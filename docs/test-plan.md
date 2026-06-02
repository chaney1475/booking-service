# 테스트 구성

BOOKING_FLOW_CASES.md의 단계별 케이스를 기반으로 작성할 테스트를 정리한다.

---

## 전체 요약

| 테스트 클래스 | 방식 | 커버 케이스 |
|---|---|---|
| `PaymentPolicyTest` | 순수 단위 (Spring 없음) | 8가지 금액·수단 조합 |
| `PointProcessorTest` | 단위 + Mock Repository | deduct 성공/실패/스킵, refund 정상/스킵 |
| `PaymentOrchestratorTest` | 단위 + Mock | APPROVED/REJECTED/UNKNOWN × 포인트 유무, 포인트 단독 |
| `BookingFacadeTest` | 단위 + Mock (핵심) | 해피패스 3종, replay 2종, 오류 케이스 6종 |
| `StockServiceIntegrationTest` | Testcontainers (실제 Redis) | reserve Lua 케이스들, 동시성 50명 vs 10재고, 이중 release 멱등성 |

---

## 테스트 스타일 기준

| 레이어 | 방식 |
|---|---|
| 서비스 단위 | `@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks` |
| 시나리오 그룹화 | `@Nested` + 한국어 `@DisplayName` |
| 인프라 (Redis) | `@Testcontainers` + 실제 Redis 컨테이너, 직접 상태 검증 |

---

## 1. PaymentPolicyTest — 단위 테스트

대상: `PaymentPolicy.validate()`  
방식: Spring 없이 순수 단위 테스트. Mock 불필요.

| 케이스 | 기대 결과 |
|---|---|
| pgAmount > 0, method=CREDIT_CARD | 통과 |
| pgAmount > 0, method=PAY | 통과 |
| pgAmount = 0, method=null | 통과 (포인트 단독) |
| pgAmount < 0 | PAYMENT_AMOUNT_MISMATCH |
| pgAmount > 0, method=null | INVALID_PAYMENT_COMBINATION |
| pgAmount > 0, method=Y_POINT | INVALID_PAYMENT_COMBINATION |
| pgAmount = 0, method=CREDIT_CARD | INVALID_PAYMENT_COMBINATION |
| pgAmount = 0, method=PAY | INVALID_PAYMENT_COMBINATION |

---

## 2. PointProcessorTest — 단위 테스트

대상: `PointProcessorImpl.deduct()` / `refund()`  
방식: `@ExtendWith(MockitoExtension)`, `UserPointRepository` / `PointTransactionRepository` Mock

| 케이스 | 기대 결과 |
|---|---|
| deduct: 잔액 충분 (rows=1) | 성공 + PointTransaction(USE) save 호출 |
| deduct: 잔액 부족 (rows=0) | INSUFFICIENT_POINT |
| deduct: amount=0 | early return, DB 호출 없음 |
| refund: 정상 | refundBalance + PointTransaction(REFUND) save 호출 |
| refund: amount=0 | early return, DB 호출 없음 |

---

## 3. PaymentOrchestratorTest — 단위 테스트

대상: `PaymentOrchestrator.process()`  
방식: `@ExtendWith(MockitoExtension)`, Gateway / OrderService / PointProcessor Mock

| 케이스 | 기대 결과 |
|---|---|
| PG APPROVED, 포인트 없음 | PaymentContext 반환, refund 미호출 |
| PG APPROVED, 포인트 있음 | deduct 1회 + PaymentContext 반환 |
| PG REJECTED, 포인트 없음 | PAYMENT_FAILED + markFailed, refund 미호출 |
| PG REJECTED, 포인트 있음 | refund 1회 + markFailed |
| PG UNKNOWN | PaymentUnknownException + markUnknown, refund 미호출 |
| pgAmount=0 (포인트 단독) | approve 미호출, PaymentContext(null, null, 0) 반환 |

---

## 4. BookingFacadeTest — 시나리오 단위 테스트 (핵심)

대상: `BookingFacade.book()`  
방식: `@ExtendWith(MockitoExtension)`, 모든 협력 객체 Mock  
그룹: `@Nested`로 해피패스 / 멱등성 / 재고 / 결제 오류로 분류

### 해피 패스

| 케이스 | 검증 포인트 |
|---|---|
| 카드 단독 | markPaid 호출, confirm 호출, setResult 호출 |
| 카드 + 포인트 | 위 동일 + deduct 호출 확인 |
| 포인트 단독 | approve 미호출, markPaid 호출 |

### 멱등성

| 케이스 | 검증 포인트 |
|---|---|
| DB Layer 1 replay (PAID 주문) | reserve/PG/markPaid 미호출, 캐시 결과 반환 |
| Redis Layer 2 replay (JSON 캐시) | reserve/PG 미호출 |
| IN_PROGRESS 중복 요청 | DUPLICATE_ENTRY, reserve 미호출 |
| UNKNOWN 주문 동일 키 재요청 | ORDER_IN_UNKNOWN_STATE |

### 재고 / 정책 오류

| 케이스 | 검증 포인트 |
|---|---|
| SOLD_OUT | release + idem 해제 호출, markFailed 미호출 |
| 포인트 초과 (pgAmount < 0) | PAYMENT_AMOUNT_MISMATCH, release + idem 해제 호출 |

### 결제 오류

| 케이스 | 검증 포인트 |
|---|---|
| PG REJECTED | release + idem 해제 호출 (markFailed는 Orchestrator가 처리) |
| PG UNKNOWN | release 미호출, idem 미해제, PaymentUnknownException 전파 |

---

## 5. StockServiceIntegrationTest — Testcontainers (실제 Redis)

대상: `StockServiceImpl` + reserve.lua / confirm.lua / release.lua  
방식: `@Testcontainers`, Redis 7 컨테이너, `@BeforeEach`마다 DB flush  
직접 상태 검증: `promoStock()`, `inflightHas()`, `purchasedHas()` 헬퍼로 Redis 상태 확인

### reserve.lua

| 케이스 | 기대 결과 |
|---|---|
| 정상 | promo_stock -1, inflight 등록 |
| SOLD_OUT | promo_stock 변화 없음 |
| ALREADY_PURCHASED | promo_stock 변화 없음 |
| DUPLICATE_ENTRY (inflight 있음) | promo_stock 변화 없음 |

### confirm.lua / release.lua

| 케이스 | 기대 결과 |
|---|---|
| confirm 정상 | purchased 등록, inflight 제거 |
| release 정상 | promo_stock +1, inflight 제거 |
| release 이중 호출 | promo_stock 1회만 복원 (멱등) |

### 동시성

| 케이스 | 기대 결과 |
|---|---|
| 재고 10개에 50명 동시 reserve | 정확히 10명만 성공, promo_stock 0 미만 불가 |

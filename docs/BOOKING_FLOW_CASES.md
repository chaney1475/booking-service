# BOOKING_FLOW_CASES.md

POST /api/booking 전체 흐름에서 각 단계마다 어떤 상황이 생겼을 때
어떻게 되는 게 정상인지를 기술한다.

---

## 1. 전체 흐름 (해피 패스)

```
Step 1. 멱등 체크 — DB         이미 완료된 요청인지 DB에서 먼저 확인
Step 2. 멱등 체크 — Redis       동시 중복 요청인지 Redis로 확인
Step 3. 재고 선점               Lua 스크립트로 내 자리 임시 확보
Step 4. 결제 정책 검증          금액·수단 조합이 올바른지 확인
Step 5. 주문 생성 (T1)          Order + Payment PENDING으로 DB 저장
Step 6. 포인트 차감             포인트 사용분 먼저 차감 (PG 호출 전)
Step 7. PG 결제 요청            카드/페이 승인 요청
Step 8. 재고 확정 + 주문 PAID (T2)  confirm.lua + Order PAID 저장
```

포인트 단독 결제는 Step 7을 건너뛰고 Step 8로 바로 간다.

---

## 2. 단계별 케이스

### Step 1. 멱등 체크 — DB

이전에 동일한 idempotency key로 요청한 이력이 있는지 DB에서 먼저 확인한다.

→ 이력 없음: 다음 단계로  
→ 이전 요청이 PAID로 완료됐으면: 저장된 결과를 그대로 반환한다. reserve/PG/DB 쓰기 없음.  
→ 이전 요청이 UNKNOWN으로 동결됐으면: 처리 불가 409. GET /orders/{id}로 상태 확인이 먼저.  
→ 이전 요청이 PENDING 또는 FAILED면: 재시도 허용, 다음 단계로

---

### Step 2. 멱등 체크 — Redis

동시에 같은 키로 요청이 들어왔는지 Redis SET NX로 확인한다.

→ 키가 없으면: IN_PROGRESS로 마킹하고 다음 단계로  
→ IN_PROGRESS가 이미 있으면: 동시 중복 요청이다. 409 반환. reserve 없음.  
→ 이전 성공 결과가 JSON으로 캐시돼 있으면: 캐시된 결과 반환. reserve/PG 없음.  
→ Redis 자체가 다운이면: 예외를 흡수하고 null로 처리해 다음 단계로 넘어간다.  
  (reserve.lua도 Redis라서 어차피 Step 3에서 막힐 가능성이 높다)

---

### Step 3. 재고 선점

Lua 스크립트로 1인 1구매 확인 + 재고 차감을 원자적으로 처리한다.

→ 정상: promo_stock -1, inflight에 등록. 다음 단계로.  
→ 재고가 없으면 (SOLD_OUT): 멱등 키 해제 후 409. 재고 변화 없음.  
→ 이 이벤트를 이미 구매한 유저면 (ALREADY_PURCHASED): 멱등 키 해제 후 409.  
→ 같은 유저의 다른 요청이 진행 중이면 (DUPLICATE_ENTRY): 멱등 키 해제 후 409.

---

### Step 4. 결제 정책 검증

(포인트 금액 + PG 금액)이 상품 가격과 맞는지, 결제 수단 조합이 올바른지 확인한다.

→ 정상: 다음 단계로  
→ 포인트가 가격보다 크면 (pgAmount < 0): 400. 재고 반납 + 멱등 키 해제.  
→ PG 금액이 있는데 결제 수단이 없거나 Y_POINT면: 400. 재고 반납 + 멱등 키 해제.  
→ PG 금액이 0인데 카드/페이가 지정됐으면: 400. 재고 반납 + 멱등 키 해제.

---

### Step 5. 주문 생성 (T1)

Order(PENDING) + Payment(PENDING)을 DB에 저장한다.

→ 정상: DB 저장 완료. 다음 단계로.  
→ DB 예외 (DB 다운 등): 재고 반납 + 멱등 키 해제 후 500.

---

### Step 6. 포인트 차감

PG 호출 전에 포인트를 먼저 차감한다. 실패 시 PG 호출 없이 즉시 보상 가능.

→ pointsToUse = 0이면: 건너뜀. 다음 단계로.  
→ 잔액 충분: 차감 + PointTransaction(USE) 기록. 다음 단계로.  
→ 잔액 부족: 재고 반납 + 멱등 키 해제 후 400 INSUFFICIENT_POINT.  
  ⚠️ 이 시점에 Step 5에서 만든 Order가 PENDING으로 남는다 (markFailed 미수행).

---

### Step 7. PG 결제 요청

PaymentGatewayRouter가 수단(카드/페이)에 맞는 Gateway로 approve를 호출한다.

→ APPROVED: 다음 단계로 (Step 8).  
→ REJECTED (한도 초과 등 명확한 거절):  
   - 포인트를 썼으면 환불한다.  
   - Order를 FAILED로 마킹한다.  
   - BookingFacade가 재고 반납 + 멱등 키 해제.  
   - 400 PAYMENT_FAILED 반환. 재시도 가능.  
→ UNKNOWN (타임아웃 또는 PG 5xx):  
   - Order를 UNKNOWN으로 마킹한다. 포인트·재고는 그대로 동결.  
   - 재고 반납도, 멱등 키 해제도 하지 않는다.  
   - 500 반환.  
   ⚠️ 카드가 실제 승인됐을 수 있어서 즉시 환불하면 이중 결제 위험.  
      배치 또는 GET /orders/{id}가 inquire()로 나중에 확정한다.

---

### Step 8. 재고 확정 + 주문 PAID (T2)

confirm.lua로 재고를 확정하고, Order를 PAID로 기록하고, 결과를 Redis에 캐시한다.

→ 전부 성공: 200 반환. 완료.  
→ confirm.lua 실패 (Redis 다운):  
   - 재고 반납 + 멱등 키 해제를 시도한다.  
   ⚠️ PG는 이미 승인됐는데 Order가 PENDING으로 남는다. 정산 배치 필요.  
→ markPaid(T2) 실패 (DB 다운):  
   - confirm은 됐는데 Order가 PAID로 기록되지 않는다.  
   ⚠️ 마찬가지로 PG 승인됐는데 Order PENDING. 정산 배치 필요.  
→ setResult 실패 (Redis 다운):  
   - 예외를 흡수한다. 응답은 200 성공.  
   - Redis replay 캐시만 없는 것. Step 1의 DB 조회로 대응 가능.

---

### UNKNOWN 이후 — GET /orders/{id}

주문 조회 시 status가 UNKNOWN이면 즉시 PG에 inquire()를 호출해 상태를 확정한다.

→ PG에서 승인됨: Order를 PAID로 확정.  
→ PG에서 거절됨: 포인트 환불 + 재고 반납 + Order FAILED.  
→ PG도 아직 모름: UNKNOWN 그대로 반환. 나중에 다시 조회하거나 배치가 처리.

---

## 3. 주의해야 할 케이스

| 케이스 | 왜 위험한가 |
|---|---|
| Step 6 포인트 부족 | Order가 PENDING 고아로 남는다. markFailed가 없음. |
| Step 8 confirm/T2 실패 | PG는 카드를 청구했는데 주문이 미확정. 정산 배치 없으면 금전 불일치. |
| Step 7 UNKNOWN 동결 | 멱등 키가 해제 안 돼 동일 키로 재시도 불가. GET으로 확정 후 재예약 필요. |

---

## 4. 테스트 대상

### PaymentPolicy — 단위 테스트

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

### PointProcessor — 단위 테스트

| 케이스 | 기대 결과 |
|---|---|
| 잔액 충분: rows=1 | 성공 + PointTransaction(USE) 저장 |
| 잔액 부족: rows=0 | INSUFFICIENT_POINT |
| amount=0 | DB 호출 없이 early return |
| refund 정상 | 잔액 복원 + PointTransaction(REFUND) 저장 |
| refund amount=0 | early return |

### PaymentOrchestrator — 단위 테스트

| 케이스 | 기대 결과 |
|---|---|
| APPROVED, 포인트 없음 | PaymentContext 반환, refund 미호출 |
| APPROVED, 포인트 있음 | deduct 1회 호출, PaymentContext 반환 |
| REJECTED, 포인트 없음 | PAYMENT_FAILED, refund 미호출 |
| REJECTED, 포인트 있음 | refund 1회 + markFailed |
| UNKNOWN | PaymentUnknownException, markUnknown, refund 미호출 |
| pgAmount=0 (포인트 단독) | approve 미호출, PaymentContext(0) 반환 |

### BookingFacade — 시나리오 테스트

| 케이스 | 검증 포인트 |
|---|---|
| 카드 단독 해피 패스 | Order=PAID, PaymentLine(CREDIT_CARD) 존재 |
| 카드 + 포인트 해피 패스 | PaymentLine 2개, 포인트 차감 확인 |
| 포인트 단독 해피 패스 | PG 미호출, PaymentLine(Y_POINT) |
| DB Layer 1 replay | PAID 주문 → DB/PG 호출 없이 200 |
| Redis Layer 2 replay | JSON 캐시 → DB/PG 호출 없이 200 |
| IN_PROGRESS 동시 중복 | 409, reserve 미호출 |
| UNKNOWN 동일 키 재요청 | 409 ORDER_IN_UNKNOWN_STATE |
| SOLD_OUT | 409, Order 미생성, 재고 변화 없음 |
| 포인트 초과 (pgAmount < 0) | 400, 재고 반납 확인 |
| PG REJECTED | Order=FAILED, 포인트 환불, 재고 반납 |
| PG UNKNOWN | Order=UNKNOWN, 포인트·재고 동결, 멱등 키 미해제 |

### 동시성 테스트

| 케이스 | 기대 결과 |
|---|---|
| 재고 10개에 50명 동시 요청 | 정확히 10명만 PAID, promo_stock 0 미만 불가 |
| 같은 유저 다른 키로 동시 요청 | inflight로 1개만 성공 |
| release.lua 이중 호출 | 재고 1회만 복원 |

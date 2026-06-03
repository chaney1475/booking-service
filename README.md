# booking-service

초특가 숙소 상품(10개 한정)에 대한 선착순 예약 시스템.
00시에 오픈되는 프로모션 상품을 분산 환경(앱 서버 2대 이상)에서 재고 정합성과 공정성을 보장하며 처리한다.

---

## 시스템 아키텍처

<img src="docs/diagram/booking_service_architecture.svg" width="600" alt="시스템 아키텍처">

| 계층 | 권위 | 핵심 역할 |
|---|---|---|
| Redis | 라이브 재고 | Lua EVAL 원자 차감 — 앱 N대에서 추가 분산 락 불필요 |
| MySQL | 영구 기록 | 주문·결제·카탈로그 무결성 |
| ShedLock | 분산 스케줄러 락 | 00시 Redis 재고 시딩을 단 1대만 실행 보장 |

---

## 시퀀스 다이어그램

### POST /booking — 예약 및 결제 (핵심 흐름)

#### 1단계 — 진입 검증 게이트

<img src="docs/diagram/booking_entry_gates_flow.svg" width="600" alt="POST /booking 진입 검증 게이트">

#### 2단계 — 결제 확정 처리

<img src="docs/diagram/booking_payment_resolution_flow.svg" width="600" alt="POST /booking 결제 확정 처리">

**흐름 핵심 요약**

| 단계 | 목적 |
|---|---|
| ① 멱등 체크 | 짧은 간격 중복 요청 → 기존 결과 즉시 반환 |
| ② reserve.lua (원자) | 1인 1구매 + oversell 방지. Redis 단일 EVAL → 분산 락 불필요 |
| ③ T1 (PG 전 커밋) | 포인트(내부) 먼저 차감. PG 실패 시 내부 보상만으로 정리 가능 |
| ④ confirm / release | PG 결과에 따라 sold 확정 또는 재고 복원 |
| ⑤ UNKNOWN 동결 | 타임아웃 ≠ 실패. 즉시 환불 시 이중 결제 위험 |

---

### GET /checkout — 주문서 조회

<img src="docs/diagram/checkout_query_flow.svg" width="600" alt="GET /checkout 주문서 조회 흐름">

---

## 도메인 모델 (ERD)

<img src="docs/diagram/booking-erd.png" width="600" alt="도메인 모델 ERD">

**주문/결제 도메인 핵심 불변식**

```
Σ order_line.line_amount  =  orders.total_amount (gross)
                          =  Σ payment_line.amount
```

| 엔티티 | 역할 | 핵심 컬럼 |
|---|---|---|
| `orders` | 결제 트랜잭션 헤더 | `idempotency_key UNIQUE`, `status`, `total_amount` |
| `order_line` | 투숙 1건 (예약 종류 담당) | `room_option_id`, `event_option_id(NULL=일반)`, `nights`, `unit_price` |
| `payment` | PG 결과 기록 | `amount(net)`, `pg_tx_ref`, `status` |
| `payment_line` | 수단별 금액 내역 | `method(CREDIT_CARD·PAY·POINT)`, `amount` |
| `event_option` | 초특가 이벤트 옵션 | `promo_price`, `promo_stock_total(=10, Redis seed 원천)` |

---

## 재고 상태 전이

<img src="docs/diagram/stock_state_transition.svg" width="600" alt="재고 상태 전이">

| 시나리오 | 결과 | 이유 |
|---|---|---|
| PG 성공 | confirm → sold 확정 | oversell 원천 차단 |
| PG 실패 | release → 재고 복원 | 보상 트랜잭션 |
| 서버 크래시 | TTL 만료 → under-sell | oversell보다 under-sell이 낫다 (운영 정리 가능) |

---

## 결제 컴포넌트 구조

<img src="docs/diagram/payment_component_structure.svg" width="600" alt="결제 컴포넌트 구조">

**지원 결제 조합**

| 조합 | 허용 |
|---|---|
| 신용카드 단독 | ✅ |
| Y페이 단독 | ✅ |
| 포인트 단독 | ✅ |
| 신용카드 + 포인트 | ✅ |
| Y페이 + 포인트 | ✅ |
| 신용카드 + Y페이 | ❌ 혼용 불가 |

**PG 응답별 처리 전략**

| PG 응답 | 처리 |
|---|---|
| APPROVED | confirm → PAID |
| REJECTED (4xx, 한도초과) | 보상 후 FAILED. 재시도 무의미 |
| 5xx (PG 서버 오류) | Gateway 내부에서 2회 재시도 후 UNKNOWN |
| 응답 타임아웃 | UNKNOWN 동결. **재시도 금지** (이중 결제 위험) |

---

## Redis 장애 Fallback

<img src="docs/diagram/redis_failure_fallback.svg" width="600" alt="Redis 장애 Fallback">

**Redis 복구 시 재구성 방법**

| 항목 | 재구성 |
|---|---|
| `promo_stock` | `promo_stock_total − COUNT(orders WHERE status IN (PAID, PENDING, UNKNOWN))` |
| `sold` | `COUNT(orders WHERE status = PAID)` |
| `purchased` SET | PAID·PENDING·UNKNOWN 주문의 userId로 재구성 |
| `inflight` key | 재구성 안 함 (TTL 휘발 정보) |

UNKNOWN까지 포함해 보수적 계산 → 최악이 under-sell. UNKNOWN을 제외하면 정산 배치가 해당 주문을 PAID로 확정할 때 oversell이 발생할 수 있다.

---

## 기술 스택

| 항목 | 선택 | 비고 |
|---|---|---|
| Language | Java 21 | |
| Framework | Spring Boot 3.4 | |
| RDB | MariaDB (MySQL 계열) | |
| Cache | Redis | 라이브 재고 권위 |
| 분산 스케줄러 락 | ShedLock 6.9 (Redis provider) | 00시 재고 시딩 중복 실행 방지 |
| ORM | Spring Data JPA (Hibernate) | |

---

## 실행 방법

**사전 요구 사항:** Docker Desktop (또는 Docker Engine + Compose v2), Java 21

```bash
# 1. 인프라 기동 (MySQL 8 + Redis 7)
docker compose up -d

# 2. 앱 실행
./gradlew bootRun
```

MySQL healthcheck 통과 후 Spring Boot가 `schema.sql`을 자동 실행하고 JPA validate를 수행한다.
접속: http://localhost:8080 / Swagger UI: http://localhost:8080/swagger-ui.html

---

## API 목록

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/checkout` | 주문서 조회 (상품 정보 + 가용 재고 + 포인트 잔액) |
| POST | `/api/booking` | 결제 및 예약 완료 |

### GET /api/checkout

주문서 진입 시 상품 정보, 재고 가용 여부, 포인트 잔액을 조회한다.
`available` 필드는 GET 시점 힌트이며, 실제 재고 점유는 POST /api/booking 에서 확정된다.

**Request**

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Header | `X-User-Id` | Long | ✅ | 사용자 ID |
| Query | `eventId` | Long | ✅ | 이벤트 ID |
| Query | `optionId` | Long | ✅ | 이벤트 옵션 ID |

**Response 200**

```json
{
  "success": true,
  "data": {
    "event": {
      "eventId": 1,
      "endsAt": "2025-01-01T00:30:00+09:00"
    },
    "product": {
      "name": "오션뷰 디럭스"
    },
    "option": {
      "optionId": 1,
      "checkInDate": "2025-01-15",
      "checkInTime": "15:00:00",
      "checkOutDate": "2025-01-16",
      "checkOutTime": "11:00:00",
      "promoPrice": 59000
    },
    "available": true,
    "userPoints": 20000
  }
}
```

**Error**

| HTTP | code | 설명 |
|---|---|---|
| 400 | `INVALID_INPUT` | 필수 파라미터 누락 |
| 404 | `EVENT_NOT_FOUND` | 이벤트 없음 |
| 404 | `EVENT_OPTION_NOT_FOUND` | 옵션 없음 |

---

### POST /api/booking

결제 수단과 금액을 입력받아 재고를 점유하고 결제를 완료한다.
`Idempotency-Key` 헤더로 중복 요청을 막는다.

**Request**

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Header | `X-User-Id` | Long | ✅ | 사용자 ID |
| Header | `Idempotency-Key` | String | ✅ | 클라이언트 발급 멱등키 (UUID 권장) |
| Body | `eventId` | Long | ✅ | 이벤트 ID |
| Body | `optionId` | Long | ✅ | 이벤트 옵션 ID |
| Body | `payments` | List | ✅ | 결제 수단 목록 (최대 2개, 혼합 불가 규칙 적용) |
| Body | `payments[].method` | String | ✅ | `CREDIT_CARD` \| `PAY` \| `POINT` |
| Body | `payments[].amount` | Long | ✅ | 해당 수단 결제 금액 (원) |

**Response 200**

```json
{
  "success": true,
  "data": {
    "orderId": 42,
    "status": "PAID",
    "totalAmount": 59000
  }
}
```

**Error**

| HTTP | code | 설명 |
|---|---|---|
| 400 | `INVALID_PAYMENT_COMBINATION` | 신용카드 + Y페이 혼용 |
| 400 | `PAYMENT_AMOUNT_MISMATCH` | 결제 합계 ≠ 상품가 |
| 400 | `INSUFFICIENT_POINT` | 포인트 잔액 부족 |
| 400 | `PAYMENT_REJECTED` | PG 한도 초과 등 거절 |
| 409 | `SOLD_OUT` | 재고 소진 |
| 409 | `ALREADY_PURCHASED` | 동일 이벤트 중복 구매 |
| 409 | `DUPLICATE_ORDER` | 이미 처리된 멱등키 |
| 503 | `BOOKING_UNAVAILABLE` | Redis 일시 장애 — 재시도 필요 |

---

### DDL

전체 테이블 정의: [`src/main/resources/schema.sql`](src/main/resources/schema.sql)

---

## 테스트

| 테스트 클래스 | 방식 | 주요 검증 |
|---|---|---|
| `BookingFacadeTest` | Mockito 단위 | 해피패스 3종, 멱등성 4종(DB/Redis replay·중복·UNKNOWN), 보상 로직 4종 |
| `PaymentOrchestratorTest` | Mockito 단위 | PG APPROVED/REJECTED/UNKNOWN × 포인트 유무, 포인트 단독 결제 |
| `StockServiceIntegrationTest` | Testcontainers (Redis 7) | Lua 원자성(reserve·confirm·release), 동시성 50명 vs 재고 10개, release 멱등성 |

```bash
./gradlew test
```

# 멱등성 설계 (Idempotency Design)

> **한 줄 요약** — 세 계층이 클라→서버 중복을 막고, orderId가 서버→PG 이중 승인을 막음.

---

## 클라이언트 → 서버 멱등성

#### 전체 흐름

```
POST /booking (Idempotency-Key: {key})
    │
    ▼
[계층 1 — DB 조회]        orders.findByIdempotencyKey(key)
    ├─ PAID    → 즉시 기존 결과 반환
    ├─ UNKNOWN → ORDER_IN_UNKNOWN 에러 + orderId 반환  ← GET /orders/{id} 조회 유도
    ├─ PENDING → 계층 2로 (IN_PROGRESS가 처리)
    ├─ FAILED  → 계층 2로 (재시도 허용)
    └─ 없음    → 계층 2로
    │
    ▼
[계층 2 — IdempotencyStore]    Redis: idem:{idempotency_key} → IN_PROGRESS | JSON  (TTL 24h)
    ├─ SET NX 성공   → 새 요청, 정상 처리 진행
    ├─ IN_PROGRESS   → 409  ← 동시 중복 차단
    └─ JSON          → 캐시된 결과 반환  ← 멱등 재생
    │
    ▼
[계층 3 — DB UNIQUE backstop]    orders.idempotency_key UNIQUE
    └─ Redis 장애·만료 + 계층 1 미스 동시 발생 시 이중 INSERT 최후 차단
```

---

#### Redis 키 구조

| 키 | 타입 | 값 | TTL | 역할 |
|---|---|---|---|---|
| `idem:{idempotency_key}` | String | `IN_PROGRESS` \| JSON | 24h | 동시 중복 차단 + 결과 캐시 |

---

#### 케이스별 처리

| 상황 | 계층 1 | 계층 2 | 처리 |
|---|---|---|---|
| 최초 요청 | 없음 | SET NX 성공 | 정상 처리 진행 |
| 완료 후 재요청 | PAID | 미도달 | 기존 결과 즉시 반환 |
| 처리 중 동시 중복 | PENDING | IN_PROGRESS | 409 |
| UNKNOWN 재시도 | UNKNOWN | 미도달 | 409 + orderId 반환 → GET /orders/{id} 유도 |
| 실패 후 재시도 | FAILED | SET NX 성공 | 재시도 허용 |

---

#### Redis 장애 시 동작

| 계층 | Redis 장애 시 |
|---|---|
| 계층 1 (DB 조회) | 정상 동작 — Redis 무관 |
| 계층 2 (IdempotencyStore) | SET NX 실패 → null 반환 → 처리 진행 |
| 계층 3 (DB UNIQUE) | 이중 INSERT 최후 차단 |

---

## 서버 → PG사 멱등성

PG 멱등키 = `orderId` (merchantUid)

클라이언트가 보낸 `Idempotency-Key`와 완전히 분리된 별도 키.
클라 키는 중복 요청 식별용, PG 키는 이중 승인 방지용으로 역할이 다름.
`orderId`는 DB PK라 서버 재시작 후에도 안정적으로 참조 가능함.

| PG 유형 | 처리 |
|---|---|
| idempotency key 지원하는 경우 | `orderId`를 PG 멱등키로 전달 → PG 내부에서 이중 승인 차단 |
| 지원하지 않는 경우 | `PaymentGateway.inquire(orderId)` 로 기존 승인 여부 확인 후 approve 호출 |

서버→PG 재시도 규칙(5xx 2회, 타임아웃 금지)은 결제 설계(payment-design.md) 재시도 전략 참조.

---

## UNKNOWN 실시간 보정 — GET /orders/{orderId}

주문 조회 시 status == UNKNOWN이면 `PaymentGateway.inquire()` 를 즉시 호출해 결과 확정.

```
GET /orders/{orderId}
    │
    └─ status == UNKNOWN
           → PG.inquire(orderId)
           │
           ├─ [승인됨 — 결제된 경우]
           │    confirm.lua + markPaid → PAID
           │    → 완료 주문서 반환
           │
           ├─ [미승인 — 문제 있는 경우]
           │    compensate.lua(재고 release) + pointRefund + markFailed → FAILED
           │    → 실패 응답, 포인트 반환 처리됨 안내
           │
           └─ [아직 결과 없음]
                UNKNOWN 그대로 반환 (배치가 사후 처리)
```

배치 잡(미구현)도 동일한 `PaymentGateway.inquire()` 인터페이스를 사용해 UNKNOWN 주문을 주기적으로 순회함.

---

## 구현 위치

```
com.example.booking
└── domain
    └── booking
        ├── idempotency
        │   └── IdempotencyStore.java    ← Redis SET NX / IN_PROGRESS / 결과 캐시
        └── service
            └── BookingServiceImpl.java  ← 계층 1(DB 조회) + 계층 2(Store) 조율
```

# booking-service

초특가 숙소 상품(10개 한정)에 대한 선착순 예약 시스템.
00시에 오픈되는 프로모션 상품을 분산 환경(앱 서버 2대 이상)에서 재고 정합성과 공정성을 보장하며 처리한다.

---

## 시스템 아키텍처

```mermaid
flowchart TB
    C["클라이언트"] --> LB["로드 밸런서"]

    LB --> App1 & App2

    subgraph app["애플리케이션 계층 (× 2)"]
        App1["App Server 1\n:8080"]
        App2["App Server 2\n:8080"]
    end

    subgraph data["데이터 계층"]
        Redis["Redis\n라이브 재고 권위\npromo_stock · sold\ninflight (TTL 90s)\npurchased SET\nShedLock 분산 락"]
        MySQL["MySQL (MariaDB)\n영구 기록 권위\n카탈로그 · 이벤트\n주문 · 결제 · 포인트"]
    end

    PG["외부 PG\n카드사 / Y페이\n(mock)"]

    App1 & App2 <-->|"Lua EVAL (원자적 재고 차감)"| Redis
    App1 & App2 <--> MySQL
    App1 & App2 -->|"결제 승인 요청"| PG
    App1 -.->|"ShedLock 분산 락\n(단 1대만 실행)"| Redis
    App1 -.->|"00시 이벤트 seed"| MySQL
```

| 계층 | 권위 | 핵심 역할 |
|---|---|---|
| Redis | 라이브 재고 | Lua EVAL 원자 차감 — 앱 N대에서 추가 분산 락 불필요 |
| MySQL | 영구 기록 | 주문·결제·카탈로그 무결성 |
| ShedLock | 분산 스케줄러 락 | 00시 Redis 재고 시딩을 단 1대만 실행 보장 |

---

## 시퀀스 다이어그램

### POST /booking — 예약 및 결제 (핵심 흐름)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as BookingService
    participant Redis
    participant DB as MySQL
    participant PG as PG Gateway

    C->>S: POST /booking
    note right of C: {idempotencyKey, eventId, optionId,<br/>userId, paymentLines}

    S->>DB: idempotency_key 조회
    DB-->>S: 

    alt 중복 요청 — 기존 주문 존재
        S-->>C: 200 기존 주문 결과 반환 (멱등)
    end

    S->>DB: event.status 확인
    alt OPEN 아님
        S-->>C: 400 EVENT_NOT_OPEN
    end

    note over S: PaymentPolicy — 조합 규칙 검증<br/>카드+페이 혼용 불가 / Σ결제금액 = 상품가

    alt 조합 오류 / 금액 불일치
        S-->>C: 400 INVALID_PAYMENT_COMBINATION
    end

    S->>Redis: reserve.lua
    note right of Redis: ① purchased SET — 이미 구매한 유저?<br/>② inflight key — 현재 결제 진행 중?<br/>③ promo_stock > 0 ?<br/>→ OK: promo_stock-1, inflight SET(TTL 90s)

    alt ALREADY_PURCHASED
        Redis-->>S: ALREADY_PURCHASED
        S-->>C: 409
    else DUPLICATE_ENTRY
        Redis-->>S: DUPLICATE_ENTRY
        S-->>C: 409
    else SOLD_OUT
        Redis-->>S: SOLD_OUT
        S-->>C: 409
    else OK — 재고 선점 완료
        Redis-->>S: OK

        note over S,DB: T1 — 로컬 트랜잭션 (PG 호출 전 커밋)
        S->>DB: point balance -= N, point_transaction(USE)
        S->>DB: Order(PENDING) + OrderLine insert

        alt 포인트 단독 결제 (PG 없음)
            S->>Redis: confirm.lua — inflight DEL · sold+1 · purchased SADD
            S->>DB: Order(PAID), Payment(SUCCESS)
            S-->>C: 201 예약 완료
        else PG 결제 포함 (카드 / Y페이)
            S->>+PG: Gateway.approve()
            PG-->>-S: PaymentOutcome

            alt APPROVED
                S->>Redis: confirm.lua
                S->>DB: Order(PAID), Payment(SUCCESS), PaymentLine × N
                S-->>C: 201 예약 완료
            else REJECTED — 한도초과 등
                S->>DB: point balance += N, point_transaction(REFUND)
                S->>Redis: release.lua — inflight DEL · promo_stock+1
                S->>DB: Order(FAILED), Payment(FAILED)
                S-->>C: 400 결제 거절
            else UNKNOWN — 응답 타임아웃
                S->>DB: Order(UNKNOWN), Payment(UNKNOWN)
                note right of S: 포인트·재고 동결<br/>정산 배치로 사후 확정
                S-->>C: 202 결제 처리 중
            end
        end
    end
```

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

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as CheckoutService
    participant Redis
    participant DB as MySQL

    C->>S: GET /checkout?eventId=&optionId=
    note right of C: Header: X-User-Id

    S->>DB: EventOption + Product 조회
    DB-->>S: 상품명, promo 가격, 체크인/아웃 시각

    S->>Redis: HGET stock:event:{e}:option:{o} promo_stock
    note right of Redis: Redis 장애 시 재고 가용 여부 = "확인 불가"로 degrade<br/>상품 정보는 MySQL에서 정상 응답

    Redis-->>S: 잔여 재고 수

    S->>DB: UserPoint.balance 조회
    DB-->>S: 가용 포인트

    S-->>C: 200 {상품정보, available, userPoints}
    note right of S: available 은 힌트.<br/>실제 점유는 POST /booking의 reserve.lua 에서 확정
```

---

## 도메인 모델 (ERD)

```mermaid
erDiagram
    users         ||--o| user_point        : "1:1"
    users         ||--o{ orders            : "1:N"
    product       ||--o{ room_option       : "1:N"
    room_option   ||--o{ event_option      : "1:N"
    event         ||--o{ event_option      : "1:N"
    orders        ||--o{ order_line        : "1:N"
    order_line    }o--|| room_option       : "재고 단위 (타입×날짜)"
    order_line    }o--o| event_option      : "promo 출처 (nullable)"
    orders        ||--|| payment           : "1:1"
    payment       ||--o{ payment_line      : "1:N"
    orders        ||--o{ point_transaction : "1:N"
```

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

```mermaid
stateDiagram-v2
    [*] --> RESERVED : reserve.lua OK\npromo_stock-1, inflight SET(TTL 90s)
    [*] --> REJECTED : SOLD_OUT / DUPLICATE_ENTRY / ALREADY_PURCHASED

    RESERVED --> CONFIRMED : PG 성공 → confirm.lua\nsold+1, purchased SADD
    RESERVED --> RELEASED  : PG 실패 → release.lua\npromo_stock+1
    RESERVED --> EXPIRED   : 서버 크래시 → TTL 90s 만료\npromo_stock 미복원 (under-sell)

    CONFIRMED --> [*]
    RELEASED  --> [*]
    EXPIRED   --> [*]
    REJECTED  --> [*]
```

| 시나리오 | 결과 | 이유 |
|---|---|---|
| PG 성공 | confirm → sold 확정 | oversell 원천 차단 |
| PG 실패 | release → 재고 복원 | 보상 트랜잭션 |
| 서버 크래시 | TTL 만료 → under-sell | oversell보다 under-sell이 낫다 (운영 정리 가능) |

---

## 결제 컴포넌트 구조

```mermaid
flowchart TD
    BS["BookingService"] --> PO["PaymentOrchestrator\n결제 흐름 총괄 · 포인트 보상 책임"]

    PO --> PP["PaymentPolicy\n조합 규칙 검증\n카드+페이 혼용 불가\nΣ금액 = 주문금액"]
    PO --> PC["PointProcessor\n포인트 차감 · 환불\n(내부 자원, Router 미경유)"]
    PO --> PGR["PaymentGatewayRouter\nmethod → Gateway 매핑"]

    PGR -->|CREDIT_CARD| CG["CardGateway\n신용카드 (mock)"]
    PGR -->|PAY| YG["YPayGateway\nY페이 (mock)"]

    NEW["새 결제 수단 추가 시\nGateway 구현체 1개\n+ Router 등록 1줄\nBookingService 수정 없음"]
    style NEW fill:#f0f8ff,stroke:#4a90d9
```

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

```mermaid
flowchart TD
    A["요청 수신"] --> B{"Redis 가용?"}

    B -->|정상| C["정상 처리"]

    B -->|장애| D{"엔드포인트"}

    D -->|"POST /booking"| E["503 반환\noversell 방지 우선\n정합성 > 가용성"]
    D -->|"GET /checkout"| F["degrade 응답\n상품 정보는 MySQL 정상 제공\n재고 가용 여부 = 확인 불가"]
```

**Redis 복구 시 재구성 방법**

| 항목 | 재구성 |
|---|---|
| `promo_stock` | `promo_stock_total − COUNT(orders WHERE status IN (PAID, PENDING))` |
| `sold` | `COUNT(orders WHERE status = PAID)` |
| `purchased` SET | PAID·PENDING 주문의 userId로 재구성 |
| `inflight` key | 재구성 안 함 (TTL 휘발 정보) |

PENDING까지 포함해 보수적 계산 → 최악이 under-sell, oversell은 0.

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

> TODO: Docker Compose 구성 및 실행 명령 추가 예정

---

## API 목록

> TODO: Booking API 구현 후 추가 예정

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/checkout` | 주문서 조회 (상품 정보 + 가용 재고 + 포인트 잔액) |
| POST | `/api/booking` | 결제 및 예약 완료 |

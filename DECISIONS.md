# DECISIONS.md

설계 과정에서 판단한 주요 기술적 쟁점과 선택의 근거를 정리함.

---

## 1. 재고 & 공정성

### 쟁점 1. Redis를 재고 Single Source of Truth로 선택

#### 상황

분산 환경(앱 서버 2대+)에서 00시 오픈 시 500~1000 TPS 트래픽이 집중됨.
10개 한정 재고를 MySQL에서 SELECT → UPDATE로 처리하면 행 락 경합이 병목이 됨.

#### 선택

이벤트 재고의 실시간 카운터를 Redis에 두고, MySQL은 주문·결제 영구 기록만 담당.

#### 판단 근거

MySQL 단독 방식은 `SELECT FOR UPDATE`로 행 락을 잡아야 oversell을 막을 수 있습니다. 동시 요청이 몰리면 락 대기가 직렬화되어 TPS가 급감합니다. 
Redis는 싱글 스레드 커맨드 모델로 락 없이 원자 연산이 가능하고, 인메모리라 응답이 빠릅니다. 단점은 장애 시 데이터 유실 위험인데, MySQL orders 테이블로 재구성 가능하도록 설계해 허용 범위로 봅니다.
Redis 단일 인스턴스 기준 약 10만 ops/sec으로 500~1000 TPS 피크는 처리 가능 범위의 1% 수준입니다. TPS 1,000 초과 또는 이벤트 유실 방지 요건이 생기면 Kafka로 예약 요청을 큐잉하는 구조로 전환 가능합니다.

---

### 쟁점 2. Lua EVAL 원자 차감으로 분산 락 제거

#### 상황

앱 서버 2대가 동시에 같은 재고를 차감할 경우 oversell 위험이 있음. 일반적으로 분산 환경에서는 Redlock 등 분산 락으로 직렬화하는 방식을 사용함.

#### 선택

분산 락 없이 Lua 스크립트를 Redis EVAL로 실행. 재고 확인 + 차감을 하나의 스크립트 안에서 원자 처리.

#### 판단 근거

Redis EVAL은 Lua 스크립트 전체를 중단 없이 직렬 실행합니다. 검사(재고 > 0 확인)와 차감(`HINCRBY -1`)이 한 EVAL 안에 묶여 있어 두 서버가 동시에 진입해도 race condition이 발생하지 않습니다.
Redlock 방식은 락 획득 실패 시 재시도 대기가 생기고 구현 복잡도가 높아집니다. Lua EVAL로 동일한 안전성을 더 단순하게 달성 가능해 분산 락을 제거합니다.

---

### 쟁점 3. Under-sell 허용, Oversell 절대 금지

#### 상황

서버 장애로 `confirm` / `release`가 호출되지 못하면 inflight key가 TTL 90s 후 만료되면서 차감된 `promo_stock`이 복원되지 않음. 해당 재고 슬롯이 잠기는 under-sell이 발생함.

#### 선택

서버 장애로 인한 under-sell은 허용 범위로 봄. Oversell은 어떤 경우에도 허용하지 않음.

#### 판단 근거

Oversell은 돈을 받고 자리가 없는 상황으로 불가역적 피해가 발생합니다. Under-sell은 MySQL `orders` 테이블에 `PENDING` 상태로 남은 주문을 통해 잠긴 재고를 사후 확인·운영 정리가 가능합니다. 
이를 방지하려면 TTL 만료 시 재고를 회수하는 lazy reclaim 로직(ZSET 순회 등)이 필요한데, `reserve.lua`의 복잡도가 크게 높아집니다. 10개 한정 상품에서 서버 장애는 극히 드문 케이스이므로 단순성을 택합니다.

---

### 쟁점 4. 1인 1구매 제한 — 이벤트 단위

#### 상황

요건 "모든 사용자에게 동등한 기회 제공"을 구체적인 정책으로 해석해야 함.

#### 선택

한 사용자가 동일 이벤트에서 여러 옵션을 중복 구매할 수 없도록 **이벤트 단위** 1인 1구매 제한 적용. Redis `purchased:event:{eventId}` SET에 구매 완료한 userId를 기록하고, `reserve.lua` 진입 시 SISMEMBER로 차단.

#### 판단 근거

"동등한 기회"를 "한 사람이 한정 재고를 독점하지 않는 것"으로 해석합니다. 이벤트 내 옵션이 여러 개일 때 옵션별로 허용하면 한 사용자가 다수의 슬롯을 선점 가능합니다. 
이벤트 단위로 묶으면 1인 최대 1박만 가능하므로 나머지 재고가 다른 사용자에게 돌아갑니다.

---

## 2. 결제 & 멱등성

### 쟁점 5. T1/T2 분리 — 포인트 먼저, PG 나중

#### 상황

복합 결제(포인트 + 카드/Y페이) 시 내부 자원(포인트)과 외부 자원(PG)을 모두 처리해야 함. PG 실패 시 이미 차감한 포인트를 어떻게 복원할지 보상 경로 설계가 필요함.

#### 선택

포인트 차감 + 주문 PENDING을 T1(로컬 트랜잭션)으로 PG 호출 전에 먼저 커밋. PG 승인 후 주문 PAID + 결제 기록을 T2로 커밋.

#### 판단 근거

PG를 먼저 호출하면 실패 시 PG 취소 요청(외부 호출)이 필요하고, 그 취소마저 실패할 수 있어 보상 경로가 복잡해집니다.
포인트를 먼저 차감하면 PG 실패 시 내부 DB 보상만으로 정리 가능합니다. 포인트 잔액 부족도 PG 호출 전에 즉시 감지됩니다.
PG가 항상 마지막이므로 이 설계에서 PG 취소를 호출하는 경로가 구조적으로 없습니다.

---

### 쟁점 6. UNKNOWN 동결 — 타임아웃을 실패로 처리하지 않음

#### 상황

PG 응답 타임아웃 발생 시 요청이 PG에 실제로 도달했는지 알 수 없음. 
즉시 실패로 처리하고 포인트를 환불하면, PG가 실제로 승인한 경우 카드는 청구됐는데 포인트도 환불되는 이중 결제 위험이 생김.

#### 선택

타임아웃 시 주문을 `UNKNOWN` 상태로 동결. 포인트·재고를 그대로 유지하고 정산 배치로 사후 확정.

#### 판단 근거

타임아웃은 실패가 아니라 결과 불명 상태입니다. 즉시 환불 시 이중 결제 위험이 발생하고, 즉시 재시도 시 이중 승인 위험이 발생합니다.
UNKNOWN으로 동결하면 최악의 경우 사용자 불편으로 끝나지만, 잘못된 즉시 처리는 금전 피해로 이어집니다.
정산 배치가 `PaymentGateway.inquire()`로 결과를 확정하는 구조는 설계해뒀고, 실제 배치 잡 구현은 PG 연동 생략 범위와 동일하게 미구현으로 남깁니다.

---


### 쟁점 8. PaymentGateway OCP 확장 구조

#### 상황

신용카드, Y페이 외에 향후 새로운 결제 수단이 추가될 수 있음. 수단이 추가될 때마다 BookingFacade를 수정하면 기존 결제 흐름에 영향을 줄 수 있음.

#### 선택

`PaymentGateway` 인터페이스를 두고, 수단별 구현체(`CardGateway`, `YPayGateway`)를 `PaymentGatewayRouter`에 등록하는 구조로 설계. 새 수단 추가 시 구현체 1개 + Router 등록 1줄만 추가하면 됨.

#### 판단 근거

BookingFacade는 `PaymentGatewayRouter`를 통해 수단에 무관하게 동일한 인터페이스로 호출합니다. 새 수단이 추가되더라도 기존 코드를 수정하지 않아 OCP를 만족합니다. 포인트는 외부 PG가 아닌 내부 자원이므로 Router를 거치지 않고 `PointProcessor`가 직접 처리합니다.

---

### 쟁점 9. 멱등성 처리 — 세 계층 + 서버→PG 분리

#### 상황

중복 처리 위험이 두 축에서 발생함. 클라이언트 재전송은 주문·포인트·재고 이중 처리 위험이 있고, 서버가 PG로 보낸 승인 요청 재전송은 이중 승인 위험이 있음.

#### 선택

클라→서버 멱등성은 세 계층으로 처리.

1. **DB 조회 (1st)**: `orders.findByIdempotencyKey()` — PAID 즉시 반환, UNKNOWN 차단, PENDING·FAILED는 다음 계층으로
2. **IdempotencyStore (2nd)**: Redis SET NX — IN_PROGRESS(동시 중복 → 409), JSON(캐시 재생)
3. **DB UNIQUE (3rd)**: `orders.idempotency_key UNIQUE` — Redis 장애·만료 시 이중 INSERT 최후 차단

서버→PG 멱등성은 `orderId`(merchantUid)를 PG 멱등키로 사용해 이중 승인 방지. 클라 `Idempotency-Key`와 역할이 다르므로 분리.

#### 판단 근거

**① 클라이언트 → 서버**

DB 조회를 1순위로 두는 이유는 Redis TTL(24h) 만료 후에도 PAID 주문을 정상 반환할 수 있고, Redis 장애 시에도 1계층이 온전히 동작하기 때문입니다.
IdempotencyStore(Redis)가 2순위로 동시 중복을 빠르게 차단하고, DB UNIQUE가 최후 보루로 작동합니다.

**② 서버 → PG사**

`orderId`(merchantUid)를 PG 멱등키로 사용. PG가 멱등키를 지원하면 동일 키 재전송 시 기존 승인 결과를 반환해 이중 청구를 차단합니다.
지원하지 않으면 approve 전에 `inquire(orderId)`로 기존 승인 여부를 확인합니다.

---

## 3. 고가용성 & 장애 대응

> 전략별 동작 흐름과 검증 결과는 [`docs/high-availability-design.md`](docs/high-availability-design.md) 참조.

### 쟁점 10. Redis Sentinel — 인프라 단 장애 자동 복구

#### 상황

Redis 단일 인스턴스 장애 시 재고 키 접근이 불가해 예약 서비스가 전면 503이 됨. 장애 지속 시간이 서비스 중단 시간과 직결됨.

#### 선택

프로덕션에서 Redis Sentinel 구성. Master 1 + Replica 2 + Sentinel 3.
로컬 개발은 단일 인스턴스(docker-compose.yml, AOF 활성화). 앱은 `spring.data.redis.sentinel` 설정만 교체하면 코드 수정 없이 전환 가능.

#### 판단 근거

**Sentinel을 선택한 이유 (vs Cluster)**

현재 이벤트 재고는 단일 키셋으로 관리되며 샤딩이 불필요합니다. Redis Cluster는 샤딩과 고가용성을 동시에 제공하지만 운영 복잡도가 높습니다. Sentinel은 단순 Master/Replica 구조로 자동 failover만 제공하므로 이 규모에 적합합니다.

**Sentinel 동작 흐름**

Master 장애 감지(down-after-milliseconds, 기본 30s → 튜닝으로 10s 이내) → Sentinel 과반수 합의 → Replica 중 하나를 Master로 자동 승격 → 앱이 새 Master 주소로 재연결.

**서킷 브레이커와의 역할 분리**

| 레이어 | 역할 | 담당 |
|---|---|---|
| 인프라 단 | Redis 자체 복구 (failover) | Sentinel |
| 앱 단 | 복구 전 공백 기간 빠른 실패 | Resilience4j 서킷 브레이커 |

Sentinel failover 완료까지의 10~30s 공백을 서킷 브레이커가 흡수합니다. Redis가 복구되면 서킷이 HALF-OPEN → CLOSED로 자동 복귀해 정상 처리가 재개됩니다.

**Redis 장애 시 재구성 방법**

| 키 | 재구성 |
|---|---|
| `promo_stock` | `promo_stock_total − COUNT(orders WHERE status IN (PAID, PENDING, UNKNOWN))` |
| `sold` | `COUNT(orders WHERE status = PAID)` |
| `purchased` SET | PAID·PENDING·UNKNOWN 주문의 userId로 재구성 |
| `inflight` key | 재구성 안 함 (TTL 휘발 정보) |

UNKNOWN까지 포함해 보수적 계산 → 최악이 under-sell. UNKNOWN을 제외하면 정산 배치가 해당 주문을 PAID로 확정할 때 oversell이 발생할 수 있습니다.

---

### 쟁점 11. ShedLock — 분산 스케줄러 중복 실행 방지

#### 상황

00시 이벤트 오픈 직전 Redis에 재고를 시딩해야 함. 앱 서버가 2대 이상이므로 Spring `@Scheduled`만 사용하면 동시에 두 서버가 같은 스케줄을 실행해 재고가 이중 초기화될 위험이 있음.

#### 선택

ShedLock을 도입해 Redis에 분산 락을 잡고 단 한 대만 시딩 스케줄을 실행하도록 보장.

#### 판단 근거

앱 서버 2대+ 분산 환경에서 `@Scheduled`를 그대로 사용하면 모든 서버가 동시에 스케줄을 실행해 재고가 이중 초기화됩니다. ShedLock으로 단 한 대만 실행하도록 보장합니다.
별도 분산 락을 직접 구현하는 것보다 ShedLock이 Spring `@Scheduled`와 자연스럽게 통합되고 검증된 라이브러리입니다. `lockAtMostFor`(2분)로 서버 장애 시 락이 자동 해제돼 다음 서버가 이어받을 수 있습니다. `lockAtLeastFor`(10초)로 빠르게 완료된 경우에도 중복 실행을 방지합니다. 시딩에는 `HSETNX`를 사용해 서버 재시작이나 락 경쟁 상황에서도 기존 재고를 덮어쓰지 않도록 멱등성을 보장합니다.

---

### 쟁점 12. Redis 장애 격리 — Fail-closed / Degrade 전략 + 서킷 브레이커

#### 상황

Redis 장애 시 두 가지 문제가 동시에 발생함. 첫째, Redis 호출이 타임아웃까지 블록되면서 스레드가 묶여 cascading failure로 이어짐. 둘째, 원자적 재고 차감 수단이 없는 상태에서 예약을 허용해도 되는지 판단이 필요함.

#### 선택

경로별로 전략을 분리함.
- **POST /booking (`reserve`)**: fail-closed — 503 `BOOKING_UNAVAILABLE`으로 즉시 거부
- **GET /checkout (`isAvailable`)**: degrade — `false` 반환으로 partial 응답 허용
- **`confirm` / `release`**: best-effort — 로그만 기록, 주문 PENDING 유지

Resilience4j `@CircuitBreaker`를 `StockServiceImpl` 메서드에 적용. 실패율 50% 초과 시 서킷을 열어 Redis 요청 없이 즉시 fallback으로 전환함.

| 메서드 | 서킷 오픈 시 동작 | 이유 |
|---|---|---|
| `isAvailable` | `false` 반환 (degrade) | GET 부수효과 없음, partial 응답 허용 |
| `reserve` | 503 `BOOKING_UNAVAILABLE` | 재고 보장 없이 예약 허용 불가 |
| `confirm` | 로그만 기록 | 주문 PENDING 유지, 정산 배치가 사후 처리 |
| `release` | 로그만 기록 | under-sell 허용 범위 (쟁점 3 참조) |

서킷은 30s 후 HALF-OPEN으로 전환해 5회 시험 요청으로 Redis 복구를 확인함. Redis가 느리게 응답하는 부분 장애도 감지하도록 1s 초과 호출을 slow call로 집계하고, slow call 비율이 80% 이상이면 서킷을 오픈함.

#### 판단 근거

Redis 없이 예약을 허용하면 oversell 방지 수단이 없어 정합성이 깨집니다. 반면 GET /checkout은 부수효과가 없어 재고 정보만 빠진 partial 응답으로 degrade가 가능합니다. 정합성 > 가용성 원칙을 예약 경로에 일관되게 적용하고, 조회 경로는 가용성을 유지합니다.

Spring Boot 3 공식 지원 라이브러리로 `@CircuitBreaker` 어노테이션 하나로 AOP 기반 적용이 가능합니다. Hystrix는 deprecated, Spring Cloud CircuitBreaker는 추상화 레이어가 추가되어 설정이 복잡해집니다. `ignore-exceptions`에 `BaseException`을 등록해 SOLD_OUT 등 비즈니스 예외는 실패 카운트에서 제외합니다.

**설정값 선택 이유**

COUNT_BASED는 트래픽 변동 폭이 크면 윈도우 지속 시간이 불안정해집니다. 평시 50 TPS에서는 size=10 윈도우가 200ms를 커버하지만, 프로모션 피크 1000 TPS에서는 10ms로 줄어들어 Redis 순간 지연 하나로도 서킷이 오픈될 수 있습니다. TIME_BASED로 전환해 10초 고정 윈도우 안에서 실패율을 측정하면 TPS 변화에 무관하게 안정된 신호를 얻을 수 있습니다.

wait-duration-in-open-state를 30s로 설정한 이유는 Redis 복구나 Sentinel failover 완료까지 통상 10~30s가 걸리는데, 10s면 OPEN→HALF_OPEN 반복 진동이 발생해 회복 중인 Redis에 불필요한 부하를 줄 수 있기 때문입니다.

HALF_OPEN 시험 요청을 5회로 늘린 이유는 피크 TPS 환경에서 3회로는 통계적으로 부족해 오류 판정이 불안정할 수 있기 때문입니다.

---

## 4. 도메인 & 데이터 설계

### 쟁점 14. OrderLine 설계 — 일반 연박 주문 확장 고려

#### 상황

현재 구현은 이벤트 재고 1박 구매만 존재함. 일반 객실 연박 주문도 필요하며, 이를 나중에 수용하기 위해 Order 구조를 확장성 있게 설계하였습니다.

#### 선택

```
Order                  ← 주문 건 (예약 종류 모름)
 └── OrderLine         ← 투숙 1건 (무엇을 샀는지 아는 유일한 레이어)
       ├── room_option_id      (FK, 필수)
       ├── event_option_id     (FK, nullable — promo면 채워짐, 일반이면 NULL)
       └── nights              (현재 1 고정, 연박 시 N)
```

| 예약 종류 | event_option_id | nights |
|---|---|---|
| 이벤트 1박 (현재) | 채워짐 | 1 |
| 일반 1박 | NULL | 1 |
| 일반 연박 | NULL | N |

#### 판단 근거

연박 예약 수용이 설계의 핵심 동기입니다. Order를 주문 건으로만 유지하고 "무엇을 샀는지"는 OrderLine이 아는 구조로 분리하면, 연박이나 일반 예약을 추가할 때 Order와 결제 흐름 코드는 수정 없이 OrderLine 레벨에서만 처리 가능합니다.
향후 날짜별 가격·취소·정산이 필요해지면 `OrderLineNight`을 추가하는 방향으로 확장 가능합니다.

---

### 쟁점 15. Timezone — ZonedDateTime 기본, 달력 날짜는 LocalDate 예외

#### 상황

숙소 체크인/아웃 시각은 숙소 현지 벽시계 시각임. 모든 필드를 ZonedDateTime으로 통일하면 체크인 날짜에도 타임존 변환이 발생해 클라가 자기 로컬 타임존으로 변환하면 날짜가 달라지는 문제가 생김.

#### 선택

```
절대 시점 (이벤트 오픈·마감, 결제 시각 등)  → ZonedDateTime
달력 날짜 (체크인·체크아웃 날짜)            → LocalDate   ("2024-06-12")
숙소 벽시계 시각 (체크인·체크아웃 시각)      → LocalTime   ("15:00:00")
숙소 타임존                               → Product.timezone (IANA)
```

#### 판단 근거

"6월 12일 체크인"은 전 세계 어느 타임존에서 예약하더라도 한국 숙소 기준 6월 12일이어야 합니다.
Agoda, Expedia, Booking.com 모두 체크인/아웃 날짜를 `YYYY-MM-DD`로 주고받으며 절대 시점이 아닌 달력 날짜에 타임존을 붙이지 않는 것이 OTA 업계 공통 관례입니다.
타임존 정보는 `Product.timezone`(IANA)에 귀속시켜 글로벌 숙소 추가 시 필드 값만 교체하면 됩니다.

---

### 쟁점 16. UserPoint 설계 단순화 + JPQL UPDATE 원자 처리

#### 상황

포인트 설계의 정석은 `point_event`(이력 로그) + `point_balance`(잔액) 두 테이블 구조임. 이력을 통해 감사, 분쟁 처리, 잔액 재산출이 가능함. 다만 만료 처리, 유형별 세분화, 어드민 조정 등 완전한 이력 시스템은 포인트 전용 도메인 수준의 구현이 필요해 현재 구현 범위를 벗어남.

#### 선택

`user_point` 테이블(balance 컬럼) + `point_transaction` 테이블(USE/REFUND 이력)로 구성. 잔액 차감·환불은 `@Modifying @Query` JPQL UPDATE 단일 문장으로 처리.

```sql
-- 차감 (잔액 부족 시 0 rows 반환)
UPDATE user_point SET balance = balance - :amount WHERE user_id = :userId AND balance >= :amount

-- 환불
UPDATE user_point SET balance = balance + :amount WHERE user_id = :userId
```

#### 판단 근거

단순화한 구조(`user_point` + `point_transaction`)로도 장애 시 잔액 복구가 가능합니다. `point_transaction`의 USE/REFUND 이력을 합산하면 balance를 재산출할 수 있어 최소한의 감사 추적은 유지됩니다. 완전한 이력 시스템은 향후 포인트 도메인이 독립될 때 추가하는 방향으로 남겨둡니다.

비관적 락(SELECT FOR UPDATE + UPDATE, 2회 왕복)보다 UPDATE 단일 문장이 더 원자적입니다.
DB가 `WHERE balance >= :amount` 조건 평가와 차감을 한 번에 처리하므로 별도 락 없이 race condition이 없습니다. `rows == 0`이면 잔액 부족으로 판단해
`INSUFFICIENT_POINT`를 throw합니다. 단일 행 UPDATE이므로 데드락 사이클이 구조적으로 불가능합니다.

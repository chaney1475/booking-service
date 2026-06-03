# DECISIONS.md

설계 과정에서 판단한 주요 기술적 쟁점과 선택의 근거를 정리했습니다.

---

## 1. 재고 정합성 & 공정성

> 요구사항: 00시 트래픽 집중 상황에서 초과판매·미달 없는 재고 정합성 보장, 모든 사용자에게 동등한 확률로 구매 기회 제공

### 쟁점 1. Redis를 재고 Single Source of Truth로 선택

#### 상황

분산 환경(앱 서버 2대+)에서 00시 오픈 시 500~1000 TPS 트래픽이 집중됩니다.
10개 한정 재고를 MySQL에서 SELECT → UPDATE로 처리하면 행 락 경합이 병목이 될 것이라고 판단했습니다.

#### 선택

이벤트 재고의 실시간 카운터를 Redis에 두고, MySQL은 주문·결제 영구 기록만 담당하도록 구현했습니다.

#### 판단 근거

MySQL 단독으로 가면 oversell을 막기 위해 `SELECT FOR UPDATE`로 행 락을 잡아야 하는데, 00시처럼 동시 요청이 몰리는 상황에서는 이 락 대기가 직렬화되면서 TPS가 급감합니다.
반면 Redis는 싱글 스레드 기반이라 락 없이도 원자 연산을 보장하고 인메모리 구조라 응답 속도까지 빠르기 때문에,
같은 정합성을 락 경합 없이 확보할 수 있다고 봤습니다.

다만 Redis는 장애 시 데이터가 유실될 수 있는데, 이 위험은 Sentinel + AOF·RDB 스냅샷 영속성 옵션으로 최소화하고(쟁점 5 참조),
유실이 발생하더라도 MySQL orders 테이블로 재고를 재구성할 수 있도록 설계했습니다.

Redis 단일 인스턴스 기준 약 10만 ops/sec으로, 500~1000 TPS 피크는 처리 가능 범위라고 판단했습니다.
TPS가 1,000을 크게 초과하거나 이벤트 유실 방지 요건이 추가될 경우, Kafka 기반 이벤트 스트리밍 구조 도입을 고려해보겠습니다.

---

### 쟁점 2. Lua EVAL 원자 차감으로 분산 락 제거

#### 상황

앱 서버 2대가 동시에 같은 재고를 차감할 경우 oversell 위험이 있습니다.
일반적으로 분산 환경에서는 Redlock 등 분산 락으로 직렬화하는 방식을 사용합니다.

#### 선택

분산 락 없이 Lua 스크립트를 Redis EVAL로 실행하도록 구현했습니다. 재고 확인 + 차감을 하나의 스크립트 안에서 원자 처리합니다.

#### 판단 근거

Redis EVAL은 Lua 스크립트 전체를 중단 없이 직렬 실행합니다.
검사(재고 > 0 확인)와 차감(`HINCRBY -1`)이 한 EVAL 안에 묶여 있어, 두 서버가 동시에 진입해도 race condition이 발생하지 않습니다.
Redlock 방식은 락 획득 실패 시 재시도 대기가 생기고 구현 복잡도가 높아집니다.
Lua EVAL만으로 동일한 수준의 정합성을 확보할 수 있다고 판단해 별도 분산 락은 도입하지 않았습니다.

---

### 쟁점 3. Under-sell 허용, Oversell 절대 금지

#### 상황

서버 장애로 `confirm` / `release`가 호출되지 못하면, inflight key가 TTL 90s 후 만료되면서 차감된 `promo_stock`이 복원되지 않습니다.
결과적으로 일부 재고가 판매 불가능한 상태로 남는 under-sell이 발생할 수 있다고 판단했습니다.

#### 선택

서버 장애로 인한 under-sell은 허용 범위로 두고, oversell은 어떤 경우에도 허용하지 않도록 설계했습니다.

#### 판단 근거

Oversell은 돈을 받고 자리가 없는 상황으로 불가역적 피해가 발생합니다.
Under-sell은 MySQL `orders` 테이블에 `PENDING` 상태로 남은 주문을 통해 잠긴 재고를 사후 확인·운영 정리할 수 있습니다.
이를 방지하려면 TTL 만료 시 재고를 회수하는 lazy reclaim 로직(ZSET 순회 등)이 필요한데,
그러면 `reserve.lua`의 복잡도가 크게 높아집니다.
10개 한정 상품에서 서버 장애는 발생 가능성이 낮은 시나리오라고 판단해, 구현 복잡도보다 단순성을 우선했습니다.

---

### 쟁점 4. 1인 1구매 제한 — 동등한 확률 보장

#### 상황

요구사항에서 "모든 사용자가 동등한 확률로 상품을 구매할 수 있는 구조"를 구체적인 정책으로 해석해야 한다고 봤습니다.

#### 선택

한 사용자가 동일 이벤트에서 여러 옵션을 중복 구매할 수 없도록 **이벤트 단위** 1인 1구매 제한을 적용했습니다.
Redis `purchased:event:{eventId}` SET에 구매 완료한 userId를 기록하고, `reserve.lua` 진입 시 SISMEMBER로 차단하도록 구현했습니다.

#### 판단 근거

**동등한 확률의 해석**

재고가 10개이고 참여자가 N명일 때, 1인 1구매 제한이 없으면 일부 사용자가 여러 건을 선점해 다른 사용자의 구매 확률이 0이 될 수 있습니다.
1인 1구매를 보장해야만 각 사용자가 최대 1개의 기회를 갖는 구조가 성립하므로, 이것이 "동등한 확률"의 필요조건이라고 해석했습니다.
이벤트 내 옵션이 여러 개일 때 옵션별로 허용하면 한 사용자가 다수의 슬롯을 선점할 수 있어, 이벤트 단위로 묶었습니다.

**선착순을 유지한 이유**

추첨 방식이 수학적으로 더 균등하지만, 오픈 시간 동안 모든 요청을 수집한 뒤 추첨·결과 통보하는 별도 플로우가 필요해 구현 복잡도가 크게 높아집니다.
선착순은 요청 순서대로 즉시 처리하므로 UX가 명확하고, 1인 1구매 제한이 적용된 선착순은 "독점 없는 동등한 기회"라는 요건을 충족한다고 판단했습니다.
봇·매크로 대응은 rate-limiting·CAPTCHA 등 별도 보안 레이어의 문제로, 예약 시스템 범위를 벗어난다고 봤습니다.

---

## 2. 고가용성

> 요구사항: TPS 급증 대비 시스템 붕괴 방지, 평시 50 TPS / 프로모션 500~1000 TPS 대응
> 전략별 동작 흐름과 성능 측정 결과는 [`docs/high-availability-design.md`](docs/high-availability-design.md), [`docs/performance.md`](docs/performance.md) 참조.

### 쟁점 5. Redis Sentinel — 인프라 단 장애 자동 복구

#### 상황

Redis 단일 인스턴스 장애 시 재고 키 접근이 불가해 관련 API가 모두 사용 불가능합니다. 장애 지속 시간이 곧 서비스 중단 시간과 직결됩니다.

#### 선택

실제 프로덕션에서 Redis Sentinel을 구성하겠습니다. Master 1 + Replica 2 + Sentinel 3 구성입니다.
로컬 개발은 단일 인스턴스(docker-compose.yml, AOF 활성화)로 두고, 앱은 `spring.data.redis.sentinel` 설정만 교체하면 코드 수정 없이 전환 가능하도록 했습니다.

#### 판단 근거

**Sentinel을 선택한 이유 (vs Cluster)**

현재 이벤트 재고는 단일 키셋으로 관리되며 샤딩이 불필요합니다.
Redis Cluster는 샤딩과 고가용성을 동시에 제공하지만 운영 복잡도가 높습니다.
Sentinel은 단순 Master/Replica 구조로 자동 failover만 제공하므로 이 규모에 적합하다고 판단했습니다.

**Sentinel 동작 흐름**

Master 장애 감지(down-after-milliseconds, 기본 30s → 튜닝으로 10s 이내) → Sentinel 과반수 합의 → Replica 중 하나를 Master로 자동 승격 → 앱이 새 Master 주소로 재연결.

**서킷 브레이커와의 역할 분리**

| 레이어 | 역할 | 담당 |
|---|---|---|
| 인프라 단 | Redis 자체 복구 (failover) | Sentinel |
| 앱 단 | 복구 전 공백 기간 빠른 실패 | Resilience4j 서킷 브레이커 |

Sentinel failover가 완료될 때까지 발생하는 10~30초의 공백 구간을 서킷 브레이커가 완화합니다. Redis가 복구되면 서킷이 HALF-OPEN → CLOSED로 자동 복귀해 정상 처리가 재개됩니다.

**Redis 장애 시 재구성 방법**

| 키 | 재구성 |
|---|---|
| `promo_stock` | `promo_stock_total − COUNT(orders WHERE status IN (PAID, PENDING, UNKNOWN))` |
| `sold` | `COUNT(orders WHERE status = PAID)` |
| `purchased` SET | PAID·PENDING·UNKNOWN 주문의 userId로 재구성 |
| `inflight` key | 재구성 안 함 (TTL 휘발 정보) |

UNKNOWN까지 포함해 보수적으로 계산하므로 최악이 under-sell입니다. UNKNOWN을 제외하면 정산 배치가 해당 주문을 PAID로 확정할 때 oversell이 발생할 수 있습니다.

---

### 쟁점 6. ShedLock — 분산 스케줄러 중복 실행 방지

#### 상황

00시 이벤트 오픈 직전 Redis에 재고를 시딩해야 합니다. 앱 서버가 2대 이상이므로 Spring `@Scheduled`만 사용하면 동시에 두 서버가 같은 스케줄을 실행해 재고가 이중 초기화될 위험이 있다고 봤습니다.

#### 선택

ShedLock을 도입해 Redis에 분산 락을 잡고, 단 한 대만 시딩 스케줄을 실행하도록 보장했습니다.

#### 판단 근거

앱 서버 2대+ 분산 환경에서 `@Scheduled`를 그대로 사용하면 모든 서버가 동시에 스케줄을 실행해 재고가 이중 초기화됩니다.
별도 분산 락을 직접 구현하는 것보다 ShedLock이 Spring `@Scheduled`와 자연스럽게 통합되고 검증된 라이브러리라고 판단했습니다.
`lockAtMostFor`(2분)로 서버 장애 시 락이 자동 해제돼 다음 서버가 이어받을 수 있고,
`lockAtLeastFor`(10초)로 빠르게 완료된 경우에도 중복 실행을 방지합니다.
시딩에는 `HSETNX`를 사용해 서버 재시작이나 락 경쟁 상황에서도 기존 재고를 덮어쓰지 않도록 멱등성을 보장했습니다.

---

### 쟁점 7. 콜드 스타트 방지 — WarmupRunner + 커넥션 풀 튜닝

#### 상황

00시 오픈 직후 1,000 VU가 동시에 몰리는 상황에서, JVM JIT 미컴파일·HikariCP cold 상태·Tomcat 스레드 풀 미확장으로 인해 개선 전 성공률이 17%(171/1,000)에 불과했고 829건이 connection reset으로 실패했습니다.

#### 선택

`ApplicationReadyEvent` 시점에 자기 자신에게 병렬 HTTP 요청(50개)을 보내는 `WarmupRunner`를 추가했습니다.
Tomcat max-threads 400, min-spare 200, HikariCP maximum-pool-size 50, minimum-idle 20으로 설정했습니다.

#### 판단 근거

서비스 레이어 직접 호출은 Tomcat 스레드 풀을 확장하지 않습니다. 실제 HTTP 요청을 보내야 JIT, HikariCP, Lettuce, Tomcat이 전체 스택 기준으로 초기화됩니다. 워밍업 후 성공률 17% → 100%, connection reset 0건으로 개선됐습니다.

HikariCP pool-size는 30이면 DB 커넥션 대기로 p95가 급등하고 100이면 MySQL 자체가 병목이 됩니다. 50이 로컬 단일 MySQL 기준 스위트스팟으로 확인됐습니다. Tomcat min-spare 200은 기동 직후 1대 장애 시 나머지 서버가 피크 TPS 전체를 혼자 수용할 수 있도록 잡은 값입니다.

---

## 3. 멱등성

> 요구사항: 짧은 간격의 연속 결제 요청에도 중복 처리 없이 동일 결과 반환

### 쟁점 8. 멱등성 처리 — 세 계층 + 서버→PG 분리

#### 상황

중복 처리 위험이 두 가지 상황에서 발생합니다.
클라→서버, 서버→PG사.

#### 선택

클라→서버 멱등성은 세 계층으로 처리하도록 구현했습니다.

1. **DB 조회 (1st)**: `orders.findByIdempotencyKey()` — PAID 즉시 반환, UNKNOWN 차단, PENDING·FAILED는 다음 계층으로
2. **IdempotencyStore (2nd)**: Redis SET NX — IN_PROGRESS(동시 중복 → 409), JSON(캐시 재생)
3. **DB UNIQUE (3rd)**: `orders.idempotency_key UNIQUE` — Redis 장애·만료 시 이중 INSERT 최후 차단

서버→PG 멱등성은 `orderId`(merchantUid)를 PG 멱등키로 사용해 이중 승인을 방지했습니다. 클라 `Idempotency-Key`와 역할이 다르므로 분리했습니다.

#### 판단 근거

**① 클라이언트 → 서버**

DB 조회를 1순위로 둔 이유는, Redis TTL(24시간)이 만료되거나 Redis 장애가 발생하더라도 이미 완료된 주문을 정상적으로 조회할 수 있기 때문입니다.
IdempotencyStore(Redis)가 2순위로 동시 중복을 빠르게 차단하고, DB UNIQUE 제약이 최종 방어선 역할을 합니다.

**② 서버 → PG사**

`orderId`(merchantUid)를 PG 멱등키로 사용했습니다. PG가 멱등키를 지원하면 동일 키 재전송 시 기존 승인 결과를 반환해 이중 청구를 차단합니다.
지원하지 않으면 approve 전에 `inquire(orderId)`로 기존 승인 여부를 확인합니다.

---

## 4. 결제 확장성

> 요구사항: 신용카드·Y페이·포인트 복합 결제 지원, 새 결제 수단 추가 시 기존 로직 수정 최소화

### 쟁점 9. T1/T2 분리 — 포인트 먼저, PG 나중

#### 상황

복합 결제(포인트 + 카드/Y페이) 시 내부 자원(포인트)과 외부 자원(PG)을 모두 처리해야 합니다.
PG 실패 시 이미 차감한 포인트를 어떻게 복원할지 보상 경로 설계가 필요했습니다.

#### 선택

포인트 차감 + 주문 PENDING을 T1(로컬 트랜잭션)으로 PG 호출 전에 먼저 커밋하고, PG 승인 후 주문 PAID + 결제 기록을 T2로 커밋하도록 구현했습니다.

#### 판단 근거

PG를 먼저 호출하면 그 이후의 흐름에서 실패 시 PG 취소 요청(외부 호출)이 필요하고,
그 취소마저 실패할 수 있어 보상 경로가 복잡해집니다.

포인트를 먼저 차감하면 PG 실패 시 내부 DB 보상만으로 정리할 수 있습니다. 포인트 잔액 부족도 PG 호출 전에 즉시 감지됩니다.
PG 승인 요청이 항상 마지막 단계에서 수행되므로, 이 설계에서는 별도의 PG 취소 흐름이 필요하지 않습니다.

---

### 쟁점 10. PaymentGateway OCP 확장 구조

#### 상황

신용카드, Y페이 외에 향후 새로운 결제 수단이 추가될 수 있습니다.
수단이 추가될 때마다 BookingFacade를 수정하면 기존 결제 흐름에 영향을 줄 수 있다고 봤습니다.

#### 선택

`PaymentGateway` 인터페이스를 두고, 수단별 구현체(`CardGateway`, `YPayGateway`)를 `PaymentGatewayRouter`에 등록하는 구조로 설계했습니다.
새 수단 추가 시 구현체 1개 + Router 등록 1줄만 추가하면 됩니다.

#### 판단 근거

BookingFacade는 `PaymentGatewayRouter`를 통해 수단에 무관하게 동일한 인터페이스로 호출합니다.
새 수단이 추가되더라도 기존 코드를 수정하지 않아 OCP를 만족합니다.
포인트는 외부 PG가 아닌 내부 자원이므로 Router를 거치지 않고 `PointProcessor`가 직접 처리하도록 구현했습니다.

---

## 5. 장애 대응

> 요구사항: Redis 장애 시 fallback 전략, 결제 실패·타임아웃 케이스 대응 로직

### 쟁점 11. Redis 장애 격리 — Fail-closed / Degrade 전략 + 서킷 브레이커

#### 상황

Redis 장애 시 두 가지 문제가 동시에 발생한다고 봤습니다.
첫째, Redis 호출이 타임아웃까지 블록되면서 스레드가 묶여 cascading failure로 이어집니다.
둘째, 원자적 재고 차감 수단이 없는 상태에서 예약을 허용해도 되는지 판단이 필요했습니다.

#### 선택

경로별로 전략을 분리했습니다.
- **POST /booking (`reserve`)**: fail-closed — 503 `BOOKING_UNAVAILABLE`으로 즉시 거부
- **GET /checkout (`isAvailable`)**: degrade — `false` 반환으로 partial 응답 허용
- **`confirm` / `release`**: best-effort — 로그만 기록, 주문 PENDING 유지

Resilience4j `@CircuitBreaker`를 `StockServiceImpl` 메서드에 적용했습니다. 실패율 50% 초과 시 서킷을 열어 Redis 요청 없이 즉시 fallback으로 전환합니다.

| 메서드 | 서킷 오픈 시 동작 | 이유 |
|---|---|---|
| `isAvailable` | `false` 반환 (degrade) | GET 부수효과 없음, partial 응답 허용 |
| `reserve` | 503 `BOOKING_UNAVAILABLE` | 재고 보장 없이 예약 허용 불가 |
| `confirm` | 로그만 기록 | 주문 PENDING 유지, 정산 배치가 사후 처리 |
| `release` | 로그만 기록 | under-sell 허용 범위 (쟁점 3 참조) |

서킷은 30s 후 HALF-OPEN으로 전환해 5회 시험 요청으로 Redis 복구를 확인하도록 했습니다.
Redis가 느리게 응답하는 부분 장애도 감지하도록, 1s 초과 호출을 slow call로 집계하고 slow call 비율이 80% 이상이면 서킷을 오픈합니다.

#### 판단 근거

Redis 없이 예약을 허용하면 재고 정합성을 보장할 수 없어 oversell 위험이 발생합니다.
반면 GET /checkout은 부수효과가 없어 재고 정보만 빠진 partial 응답으로 degrade가 가능합니다.
예약 경로에서는 가용성보다 정합성을 우선하도록 설계했고, 조회 경로는 가용성을 유지하도록 했습니다.

Resilience4j는 Spring Boot 3 공식 지원 라이브러리로 `@CircuitBreaker` 어노테이션 하나로 AOP 기반 적용이 가능합니다.
Hystrix는 deprecated이고, Spring Cloud CircuitBreaker는 추상화 레이어가 추가되어 설정이 복잡해집니다.
`ignore-exceptions`에 `BaseException`을 등록해 SOLD_OUT 등 비즈니스 예외는 실패 카운트에서 제외했습니다.

**설정값 선택 이유**

COUNT_BASED는 트래픽 변동 폭이 크면 윈도우 지속 시간이 불안정해집니다.
평시 50 TPS에서는 size=10 윈도우가 200ms를 커버하지만, 프로모션 피크 1000 TPS에서는 10ms로 줄어들어 Redis 순간 지연 하나로도 서킷이 오픈될 수 있습니다.
그래서 TIME_BASED로 전환해 10초 고정 윈도우 안에서 실패율을 측정하도록 했고, 이렇게 하면 TPS 변화에 무관하게 안정된 신호를 얻을 수 있습니다.

wait-duration-in-open-state를 30s로 설정한 이유는,
Redis 복구나 Sentinel failover 완료까지 통상 10~30s가 걸리는데 10s면 OPEN→HALF_OPEN 반복 진동이 발생해 회복 중인 Redis에 불필요한 부하를 줄 수 있기 때문입니다.

HALF_OPEN 시험 요청을 5회로 늘린 이유는, 피크 TPS 환경에서 3회로는 통계적으로 부족해 오류 판정이 불안정할 수 있기 때문입니다.

---

### 쟁점 12. UNKNOWN 동결 — 타임아웃을 실패로 처리하지 않음

#### 상황

PG 응답 타임아웃이 발생하면 요청이 PG에 실제로 도달했는지 알 수 없습니다.
이를 즉시 실패로 처리하고 포인트를 환불하면, PG가 실제로 승인한 경우 카드는 청구됐는데 포인트도 환불되는 이중 결제 위험이 생길 수 있습니다.

#### 선택

타임아웃 시 주문을 `UNKNOWN` 상태로 동결하도록 했습니다. 포인트·재고를 그대로 유지하고 정산 배치로 사후 확정합니다.

#### 판단 근거

타임아웃은 실패가 아니라 결과 불명 상태라고 판단했습니다. 즉시 환불하면 이중 결제 위험이, 즉시 재시도하면 이중 승인 위험이 발생합니다.
UNKNOWN으로 동결하면 최악의 경우 일시적인 사용자 불편으로 끝나지만, 잘못된 즉시 처리는 금전 피해로 이어집니다.
정산 배치가 `PaymentGateway.inquire()`로 결과를 확정하는 구조는 설계해뒀고, 실제 배치 잡 구현은 PG 연동 생략 범위와 동일하게 미구현으로 남겼습니다.

**PG 응답별 처리 전략**

| PG 응답 | 처리 |
|---|---|
| APPROVED | confirm → PAID |
| REJECTED (4xx, 한도 초과) | 보상 후 FAILED. 재시도 무의미 |
| 5xx (PG 서버 오류) | Gateway 내부에서 2회 재시도 후 UNKNOWN |
| 응답 타임아웃 | UNKNOWN 동결. 재시도 금지 (이중 결제 위험) |

---

## 6. 도메인 & 데이터 설계

### 쟁점 13. 상품·주문 도메인 구조 설계

#### 상황

숙소 상품은 RoomOption(방 종류) 단위로 존재하고, 이벤트 프로모션은 특정 옵션에만 붙는 구조입니다.
주문은 이벤트 예약과 일반 예약 두 경로를 모두 커버해야 하므로, 재고 단위를 어디에 둘지와 Order 구조를 어떻게 잡을지 결정이 필요했습니다.

#### 선택

**상품 구조**

```
Product (숙소)
 └── RoomOption              ← 방 종류
       └── EventOption       ← 이벤트 프로모션 (optional)
             ├── promo_price
             └── promo_stock_total  ← 재고 단위 (Redis 시딩 원천)
```

재고를 EventOption 단위로 분리했습니다. Redis 키도 `stock:event:{eventId}:option:{optionId}` 구조로 옵션별 독립 관리합니다.

**주문 구조**

```
Order                        ← 결제·멱등 헤더 (예약 종류 모름)
 └── OrderLine               ← 투숙 1건 (무엇을 샀는지 아는 유일한 레이어)
       ├── room_option_id    (FK, 필수)
       ├── event_option_id   (FK, nullable — 이벤트 예약이면 채워짐, 일반이면 NULL)
       └── nights
```

| 예약 종류 | event_option_id |
|---|---|
| 이벤트 예약 (현재) | 채워짐 |
| 일반 예약 | NULL |

날짜 타입도 의미 기준으로 분리했습니다.

```
절대 시점 (이벤트 오픈·마감, 결제 시각 등)  → ZonedDateTime
달력 날짜 (체크인·체크아웃 날짜)            → LocalDate + LocalTime
숙소 타임존                               → Product.timezone (IANA)
```

#### 판단 근거

옵션 단위 재고는 같은 방의 이벤트·일반이 재고 규칙이 달라 분리가 불가피합니다(이벤트는 총 10개 제한, 일반은 날짜별 가용 여부).

Order/OrderLine 분리는 결제·환불·멱등 흐름을 Order 레벨에서 통일하고, 예약 종류 분기는 OrderLine 안에서만 발생하도록 하기 위한 선택입니다. Order가 예약 종류를 모르기 때문에 새로운 예약 유형이 추가되더라도 결제 흐름 코드는 수정이 없습니다.

날짜 타입 분리는 ZonedDateTime으로 통일하면 클라이언트 타임존 변환 시 "6월 12일 체크인"이 다른 날짜로 변환되는 문제를 방지하기 위한 것입니다. OTA 업계(Agoda, Expedia 등)가 체크인·아웃 날짜를 `YYYY-MM-DD`로 주고받는 것과 같은 이유입니다.

---

### 쟁점 14. UserPoint 설계 단순화 + JPQL UPDATE 원자 처리

#### 상황

포인트 설계의 정석은 `point_event`(이력 로그) + `point_balance`(잔액) 두 테이블 구조입니다.
이력을 통해 감사, 분쟁 처리, 잔액 재산출이 가능합니다.
다만 만료 처리, 유형별 세분화, 어드민 조정 등 완전한 이력 시스템은 포인트 전용 도메인 수준의 구현이 필요해, 현재 구현 범위를 벗어난다고 봤습니다.

#### 선택

`user_point` 테이블(balance 컬럼) + `point_transaction` 테이블(USE/REFUND 이력)로 구성했습니다. 잔액 차감·환불은 `@Modifying @Query` JPQL UPDATE 단일 문장으로 처리합니다.

```sql
-- 차감 (잔액 부족 시 0 rows 반환)
UPDATE user_point SET balance = balance - :amount WHERE user_id = :userId AND balance >= :amount

-- 환불
UPDATE user_point SET balance = balance + :amount WHERE user_id = :userId
```

#### 판단 근거

단순화한 구조(`user_point` + `point_transaction`)로도 장애 시 잔액 복구가 가능합니다. `point_transaction`의 USE/REFUND 이력을 합산하면 balance를 재산출할 수 있어 최소한의 감사 추적은 유지됩니다. 완전한 이력 시스템은 향후 포인트 도메인이 독립될 때 추가하는 방향으로 남겨뒀습니다.

비관적 락(SELECT FOR UPDATE + UPDATE, 2회 왕복)보다 UPDATE 단일 문장이 더 원자적이라고 판단했습니다.
DB가 `WHERE balance >= :amount` 조건 평가와 차감을 한 번에 처리하므로 별도 락 없이 race condition이 없습니다.
`rows == 0`이면 잔액 부족으로 판단해 `INSUFFICIENT_POINT`를 throw합니다. 단일 행 UPDATE만 수행하므로 데드락 발생 가능성이 매우 낮습니다.

---

### 쟁점 15. 멱등키 중복 시 에러 vs. Replay

#### 상황

동일한 멱등키로 예약 요청이 재전송될 수 있습니다. 이를 `409 DUPLICATE_ORDER` 에러로 거절할지, 이전 성공 결과를 그대로 반환(replay)할지 선택해야 했습니다.

#### 선택

**Replay**를 선택했습니다. 이미 성공한 요청에 대해 200 OK와 기존 결과를 반환합니다.

- **DB 1계층**: `idempotency_key`로 PAID 주문 조회 — Redis TTL 만료 후에도 안전하게 replay
- **Redis 2계층**: 성공 결과를 JSON으로 캐싱(24h TTL) — DB 조회 없이 빠른 replay
- **동시 중복**: 처리 중 상태(IN_PROGRESS)일 때만 `409 DUPLICATE_ENTRY` 반환

#### 판단 근거

에러를 반환하면 클라이언트가 "요청이 실패했는가, 아니면 이미 성공했는가"를 구분할 수 없어 재시도 로직이 복잡해집니다. Replay 방식은 클라이언트가 안전하게 재시도할 수 있도록 보장하는 업계 표준(Stripe 등)입니다. 요구사항 역시 "중복 처리가 되지 않도록"만 명시하므로, 에러 거절보다 replay가 더 나은 UX를 제공하면서 요구사항을 충족합니다.

# DECISIONS.md

설계 과정에서 판단한 주요 기술적 쟁점과 선택의 근거를 정리함.

---

## 쟁점 1. Redis를 재고 Single Source of Truth로 선택

### 상황

분산 환경(앱 서버 2대+)에서 00시 오픈 시 500~1000 TPS 트래픽이 집중됨.
10개 한정 재고를 MySQL에서 SELECT → UPDATE로 처리하면 행 락 경합이 병목이 됨.

### 선택

이벤트 재고의 실시간 카운터를 Redis에 두고, MySQL은 주문·결제 영구 기록만 담당.

### 판단 근거

MySQL 단독 방식은 `SELECT FOR UPDATE`로 행 락을 잡아야 oversell을 막을 수 있음. 동시 요청이 몰리면 락 대기가 직렬화되어 TPS가 급감함. Redis는 싱글 스레드 커맨드 모델로 락 없이 원자 연산이 가능하고, 인메모리라 응답이 빠름. 단점은 장애 시 데이터 유실 위험인데, MySQL orders 테이블로 재구성 가능하도록 설계해 허용 범위로 봄.

---

## 쟁점 2. Lua EVAL 원자 차감으로 분산 락 제거

### 상황

앱 서버 2대가 동시에 같은 재고를 차감할 경우 oversell 위험이 있음. 일반적으로 분산 환경에서는 Redlock 등 분산 락으로 직렬화하는 방식을 사용함.

### 선택

분산 락 없이 Lua 스크립트를 Redis EVAL로 실행. 재고 확인 + 차감을 하나의 스크립트 안에서 원자 처리.

### 판단 근거

Redis EVAL은 Lua 스크립트 전체를 중단 없이 직렬 실행함. 검사(재고 > 0 확인)와 차감(`HINCRBY -1`)이 한 EVAL 안에 묶여 있어 두 서버가 동시에 진입해도 race condition이 발생하지 않음. Redlock 방식은 락 획득 실패 시 재시도 대기가 생기고 구현 복잡도가 높아짐. Lua EVAL로 동일한 안전성을 더 단순하게 달성 가능해 분산 락을 제거함.

---

## 쟁점 3. Under-sell 허용, Oversell 절대 금지

### 상황

서버 장애로 `confirm` / `release`가 호출되지 못하면 inflight key가 TTL 90s 후 만료되면서 차감된 `promo_stock`이 복원되지 않음. 해당 재고 슬롯이 잠기는 under-sell이 발생함.

### 선택

서버 장애로 인한 under-sell은 허용 범위로 봄. Oversell은 어떤 경우에도 허용하지 않음.

### 판단 근거

Oversell은 돈을 받고 자리가 없는 상황으로 불가역적 피해가 발생함. Under-sell은 MySQL `orders` 테이블에 `PENDING` 상태로 남은 주문을 통해 잠긴 재고를 사후 확인·운영 정리가 가능함. 이를 방지하려면 TTL 만료 시 재고를 회수하는 lazy reclaim 로직(ZSET 순회 등)이 필요한데, `reserve.lua`의 복잡도가 크게 높아짐. 10개 한정 상품에서 서버 장애는 극히 드문 케이스이므로 단순성을 택함.

---

## 쟁점 4. 1인 1구매 제한 — 이벤트 단위

### 상황

과제 요건 "모든 사용자에게 동등한 기회 제공"을 구체적인 정책으로 해석해야 함.

### 선택

한 사용자가 동일 이벤트에서 여러 옵션을 중복 구매할 수 없도록 **이벤트 단위** 1인 1구매 제한 적용. Redis `purchased:event:{eventId}` SET에 구매 완료한 userId를 기록하고, `reserve.lua` 진입 시 SISMEMBER로 차단.

### 판단 근거

"동등한 기회"를 "한 사람이 한정 재고를 독점하지 않는 것"으로 해석함. 이벤트 내 옵션이 여러 개일 때 옵션별로 허용하면 한 사용자가 다수의 슬롯을 선점 가능함. 이벤트 단위로 묶으면 1인 최대 1박만 가능하므로 나머지 재고가 다른 사용자에게 돌아감.

---

## 쟁점 5. T1/T2 분리 — 포인트 먼저, PG 나중

### 상황

복합 결제(포인트 + 카드/Y페이) 시 내부 자원(포인트)과 외부 자원(PG)을 모두 처리해야 함. PG 실패 시 이미 차감한 포인트를 어떻게 복원할지 보상 경로 설계가 필요함.

### 선택

포인트 차감 + 주문 PENDING을 T1(로컬 트랜잭션)으로 PG 호출 전에 먼저 커밋. PG 승인 후 주문 PAID + 결제 기록을 T2로 커밋.

### 판단 근거

PG를 먼저 호출하면 실패 시 PG 취소 요청(외부 호출)이 필요하고, 그 취소마저 실패할 수 있어 보상 경로가 복잡해짐. 포인트를 먼저 차감하면 PG 실패 시 내부 DB 보상만으로 정리 가능함. 포인트 잔액 부족도 PG 호출 전에 즉시 감지됨. PG가 항상 마지막이므로 이 설계에서 PG 취소를 호출하는 경로가 구조적으로 없음.

---

## 쟁점 6. UNKNOWN 동결 — 타임아웃을 실패로 처리하지 않음

### 상황

PG 응답 타임아웃 발생 시 요청이 PG에 실제로 도달했는지 알 수 없음. 즉시 실패로 처리하고 포인트를 환불하면, PG가 실제로 승인한 경우 카드는 청구됐는데 포인트도 환불되는 이중 결제 위험이 생김.

### 선택

타임아웃 시 주문을 `UNKNOWN` 상태로 동결. 포인트·재고를 그대로 유지하고 정산 배치로 사후 확정.

### 판단 근거

타임아웃은 실패가 아니라 결과 불명 상태임. 즉시 환불 시 이중 결제 위험이 발생하고, 즉시 재시도 시 이중 승인 위험이 발생함. UNKNOWN으로 동결하면 최악의 경우 사용자 불편으로 끝나지만, 잘못된 즉시 처리는 금전 피해로 이어짐. 정산 배치가 `PaymentGateway.inquire()`로 결과를 확정하는 구조는 설계해뒀고, 실제 배치 잡 구현은 PG 연동 생략 범위와 동일하게 미구현으로 남김.

---

## 쟁점 7. Redis 장애 시 POST /booking fail-closed (503)

### 상황

Redis 연결 실패로 Lua EVAL이 불가한 상황에서 POST /booking 요청이 들어옴. Redis 없이는 원자적 재고 차감을 보장할 수 없음.

### 선택

POST /booking은 503으로 거부. GET /checkout은 재고 가용 여부를 "확인 불가"로 degrade하되 상품 정보는 MySQL에서 정상 응답.

### 판단 근거

Redis 없이 예약을 허용하면 oversell 방지 수단이 없음. 가용성을 위해 예약을 허용하는 것보다 503으로 거부하는 것이 낫다고 판단함. GET /checkout은 부수효과가 없어 재고 정보만 빠진 partial 응답으로 degrade 가능함. 정합성 > 가용성 원칙을 예약 경로에 일관되게 적용함.

실제 운영 환경에서는 RDB 스냅샷 + AOF 옵션으로 Redis 장애 시 데이터 복구가 가능하고, Sentinel 구성으로 장애 발생 시 자동 failover가 이뤄져 503 구간을 최소화할 수 있음. Redis는 단순 명령어 기준 단일 인스턴스에서 약 10만 ops/sec 처리가 가능해 500~1000 TPS 수준에서는 충분한 여유가 있음. 이보다 TPS가 높아지거나 이벤트 유실을 완전히 방지해야 하는 요건이 생기면 Kafka를 도입해 예약 요청을 큐잉하는 구조로 전환 가능함.

---

## 쟁점 8. PaymentGateway OCP 확장 구조

### 상황

신용카드, Y페이 외에 향후 새로운 결제 수단이 추가될 수 있음. 수단이 추가될 때마다 BookingService나 PaymentOrchestrator를 수정하면 기존 결제 흐름에 영향을 줄 수 있음.

### 선택

`PaymentGateway` 인터페이스를 두고, 수단별 구현체(`CardGateway`, `YPayGateway`)를 `PaymentGatewayRouter`에 등록하는 구조로 설계. 새 수단 추가 시 구현체 1개 + Router 등록 1줄만 추가하면 됨.

### 판단 근거

BookingService와 PaymentOrchestrator는 `PaymentGatewayRouter`를 통해 수단에 무관하게 동일한 인터페이스로 호출함. 새 수단이 추가되더라도 기존 코드를 수정하지 않아 OCP를 만족함. 포인트는 외부 PG가 아닌 내부 자원이므로 Router를 거치지 않고 `PointProcessor`가 직접 처리함.

---

## 쟁점 9. ShedLock — 분산 스케줄러 중복 실행 방지

### 상황

00시 이벤트 오픈 직전 Redis에 재고를 시딩해야 함. 앱 서버가 2대 이상이므로 Spring `@Scheduled`만 사용하면 동시에 두 서버가 같은 스케줄을 실행해 재고가 이중 초기화될 위험이 있음.

### 선택

ShedLock을 도입해 Redis에 분산 락을 잡고 단 한 대만 시딩 스케줄을 실행하도록 보장.

### 판단 근거

별도 분산 락을 직접 구현하는 것보다 ShedLock이 Spring `@Scheduled`와 자연스럽게 통합되고 검증된 라이브러리임. `lockAtMostFor`(2분)로 서버 장애 시 락이 자동 해제돼 다음 서버가 이어받을 수 있음. `lockAtLeastFor`(10초)로 빠르게 완료된 경우에도 중복 실행을 방지함. 시딩에는 `HSETNX`를 사용해 서버 재시작이나 락 경쟁 상황에서도 기존 재고를 덮어쓰지 않도록 멱등성을 보장함.

---

## 쟁점 10. 멱등성 처리 — 세 계층 + 서버→PG 분리

### 상황

중복 처리 위험이 두 축에서 발생함. 클라이언트 재전송은 주문·포인트·재고 이중 처리 위험이 있고, 서버가 PG로 보낸 승인 요청 재전송은 이중 승인 위험이 있음.

### 선택

클라→서버 멱등성은 세 계층으로 처리.

1. **DB 조회 (1st)**: `orders.findByIdempotencyKey()` — PAID 즉시 반환, UNKNOWN 차단 후 조회 유도(GET /orders/{id}), PENDING·FAILED는 다음 계층으로
2. **IdempotencyStore (2nd)**: Redis SET NX — IN_PROGRESS(동시 중복 → 409), JSON(캐시 재생)
3. **DB UNIQUE (3rd)**: `orders.idempotency_key UNIQUE` — Redis 장애·만료 시 이중 INSERT 최후 차단

서버→PG 멱등성은 `orderId`(merchantUid)를 PG 멱등키로 사용해 이중 승인 방지. 클라 `Idempotency-Key`와 역할이 다르므로 분리.

### 판단 근거

멱등성 보장이 필요한 경계가 두 곳이므로 분리해서 설계함.

**① 클라이언트 → 서버**

클라이언트 재전송·네트워크 중복이 주문·포인트·재고를 이중 처리하는 것을 막음. DB 조회를 1순위로 두는 이유는 Redis TTL(24h) 만료 후에도 PAID 주문을 정상 반환할 수 있고, Redis 장애 시에도 1계층이 온전히 동작하기 때문임. IdempotencyStore(Redis)가 2순위로 동시 중복을 빠르게 차단하고, DB UNIQUE가 최후 보루로 작동함.

**② 서버 → PG사**

서버가 PG에 승인 요청을 재전송할 때 이중 청구되는 것을 막음. `orderId`(merchantUid)를 PG 멱등키로 사용.

- **PG가 멱등키를 지원하는 경우**: `orderId`를 PG 요청에 멱등키로 함께 전달. PG 내부에서 동일 키 재전송을 기존 승인 결과로 반환해 이중 청구를 차단함.
- **PG가 멱등키를 지원하지 않는 경우**: approve 호출 전에 `inquire(orderId)`로 기존 승인 여부를 먼저 확인. 이미 승인됐으면 approve 없이 기존 결과를 사용함.

클라 `Idempotency-Key`와 PG 멱등키를 분리하는 이유: 클라 키는 중복 요청 식별용, PG 키는 이중 승인 방지용으로 역할이 다름. `orderId`는 DB PK라 서버 재시작 후에도 안정적으로 참조 가능함.

---

## 쟁점 11. OrderLine 설계 — 일반 연박 주문 확장 고려

### 상황

현재 구현은 이벤트 재고 1박 구매만 존재함. 그러나 실제 숙소 예약 서비스에서는 일반 객실 연박 주문도 필요함.

### 선택

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

### 판단 근거

Order를 주문 건으로만 유지하고 "무엇을 샀는지"는 OrderLine이 아는 구조로 분리함. 일반 예약·연박을 수용할 때 Order와 결제 흐름 코드 변경 없이 OrderLine 레벨에서만 처리 가능함. 향후 날짜별 가격·취소·정산이 필요해지면 `OrderLineNight`을 추가하는 방향으로 확장 가능함.

---

## 쟁점 12. Timezone — ZonedDateTime 기본, 달력 날짜는 LocalDate 예외

### 상황

숙소 체크인/아웃 시각은 숙소 현지 벽시계 시각임. 현재 서비스는 한국 단일이나 향후 글로벌 숙소가 추가될 수 있어 타임존을 고정하지 않는 구조로 구현함.

"모든 날짜/시간 필드를 ZonedDateTime으로 통일"할 경우 체크인 날짜에도 타임존 변환이 발생해 클라가 자기 로컬 타임존으로 변환하면 날짜가 달라지는 문제가 생김.

### 선택

```
절대 시점 (이벤트 오픈·마감, 결제 시각 등)  → ZonedDateTime
달력 날짜 (체크인·체크아웃 날짜)            → LocalDate   ("2024-06-12")
숙소 벽시계 시각 (체크인·체크아웃 시각)      → LocalTime   ("15:00:00")
숙소 타임존                               → Product.timezone (IANA)
```

클라이언트 요청·응답 모두 `LocalDate` / `LocalTime`을 ISO 8601 문자열로 직렬화. 타임존 변환 없음.

### 판단 근거

**ZonedDateTime을 쓰면 안 되는 이유 (체크인 날짜)**

"6월 12일 체크인"은 전 세계 어느 타임존에서 예약하더라도 한국 숙소 기준 6월 12일이어야 함. ZonedDateTime으로 내리면 뉴욕 클라이언트가 로컬 타임존으로 변환해 6월 11일로 표시할 수 있어 의도와 어긋남.

**LocalDate가 업계 표준**

Agoda, Expedia, Booking.com 모두 체크인/아웃 날짜를 `YYYY-MM-DD` (LocalDate 상당)으로 주고받음. 절대 시점이 아닌 달력 날짜에 타임존을 붙이지 않는 것이 OTA 업계 공통 관례임.

**숙소 타임존은 예약이 아닌 숙소에 귀속**

체크인 "15:00"은 사용자 위치와 무관하게 숙소 현지 오후 3시를 의미함. 타임존 정보는 `Product.timezone`(IANA, default `Asia/Seoul`)에 저장하고 체크인/아웃 시각 계산 시 이를 기준으로 처리함. 글로벌 숙소 추가 시 필드 값만 교체하면 됨.

---

## 쟁점 13. UserPoint 설계 단순화 + JPQL UPDATE 원자 처리

### 상황

포인트 설계의 정석은 `point_event`(충전·차감 이력 로그) + `point_balance`(현재 잔액) 두 테이블로 분리하는 구조임. 이력을 통해 감사, 분쟁 처리, 잔액 재산출이 가능함.

### 선택

단순화하여 `user_point` 테이블 하나(balance 컬럼)만 운용. 차감·환불 모두 `@Modifying @Query` JPQL UPDATE 한 문장으로 처리.

```
-- 차감 (잔액 부족 시 0 rows 반환)
UPDATE user_point SET balance = balance - :amount, updated_at = NOW()
 WHERE user_id = :userId AND balance >= :amount

-- 환불
UPDATE user_point SET balance = balance + :amount, updated_at = NOW()
 WHERE user_id = :userId
```

### 판단 근거

**단순화 이유**

이력 테이블은 운영·감사 요건이 있을 때 가치가 있음. 현재 과제 범위에서 포인트는 결제 흐름의 내부 자원으로만 사용되므로 balance 단일 컬럼으로 충분함.

**JPQL UPDATE 선택 이유**

비관적 락(SELECT FOR UPDATE + UPDATE, 2회 왕복)보다 UPDATE 단일 문장이 더 원자적임. DB가 `WHERE balance >= :amount` 조건 평가와 차감을 한 번에 처리하므로 별도 락 없이 race condition이 없음. `rows == 0`이면 잔액 부족(또는 레코드 없음)으로 판단해 `INSUFFICIENT_POINT`를 throw함. `@Modifying(clearAutomatically = true)`로 업데이트 후 1차 캐시를 즉시 클리어함.

**데드락 없는 이유**

UPDATE 문은 해당 행에 대해 락을 획득하고 즉시 해제함. 단일 행 UPDATE이므로 여러 행을 순서 없이 잠그는 패턴 자체가 없어 사이클이 구조적으로 불가능함.

---

## 쟁점 14. Resilience4j 서킷 브레이커 — Redis 장애 시 서비스 보호

### 상황

Redis가 느려지거나 응답 불능이 되면 `StockServiceImpl`의 Redis 호출이 타임아웃까지 블록됨. 스레드가 묶이면서 앱 전체가 함께 멈추는 cascading failure 위험이 있음.

### 선택

Resilience4j `@CircuitBreaker`를 `StockServiceImpl` 메서드에 적용. 실패율 50% 초과 시 서킷을 열어 Redis 요청 없이 즉시 fallback으로 전환함.

| 메서드 | 서킷 오픈 시 동작 | 이유 |
|---|---|---|
| `isAvailable` | `false` 반환 (degrade) | GET 부수효과 없음, partial 응답 허용 |
| `reserve` | 503 `BOOKING_UNAVAILABLE` | 재고 보장 없이 예약 허용 불가 |
| `confirm` | 로그만 기록 | 주문 PENDING 유지, 정산 배치가 사후 처리 |
| `release` | 로그만 기록 | under-sell 허용 범위 (쟁점 3 참조) |

서킷은 10s 후 HALF-OPEN으로 전환해 3회 시험 요청으로 Redis 복구를 확인함.

### 판단 근거

**Resilience4j를 선택한 이유**

Spring Boot 3 공식 지원 라이브러리로 `@CircuitBreaker` 어노테이션 하나로 AOP 기반 적용이 가능함. 대안인 Spring Cloud CircuitBreaker는 추상화 레이어가 추가되어 설정이 복잡해짐. Hystrix는 deprecated. Resilience4j는 스프링부트 starter가 있어 auto-configure + YAML 설정만으로 동작해 도입 비용이 낮음.

**`BaseException`을 ignore하는 이유**

SOLD_OUT, ALREADY_PURCHASED 등 비즈니스 예외는 Redis가 정상 응답한 결과임. 이 예외까지 실패로 계산하면 서킷이 불필요하게 열림. `ignore-exceptions`에 `BaseException`을 등록해 인프라 레벨 오류(커넥션 실패, 타임아웃)만 카운트함.

---

## 쟁점 15. 고가용성 — 500~1000 TPS 프로모션 트래픽 대응

### 상황

평시 50 TPS → 00시 프로모션 시작 시 1~5분간 500~1000 TPS 급증. 인프라 증설이 제한적인 상황에서 코드 레벨에서 부하를 흡수해야 함.

### 선택

DB 락 병목 제거(Redis 재고 권위) + Lua EVAL 원자 처리 + 서킷 브레이커 조합으로 TPS 급증을 처리. 별도 Rate Limiter나 큐잉 없이 현 구조로 대응 가능한 것으로 판단함.

### 판단 근거

**DB 락 병목 제거 (핵심)**

MySQL `SELECT FOR UPDATE` 방식은 동시 요청이 행 락을 직렬화해 TPS가 급감함. Redis는 싱글스레드 커맨드 모델로 락 없이 원자 연산을 처리함. Lua EVAL 한 번으로 1인 1구매 확인 + 재고 차감이 완료되므로 DB 왕복 없이 재고 확정이 끝남. Redis 단일 인스턴스 기준 약 10만 ops/sec 처리 가능 → 500~1000 TPS는 충분한 여유.

**서킷 브레이커 — cascading failure 차단**

Redis가 느려지면 서킷이 열려 이후 요청이 즉시 503으로 빠짐. 커넥션 큐 쌓임이 앱 스레드를 잠식하는 연쇄 장애를 방지함.

**fail-closed 503 — 과부하 시 명시적 거부**

Redis 없이 재고 원자성을 보장할 수 없으므로 POST /booking은 503을 반환함. 클라이언트가 재시도를 제어할 수 있어 무한 재시도로 인한 추가 부하를 방지함.

**인프라 확장이 필요한 시점**

현재 구조는 TPS 1,000 수준까지 대응 가능한 것으로 판단함. 그 이상이 되거나 이벤트 유실을 완전히 방지해야 하는 요건이 생기면 Kafka를 도입해 예약 요청을 큐잉하고 소비자가 Redis 차감·DB 기록을 순차 처리하는 구조로 전환 가능함.

---

## 쟁점 16. Docker Compose — 로컬 개발 인프라 구성

### 상황

MySQL + Redis 두 인프라가 필요하나 로컬 설치 없이 코드 수정 없이 실행 가능해야 함.

### 선택

`docker-compose.yml` 하나로 MySQL 8 + Redis 7 컨테이너를 띄움. healthcheck를 설정해 MySQL 준비 완료 전에 앱이 기동되지 않도록 함.

### 판단 근거

Docker Compose는 멀티 컨테이너 로컬 환경을 선언적으로 정의하는 표준 도구임. `healthcheck`로 의존성 순서를 강제해 schema.sql 실행 전 MySQL이 준비 완료됨을 보장함. Redis는 `--appendonly yes`로 AOF를 켜 컨테이너 재시작 시 재고 시딩 전 상태를 유지함. 프로덕션 환경(Sentinel, Cluster)과 연결 설정만 다르고 앱 코드는 동일하게 동작함.

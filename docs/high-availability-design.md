# 고가용성 설계

> **한 줄 요약** — Redis를 재고 권위로 두고 Lua EVAL로 oversell 차단. 서킷 브레이커로 cascading failure 방지, Redis 장애 시 POST /booking은 503, GET은 degrade.

## 목표

- 평시 50 TPS, 프로모션 시작(00시) 500~1000 TPS 처리
- Redis 장애 시 서비스 전체 중단 없이 부분 degrade
- cascading failure 방지 (Redis 장애가 앱 스레드 고갈로 번지지 않도록)

---

## 전략

### DB 락 병목 제거 — Redis 재고 권위

MySQL `SELECT FOR UPDATE`는 동시 요청이 행 락을 직렬화해 TPS가 급감함.
Redis 싱글스레드 커맨드 모델은 락 없이 원자 연산을 처리하며, 단일 인스턴스 기준 약 10만 ops/sec.
500~1000 TPS 트래픽은 Redis 용량의 1% 미만으로 여유가 충분함.

### Lua EVAL 원자 차감 — 분산 락 제거

`reserve.lua` 한 번의 EVAL로 1인 1구매 확인 + 재고 차감이 완료됨.
두 앱 서버가 동시에 진입해도 Redis 내부에서 직렬화되므로 race condition 없음.
Redlock 방식 대비 재시도 대기와 구현 복잡도를 제거함.

### 서킷 브레이커 (Resilience4j) — cascading failure 차단

Redis가 느려지거나 응답 불능이 되면 서킷이 열려 이후 요청이 즉시 fallback으로 빠짐.
커넥션 타임아웃 대기가 스레드를 잠식하는 연쇄 장애를 방지함.

| 메서드 | 서킷 오픈 시 동작 |
|---|---|
| `isAvailable` (GET) | `false` 반환 — 서비스 유지, 재고 힌트만 소실 |
| `reserve` (POST) | 503 `BOOKING_UNAVAILABLE` — fail-closed |
| `confirm` / `release` | ERROR 로그 — under-sell 허용, 정산 배치 처리 |

**설정값**

```yaml
sliding-window-size: 10       # 최근 10건 기준
failure-rate-threshold: 50    # 50% 실패 시 OPEN
wait-duration-in-open-state: 30s
permitted-number-of-calls-in-half-open-state: 5
```

### fail-closed 503 — 재고 정합성 우선

Redis 없이는 원자적 재고 차감을 보장할 수 없으므로 POST /booking은 503을 반환함.
클라이언트가 `Retry-After`를 보고 재시도를 제어할 수 있어 무한 재시도로 인한 추가 부하를 차단함.
GET /checkout은 부수효과가 없으므로 재고 정보만 빠진 partial 응답으로 degrade함.

### 콜드 스타트 방지 — WarmupRunner + 커넥션 풀 튜닝

00시 오픈처럼 기동 직후 트래픽이 급증하는 상황을 대비해 두 가지를 적용했다.

**WarmupRunner**

`ApplicationReadyEvent` 시점에 자기 자신에게 `/api/checkout`으로 100회(50 스레드 병렬)를 전송한다.
서비스 레이어 직접 호출은 JPA·Redis만 초기화하고 Tomcat 스레드 풀은 확장하지 않는다.
실제 HTTP 호출이어야 JVM JIT / HikariCP / Lettuce / Tomcat 스레드 풀이 전체 스택으로 초기화된다.
또한 Caffeine 로컬 캐시(이벤트·옵션·상품 정보 TTL 10s)도 이 시점에 웜업된다.

| 지표 | 개선 전 | 개선 후 |
|------|---------|---------|
| 성공률 | 17% (171/1,000) | **100%** |
| connection reset | 829건 | **0건** |

**Tomcat 스레드 설정 (2 CPU 파드 기준)**

| 항목 | 값 | 이유 |
|------|----|------|
| `threads.max` | 200 | 2 CPU 기준 적정값 — 400은 컨텍스트 스위칭 과부하로 p95 악화 |
| `threads.min-spare` | 100 | 기동 직후 즉시 사용 가능한 스레드 확보 |
| `accept-count` | 300 | TCP 백로그 큐 |

**HikariCP 풀 크기 (MySQL 1 CPU 기준)**

| 항목 | 값 | 이유 |
|------|----|------|
| `maximum-pool-size` | 25 | MySQL 1 CPU 기준 적정 동시 쿼리 수 — 50은 MySQL 과부하로 p95 악화 |
| `minimum-idle` | 10 | 워밍업 후 즉시 사용 가능한 커넥션 확보 |
| `connection-timeout` | 5000ms | 스파이크 시 큐 대기 허용 여유 |

---

### ShedLock — 분산 스케줄러 단일 실행 보장

앱 서버가 2대 이상일 때 `@Scheduled` 단독 사용 시 이중 시딩 위험이 있음.
ShedLock이 Redis에 분산 락을 잡아 단 1대만 00시 재고 시딩을 실행하도록 보장함.
`HSETNX` 사용으로 락 경쟁이나 서버 재시작 상황에서도 기존 재고를 덮어쓰지 않음.

---

## 검증

### 서킷 브레이커 동작 확인

Redis 컨테이너 강제 종료 후 요청 전송.

```
# CLOSED 구간 — 실제 Redis 호출, 타임아웃 후 fallback
WARN [CircuitBreaker] Redis unavailable — isAvailable degraded: cause=Redis command timed out

# OPEN 전환 후 — Redis 호출 없이 즉시 fallback
WARN [CircuitBreaker] Redis unavailable — isAvailable degraded: cause=CircuitBreaker 'redis' is OPEN and does not permit further calls
```

- CLOSED → OPEN: 10건 실패 누적 시점에 자동 전환
- GET /checkout: 서킷 상태와 무관하게 200 응답 유지 (`available: false`로 degrade)
- Redis 재기동 후 30초: HALF-OPEN → CLOSED 복귀, `available: true` 정상화

### WarmupRunner 효과 확인

`ApplicationReadyEvent` 후 자기 자신에게 병렬 HTTP 요청 100회 전송.

```
[Warmup] 시작 — http://localhost:8080/api/checkout?eventId=1&optionId=1 (동시 50개 × 2회)
[Warmup] 완료 — 1243ms, 성공 100/100
```

| 지표 | 워밍업 전 | 워밍업 후 |
|------|---------|---------|
| 성공률 (1,000 VU) | 17% (171건) | **100%** |
| connection reset | 829건 | **0건** |
| p95 (성공 건 기준) | 164ms | — |

서비스 레이어 직접 호출은 Tomcat 스레드 풀을 확장하지 않아 실제 트래픽에서 여전히 connection reset이 발생한다.
실제 HTTP 호출로만 JVM JIT · HikariCP · Tomcat 스레드 풀이 전체 스택 기준으로 초기화된다.

### k6 부하 테스트 결과

시나리오별 상세 결과(조회 폭주 · 구매 폭주 · 멱등성 스트레스 · 재고 초과판매 방지 · E2E 혼합)는
[`docs/performance.md`](performance.md) 참조.

---

## 인프라 확장 기준

현재 구조는 TPS 1,000 수준까지 대응 가능한 것으로 판단됨.

그 이상이 필요하거나 이벤트 유실을 완전히 방지해야 하면 Kafka 도입을 검토함.
예약 요청을 큐잉하고 컨슈머가 Redis 차감·DB 기록을 순차 처리하는 구조로 전환 시
TPS 스파이크를 큐가 흡수하고 처리 속도는 컨슈머 수로 조절 가능함.

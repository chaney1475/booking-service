# 고가용성 설계

## 목표

- 평시 50 TPS, 프로모션 시작(00시) 500~1000 TPS 처리
- Redis 장애 시 서비스 전체 중단 없이 부분 degrade
- cascading failure 방지 (Redis 장애가 앱 스레드 고갈로 번지지 않도록)

---

## 전략 요약

### 1. DB 락 병목 제거 — Redis 재고 권위

MySQL `SELECT FOR UPDATE`는 동시 요청이 행 락을 직렬화해 TPS가 급감한다.
Redis 싱글스레드 커맨드 모델은 락 없이 원자 연산을 처리하며, 단일 인스턴스 기준 약 10만 ops/sec.
500~1000 TPS 트래픽은 Redis 용량의 1% 미만으로 여유가 충분하다.

### 2. Lua EVAL 원자 차감 — 분산 락 제거

`reserve.lua` 한 번의 EVAL로 1인 1구매 확인 + 재고 차감이 완료된다.
두 앱 서버가 동시에 진입해도 Redis 내부에서 직렬화되므로 race condition 없음.
Redlock 방식 대비 재시도 대기와 구현 복잡도를 제거했다.

### 3. 서킷 브레이커 (Resilience4j) — cascading failure 차단

Redis가 느려지거나 응답 불능이 되면 서킷이 열려 이후 요청이 즉시 fallback으로 빠진다.
커넥션 타임아웃 대기가 스레드를 잠식하는 연쇄 장애를 방지한다.

| 메서드 | 서킷 오픈 시 동작 |
|---|---|
| `isAvailable` (GET) | `false` 반환 — 서비스 유지, 재고 힌트만 소실 |
| `reserve` (POST) | 503 `BOOKING_UNAVAILABLE` — fail-closed |
| `confirm` / `release` | ERROR 로그 — under-sell 허용, 정산 배치 처리 |

**설정값**

```yaml
sliding-window-size: 10       # 최근 10건 기준
failure-rate-threshold: 50    # 50% 실패 시 OPEN
wait-duration-in-open-state: 10s
permitted-number-of-calls-in-half-open-state: 3
```

### 4. fail-closed 503 — 재고 정합성 우선

Redis 없이는 원자적 재고 차감을 보장할 수 없으므로 POST /booking은 503을 반환한다.
클라이언트가 `Retry-After`를 보고 재시도를 제어할 수 있어 무한 재시도로 인한 추가 부하를 차단한다.
GET /checkout은 부수효과가 없으므로 재고 정보만 빠진 partial 응답으로 degrade한다.

### 5. ShedLock — 분산 스케줄러 단일 실행 보장

앱 서버가 2대 이상일 때 `@Scheduled` 단독 사용 시 이중 시딩 위험이 있다.
ShedLock이 Redis에 분산 락을 잡아 단 1대만 00시 재고 시딩을 실행하도록 보장한다.
`HSETNX` 사용으로 락 경쟁이나 서버 재시작 상황에서도 기존 재고를 덮어쓰지 않는다.

---

## 검증

### 서킷 브레이커 동작 확인

Redis 컨테이너 강제 종료 후 요청 전송:

```
# CLOSED 구간 — 실제 Redis 호출, 타임아웃 후 fallback
WARN [CircuitBreaker] Redis unavailable — isAvailable degraded: cause=Redis command timed out

# OPEN 전환 후 — Redis 호출 없이 즉시 fallback
WARN [CircuitBreaker] Redis unavailable — isAvailable degraded: cause=CircuitBreaker 'redis' is OPEN and does not permit further calls
```

- CLOSED → OPEN: 10건 실패 누적 시점에 자동 전환
- GET /checkout: 서킷 상태와 무관하게 200 응답 유지 (`available: false`로 degrade)
- Redis 재기동 후 10초: HALF-OPEN → CLOSED 복귀, `available: true` 정상화

---

## 인프라 확장 기준

현재 구조는 TPS 1,000 수준까지 대응 가능한 것으로 판단한다.

그 이상이 필요하거나 이벤트 유실을 완전히 방지해야 하면 Kafka 도입을 검토한다.
예약 요청을 큐잉하고 컨슈머가 Redis 차감·DB 기록을 순차 처리하는 구조로 전환 시
TPS 스파이크를 큐가 흡수하고 처리 속도는 컨슈머 수로 조절 가능하다.

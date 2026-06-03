# 부하 테스트 — 계획·환경·결과

## 전제

| 항목 | 값 |
|------|----|
| 도구 | k6 |
| 대상 API | `GET /api/checkout`, `POST /api/booking` |
| 전체 VU | 1,000 |
| 재고 | 10개 |
| 시드 데이터 | 유저 1,000명 (각 10,000포인트), 이벤트 1개 |

### 테스트 모델 — VU(closed-loop) vs TPS(open-loop)

s1~s4는 **closed-loop(VU 기반) 포화 테스트**다. VU가 응답을 받아야 다음 요청을 보내므로, 1,000 VU가 동시에 줄을 서는 구조가 된다. Little's Law에 따라 `응답시간 ≈ VU 수 ÷ 처리량`이 되어 **레이턴시는 대기 시간이 지배적**이다. 이 시나리오의 핵심 지표는 레이턴시가 아닌 **정합성(PAID ≤ 10, oversell 0, 5xx = 0)** 이다.

s5는 **open-loop(constant-arrival-rate) TPS 검증**이다. 도착률을 checkout 500 req/s + booking 500 req/s = 1,000 TPS로 고정해, 큐가 쌓이지 않고 지속 처리 가능한지 검증한다. 서비스 요건 "500~1,000 TPS"를 검증하는 유일한 시나리오다.

환경 변수로 주입:

```bash
BASE_URL=http://localhost:8080
EVENT_ID=1
OPTION_ID=1
PROMO_PRICE=59000
```

---

## 1. 환경 구성

### macOS 로컬 한계 → Docker 전환

macOS는 `kern.ipc.somaxconn` 기본값이 128이다. 1,000 VU가 동시에 TCP 연결을 시도하면 OS 레벨에서 872건이 즉시 거부(`connection reset by peer`)되어 테스트 결과가 왜곡된다.

서버를 Docker로 올리더라도 k6가 macOS 호스트에서 `localhost:8080`으로 접근하면 포트 포워딩이 macOS 네트워크 스택을 통과하므로 동일한 문제가 남는다.

**해결:** k6와 app을 동일한 Docker bridge 네트워크에 올려 Linux VM 내부에서 직접 통신하도록 구성했다.

```
k6 컨테이너 ──(Docker bridge)──▶ app:8080
                Linux VM 내부 통신 — macOS TCP 스택 무관
```

### Docker Compose 구성

```
mysql ──┐
        ├──▶ app (Spring Boot, local 프로파일)
redis ──┘         │
                  ▼ service_healthy 조건 통과 후
              k6 (grafana/k6, profiles: test)
```

- `app` healthcheck: WarmupRunner 완료 후 `/api/checkout`이 200을 반환하면 healthy
- `k6`는 `profiles: [test]`로 선언해 `docker compose up` 시 기본 제외

**실행 방법:**

> ⚠️ **macOS에서 k6를 직접 실행하면 결과가 무의미하다.**
> macOS `kern.ipc.somaxconn` 기본값(128)으로 인해 1,000 VU 중 872건이 OS 레벨에서 즉시 거부된다.
> 서버만 Docker로 올리고 k6를 호스트에서 실행해도 포트 포워딩이 macOS 네트워크 스택을 통과하므로 동일한 문제가 발생한다.
> **반드시 k6도 Docker 컨테이너로 실행해야 유효한 결과를 얻을 수 있다.**

```bash
# 인프라 + 앱 기동 (WarmupRunner 완료 후 healthy 상태가 되면 준비 완료)
docker compose --profile perf up -d

# (첫 실행 또는 시나리오 간 상태 초기화)
./scripts/k6/reset.sh

# 시나리오 실행 (예시)
docker compose run --rm k6 run /scripts/s2_booking_spike.js
```

---

## 2. 시나리오

### 시나리오 1 — 조회 폭주 (Checkout Spike) `s1_checkout_spike.js`

오픈 직후 1,000 VU가 동시에 `GET /api/checkout`을 때렸을 때 Redis 읽기 + MySQL 조회 경합 측정

```
executor: shared-iterations
vus: 1000 / iterations: 1000
각 VU: GET /api/checkout?eventId=1&optionId=1 (X-User-Id: __VU)
```

| 검증 포인트 | 기준 |
|------------|------|
| p95 응답시간 | < 200ms |
| p99 응답시간 | < 500ms |
| 에러율 | 0% |
| `available` 필드 | 존재 |

---

### 시나리오 2 — 구매 폭주 (Booking Spike) `s2_booking_spike.js`

1,000 VU가 동시에 `POST /api/booking`을 시도할 때 reserve.lua 원자 점유 + DB saga 처리 능력 측정

```
executor: shared-iterations
vus: 1000 / iterations: 1000
각 VU: POST /api/booking (idemKey 고유)
```

| 검증 포인트 | 기준 |
|------------|------|
| PAID 건수 | = 10 (재고 정합성) |
| SOLD_OUT 응답 | 409으로 명시적 거절 |
| p95 응답시간 | < 500ms |

---

### 시나리오 3 — 멱등성 스트레스 (Idempotency Storm) `s3_idempotency.js`

같은 `Idempotency-Key`로 연속 30회 `POST /api/booking`을 보냈을 때 중복 결제 없이 동일 응답 반환하는지 검증

```
VU 1~33:    idemKey = "idem-vu{N}-fixed"   → 30 iterations × 33 VU = 990 중복 요청
VU 34~1000: idemKey = "idem-vu{N}-i{ITER}" → 1회씩 단건 요청
```

| 검증 포인트 | 기준 |
|------------|------|
| 동일 VU 30회 응답 | 같은 `orderId` / `status` |
| 중복 결제 건수 | = 0 |
| IN_PROGRESS 중 동시 요청 | 409 `DUPLICATE_ENTRY` 후 재요청 시 동일 응답 |

---

### 시나리오 4 — 재고 초과판매 방지 (Stock Fence) `s4_stock_fence.js`

1,000 VU 동시 구매 시도에서 정확히 10개만 PAID 되는지 직접 검증

```
executor: shared-iterations
vus: 1000 / iterations: 1000
모든 VU 동시 출발 → POST /api/booking
```

| 검증 포인트 | 기준 |
|------------|------|
| `status: "PAID"` 응답 수 | = 10 |
| DB `orders WHERE status='PAID'` | = 10 |
| Redis `sold` 카운터 | = 10 |
| 나머지 990건 | 409 `SOLD_OUT` 또는 `ALREADY_PURCHASED` |

---

### 시나리오 5 — E2E 혼합 부하 (Mixed Load) `s5_e2e_mixed.js`

조회 트래픽과 구매 트래픽이 동시에 1,000 TPS를 구성할 때 각 흐름의 응답시간 교차 영향 측정

```
flowA (조회만):    VU 1~500  → GET /api/checkout
flowB (조회+구매): VU 501~1000 → GET /api/checkout → POST /api/booking
duration: 30s / target TPS: 1000
```

| 검증 포인트 | 기준 |
|------------|------|
| 전체 TPS | 1,000 유지 |
| p95 응답시간 (전체) | < 300ms |
| 조회 전용 VU 응답시간 | 구매 VU에 의해 열화 없음 |
| 5xx 에러율 | < 1% |

---

## 3. 개선 이력

### Baseline — 개선 없음

**문제:** 서버가 막 기동된 상태에서 1,000 VU가 동시에 요청을 보내면 대부분 실패한다.

**원인:**
- JVM JIT 미컴파일 — 첫 요청은 인터프리터로 실행
- HikariCP 커넥션 미생성 — 풀이 cold 상태
- Tomcat 스레드 풀 미확장 — 기본 max-threads 200, 스레드 미생성
- Redis Lettuce 연결 미수립

**결과 (GET /api/checkout, somaxconn=1024):**

| 지표 | 값 |
|------|----|
| 성공률 | 17% (171/1,000) |
| connection reset | 829건 |
| p95 (성공 건 기준) | 164ms |

---

### 1차 개선 — WarmupRunner + Tomcat 설정

**변경 1: WarmupRunner 추가** (`common/config/WarmupRunner.java`)

`ApplicationReadyEvent` 시점에 자기 자신에게 HTTP 요청 50개 동시 × 3라운드 = 150회를 보낸다. 서비스 레이어 직접 호출이 아닌 실제 HTTP 호출로 JVM JIT / HikariCP / Lettuce / Tomcat 스레드 풀을 전체 스택 기준으로 워밍한다.

> 서비스 레이어 직접 호출은 JPA·Redis는 초기화하지만 Tomcat 스레드 풀은 건드리지 않아 첫 실제 트래픽에서 여전히 connection reset이 발생한다.

**변경 2: Tomcat 설정 조정** (`application.yml`)

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| `threads.max` | 200 | 400 |
| `threads.min-spare` | 25 | 200 |
| `accept-count` | 100 | 500 |

`min-spare: 200`으로 기동 직후 200개 스레드를 미리 확보해 초기 트래픽 수용 능력을 높였다.

**결과 (GET /api/checkout):**

| 지표 | Baseline | 1차 개선 |
|------|----------|---------|
| 성공률 | 17% | **100%** |
| connection reset | 829건 | **0건** |
| p95 | 164ms (성공분) | 931ms (전체 1,000건) |

connection reset이 제거되면서 새 병목이 드러났다. HikariCP 커넥션 30개에 1,000 VU가 몰려 DB 커넥션 대기가 p95를 끌어올렸다.

---

### 2차 개선 — HikariCP 풀 크기 조정

**변경** (`application.yml`)

| 항목 | 변경 전 | 변경 후 | 근거 |
|------|---------|---------|------|
| `maximum-pool-size` | 30 | 50 | DB 커넥션 대기 감소 |
| `minimum-idle` | 10 | 20 | 워밍업 후 즉시 사용 가능 커넥션 확보 |

> 30 → 100으로 올렸을 때 p95가 오히려 악화됐다. 단일 MySQL 인스턴스에 100개 동시 쿼리가 몰리면 MySQL 자체가 병목이 된다. 50이 로컬 단일 MySQL 기준 스위트스팟이었다.

---

### 3차 개선 — 2 CPU 파드 기준으로 Tomcat·HikariCP 재조정

**배경**: 2차까지의 설정은 단일 프로세스 기준 최대치였다. Docker 컨테이너에 CPU 2 / MySQL CPU 1 제한이 적용되면서 Tomcat 400 스레드는 컨텍스트 스위칭 과부하, HikariCP 50 커넥션은 MySQL 과부하를 유발하는 것이 확인됐다.

**변경** (`application.yml`)

| 항목 | 변경 전 | 변경 후 | 근거 |
|------|---------|---------|------|
| `threads.max` | 400 | 200 | 2 CPU 파드 기준 — 400은 컨텍스트 스위칭으로 p95 악화 |
| `threads.min-spare` | 200 | 100 | 기동 직후 즉시 사용 가능 스레드 확보 |
| `accept-count` | 500 | 300 | TCP 백로그 큐 적정화 |
| `maximum-pool-size` | 50 | 25 | MySQL 1 CPU 기준 — 50은 MySQL 과부하로 p95 악화 |
| `minimum-idle` | 20 | 10 | 커넥션 유지 최소화 |
| `connection-timeout` | 3000ms | 5000ms | 스파이크 시 큐 대기 허용 여유 확대 |

---

### N+1 내결함성 관점

단일 서버가 1,000 TPS 전체를 처리할 수 있도록 설정한 이유:

```
정상: 서버A(500 TPS) + 서버B(500 TPS) = 1,000 TPS
장애: 서버A 다운 → 서버B 혼자 1,000 TPS 전부 처리
```

단일 서버가 피크 TPS를 감당하지 못하면 1대 장애 시 연쇄 장애로 이어진다.

---

## 4. 최종 결과

### 환경 (MSA 단일 파드 기준)

| 컴포넌트 | CPU | Memory | 비고 |
|---|---|---|---|
| app | 2 CPU | 2G (-Xms1g -Xmx1536m) | 분산 2대 구성 기준 노드당 할당 |
| mysql | 1 CPU | 512M | 단일 인스턴스 |
| redis | 0.5 CPU | 256M | |
| Tomcat | max 200 / min-spare 100 | | perf 프로파일 |
| HikariCP | max 25 / timeout 5s | | perf 프로파일 |

> **측정 조건**: WarmupRunner 완료(Caffeine 캐시 웜업 포함) 후 k6 실행.

| 시나리오 | 모델 | conn reset | 5xx | 핵심 수치 | threshold |
|----------|------|-----------|-----|-----------|-----------|
| s1 조회 폭주 | closed-loop | 0건 | **0건** | p95=3.1s, p99=3.2s, **errors=0** | errors=0 ✓ |
| s2 구매 폭주 | closed-loop | 0건 | **0건** | **PAID=10**, p95=1.7s, p99=1.7s | ≤10 ✓ |
| s3 멱등성 스트레스 | closed-loop | 0건 | **0건** | **mismatches=0**, PAID=10, p99=1.5s | =0 ✓, p99<1.5s ✓ |
| s4 재고 초과판매 방지 | closed-loop | 0건 | **0건** | **PAID=10**, p95=750ms, p99=760ms | ≤10 ✓, p99<1s ✓ |
| s5 E2E 혼합 1,000 TPS | **open-loop** | 0건 | **0건** | **PAID=10**, booking p99=30ms, checkout p99=3ms | 전항목 ✓ |

### 주요 포인트

- **재고 정합성**: 전 시나리오 oversell 0건, 5xx 0건
- **s3**: `idempotent_mismatches=0` — 33명이 같은 idemKey로 30회씩 요청해도 orderId 전부 일치. 중복 결제 없음
- **s4**: `PAID=10` — reserve.lua가 sold 확정 후 purchased SET을 업데이트해 1인 1구매 보장. mock PG 실패분은 재고 복원
- **s5**: `constant-arrival-rate` 1,000 TPS 30초 유지, 에러 0건. checkout/booking이 상호 영향 없이 독립 처리

### s1 조회 레이턴시가 s2 구매보다 느린 이유

closed-loop 포화 테스트에서 s1 p99≈3s, s2 p99≈1.7s로 읽기 경로가 더 느리다. 원인은 두 가지다.

1. **포인트 조회가 DB-bound**: 이벤트/옵션/상품 정보는 Caffeine 캐시(TTL 10s)로 처리되지만, 유저별 포인트 잔액은 캐싱 불가해 매 요청 MySQL을 찌른다. 1,000 VU × DB 쿼리 = Hikari 25 커넥션 경합.
2. **s4가 빠른 이유**: 재고 소진 후엔 reserve.lua에서 Redis 선에서 즉시 거절 → DB/결제 미진입 → 빠른 응답.

이 차이는 자원 한도(2 CPU + MySQL 1 CPU + 커넥션 25)에서 1,000 VU를 동시에 받을 때 나타나는 물리적 결과다. 개선 방향: checkout 포인트 조회를 분리하거나 `user_point`를 Redis에 미러링 (차감 권위는 DB 유지).

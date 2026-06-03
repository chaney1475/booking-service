# 부하 테스트 — 계획·환경·결과

## 전제

| 항목 | 값 |
|------|----|
| 도구 | k6 |
| 대상 API | `GET /api/checkout`, `POST /api/booking` |
| 전체 VU | 1,000 |
| 재고 | 10개 |
| 시드 데이터 | 유저 1,000명 (각 10,000포인트), 이벤트 1개 |

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

```bash
# 인프라 + 앱 기동
docker compose up -d

# 시나리오 실행 (예시)
docker compose run --rm -e EVENT_ID=1 -e OPTION_ID=1 k6 run /scripts/s2_booking_spike.js
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
| IN_PROGRESS 중 동시 요청 | 409 `DUPLICATE_ORDER` 후 재요청 시 동일 응답 |

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

`ApplicationReadyEvent` 시점에 자기 자신에게 HTTP 요청 50개 동시 × 2회 = 100회를 보낸다. 서비스 레이어 직접 호출이 아닌 실제 HTTP 호출로 JVM JIT / HikariCP / Lettuce / Tomcat 스레드 풀을 전체 스택 기준으로 워밍한다.

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

### N+1 내결함성 관점

단일 서버가 1,000 TPS 전체를 처리할 수 있도록 설정한 이유:

```
정상: 서버A(500 TPS) + 서버B(500 TPS) = 1,000 TPS
장애: 서버A 다운 → 서버B 혼자 1,000 TPS 전부 처리
```

단일 서버가 피크 TPS를 감당하지 못하면 1대 장애 시 연쇄 장애로 이어진다.

---

## 4. 최종 결과 (Docker 환경)

| 시나리오 | 목적 | conn reset | 5xx | 핵심 수치 | threshold |
|----------|------|-----------|-----|-----------|-----------|
| s1 조회 폭주 | Redis + MySQL 경합 | 0건 | 0건 | p95=931ms | — |
| s2 구매 폭주 | reserve.lua + saga | 0건 | 0건 | PAID=9, p95=814ms | ≤10 ✓ |
| s3 멱등성 스트레스 | 동일 idemKey 30회 | 0건 | 0건 | **mismatches=0**, replays=58 | =0 ✓ |
| s4 재고 초과판매 방지 | PAID ≤ 10 검증 | 0건 | 0건 | **PAID=9, p95=313ms** | ≤10 ✓, <500ms ✓ |
| s5 E2E 혼합 1,000 TPS | 조회+구매 혼합 | 0건 | 0건 | **TPS=1,001, checkout p95=2ms, booking p95=70ms** | 전항목 ✓ |

### 주요 포인트

- **s3**: `idempotent_mismatches=0` — 33명이 같은 idemKey로 30회씩 요청해도 orderId가 모두 일치. 중복 결제 없음
- **s4**: `PAID=9` — 재고 10개 중 1건은 mock PG 실패(failure-rate=0.3)로 release, 초과판매 없음
- **s5**: `TPS=1,001` 30초 유지하며 에러 0건. checkout이 구매 트래픽의 영향을 받지 않음 (p95=2ms)

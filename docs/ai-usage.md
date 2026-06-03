# AI 활용 기록

## 사용 도구

| 도구 | 용도 |
|---|---|
| Claude (claude.ai) | 설계 토론, 기술 검증, 문서 초안 |
| Claude Code | 에이전트 기반 구현·검증·테스트·문서화 자동화 |
| Deep Research | 실무 패턴 및 업계 표준 조사 |

---

## 작업 방식: 설계 → 구현

모든 기능은 **설계 문서를 먼저 작성하고, 그 문서를 에이전트의 제약 조건으로 주입해 구현**하는 순서로 진행했다.
코드를 먼저 쓰고 문서를 나중에 정리하는 방식이 아니라, 문서가 구현의 입력이 되는 구조다.

### 1단계 — 설계 문서 작성

구현 전 아래 설계 문서를 먼저 작성했다. 각 문서는 도메인 설계 결정과 제약을 명문화한 것으로,
이후 에이전트가 구현 시 반드시 읽어야 하는 입력 문서로 활용됐다.

| 문서 | 내용 |
|---|---|
| `docs/CONVENTION.md` | 레이어 구조, 네이밍, 타입 규칙 등 전체 코딩 컨벤션 |
| `docs/domain-design.md` | Product / RoomOption / EventOption / Order / OrderLine 도메인 모델 |
| `docs/stock-design.md` | Redis 재고 키 구조, Lua 스크립트 동작, 상태 전이 |
| `docs/payment-design.md` | T1/T2 트랜잭션 분리, PaymentGateway OCP 구조, 복합 결제 조합 |
| `docs/idempotency-design.md` | 3계층 멱등성 처리 흐름 (DB → Redis → DB UNIQUE) |
| `docs/high-availability-design.md` | 서킷 브레이커 설정, WarmupRunner, Sentinel failover |
| `docs/BOOKING_FLOW_CASES.md` | 결제 성공·실패·타임아웃 등 전체 케이스 매트릭스 |
| `docs/booking-state-flow.md` | 주문 상태 전이 (PENDING → PAID / FAILED / UNKNOWN) |

설계 문서 초안은 `docs-writer` 에이전트에 위임했고, 내용을 검토·수정해 확정했다.

각 설계 결정의 근거는 Deep Research로 업계 표준을 먼저 확인한 뒤 문서에 반영했다.

| 결정 | 조사 내용 | 채택 근거 |
|---|---|---|
| 멱등키 중복 시 replay | Stripe, Adyen 공개 API 문서 | 에러 반환보다 replay가 업계 표준. 클라이언트가 재시도 판단을 단순화할 수 있음 |
| 체크인 날짜 `LocalDate` 분리 | Agoda·Expedia API 스펙 | OTA 업계 표준이 `YYYY-MM-DD`. ZonedDateTime 통일 시 타임존 변환으로 날짜가 바뀌는 문제 방지 |
| Lua EVAL vs 분산 락 | Redlock 구현 사례, Redis EVAL 원자성 보장 메커니즘 | 단일 키셋 원자 연산은 Lua EVAL만으로 oversell 방지 가능. Redlock 대비 락 경합 대기 없음 |

### 2단계 — 에이전트 파이프라인으로 구현

`/feature` 슬래시 커맨드로 구현·검증·테스트를 전문 에이전트에 위임했다.

```
/feature [기능 설명] [-r] [-t] [-a]
         │
         ├─ implement 에이전트
         │    CONVENTION.md + 해당 도메인 설계 문서를 먼저 읽고 구현
         │    기존 구현체가 있으면 패턴 일관성을 위해 먼저 읽음
         │
         ├─ review 에이전트  (-r 또는 -a 플래그)
         │    CONVENTION.md 기준으로 독립 검증 → PASS / FAIL + file:line 피드백
         │    FAIL 시 implement에 재작업 요청 (최대 2회 루프)
         │
         └─ test-writer 에이전트  (-t 또는 -a 플래그)
              CONVENTION.md + 구현 파일 읽고 단위·통합·시나리오 테스트 생성
```

**핵심 설계 의도:** 에이전트에 자유를 준 것이 아니라, 1단계에서 확정한 설계 문서를 제약 조건으로 주입했다.
`implement`는 항상 설계 문서를 먼저 읽고 구현하고, `review`는 동일 문서 기준으로 독립 검증한다.
이를 통해 레이어 흐름 위반, 타입 규칙 위반(`LocalDateTime`, `double` 금액 등)을 자동으로 걸러냈다.

문서 갱신이 필요한 경우 `docs-writer` 에이전트에 위임했다.


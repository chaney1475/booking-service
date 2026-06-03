# KNOWN ISSUES

작업 중인 미완료 항목 및 개선 필요 사항을 정리합니다.

---

## [긴급] 1. `docs/high-availability-design.md` 설정값이 실제 코드와 불일치

**위치:** `docs/high-availability-design.md:39-44`

```yaml
# high-availability-design.md (구버전 — 수정 필요)
wait-duration-in-open-state: 10s
permitted-number-of-calls-in-half-open-state: 3

# application.yml + DECISIONS.md (실제 적용값)
wait-duration-in-open-state: 30s
permitted-number-of-calls-in-half-open-state: 5
```

`DECISIONS.md 쟁점 12`에서 30s / 5회를 선택한 근거를 상세히 서술했는데, `high-availability-design.md`가 구버전 값으로 남아 있어 문서 간 모순 발생.  
→ `high-availability-design.md`의 설정값 블록을 실제값으로 갱신 필요.

---

## [긴급] 2. DECISIONS.md 쟁점 번호 불연속 (7, 13 없음)

쟁점 6 → 8, 쟁점 12 → 14로 건너뜀. 작성 중 삭제된 쟁점의 번호가 정리되지 않은 상태.

→ 번호를 연속으로 재정리하거나, 삭제 이유가 있다면 해당 자리에 간단히 명시 필요.

---

## [권장] 3. 고가용성 절 — TPS 급증 대응 논리 보강 필요

**위치:** `DECISIONS.md` 고가용성 섹션, `docs/high-availability-design.md`

현재 설명이 "Redis는 10만 ops/sec이므로 500~1000 TPS는 여유"에 집중되어 있음.  
서킷 브레이커는 **Redis 장애 대응**이지 **TPS 급증 대응**이 아님.

보강이 필요한 관점:
- 앱 서버 스레드 풀 (기본 200 threads) + HikariCP 30 커넥션 설정 근거
- DB 커넥션 병목 여부 (500~1000 TPS 시 동시 결제 요청 → `orders` INSERT 집중)
- 현재 구조로 TPS 상한이 어디인지, 그 이상에서의 대응 방향 (Kafka 큐잉 등)

---

## [권장] 4. 공정성 해석 — 선착순 자체의 공정성 한계 미서술

**위치:** `DECISIONS.md 쟁점 4`

"동등한 기회"를 1인 1구매로 해석한 근거는 있으나, 선착순 방식 자체에 대한 고민이 없음.

추가 서술이 필요한 내용:
- 봇/매크로 사용자 vs 일반 사용자 간 불평등 인식 여부
- 네트워크 지연이 적은 사용자가 구조적으로 유리한 것에 대한 입장
- "추첨 방식이 아닌 선착순을 유지한 이유" (e.g. UX 명확성, 구현 복잡도)

---

## [긴급] 5. `EventQueryServiceImpl` — 이벤트 OPEN 상태 검증 누락

**위치:** `src/main/java/com/example/booking/event/service/EventQueryServiceImpl.java:22`

`findOptionWithProduct()`가 이벤트 존재 여부만 확인하고 `EventStatus.OPEN`인지 체크하지 않는다.
`SCHEDULED`(오픈 전) 또는 `CLOSED`(종료된) 이벤트도 예약이 진행될 수 있다.
`ErrorCode.EVENT_NOT_OPEN`이 정의되어 있지만 어디서도 사용되지 않는 dead code 상태.

```java
// 현재: 존재 여부만 체크 — status 무관
if (!eventRepository.existsById(eventId)) {
    throw new BaseException(ErrorCode.EVENT_NOT_FOUND);
}

// 수정 방향: findById로 로드 후 status 검증 추가
Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new BaseException(ErrorCode.EVENT_NOT_FOUND));
if (event.getStatus() != EventStatus.OPEN) {
    throw new BaseException(ErrorCode.EVENT_NOT_OPEN);
}
```

---

## [긴급] 6. `BookingFacade` — `confirm()` 후 `markPaid()` 실패 시 사용자 stuck

**위치:** `src/main/java/com/example/booking/booking/BookingFacade.java:83~99`

`stockService.confirm()`이 성공하면 Redis purchased SET에 userId가 추가되고 inflight 키가 삭제된다.
이후 `orderService.markPaid()`가 DB 예외로 실패하면 catch 블록이 `idempotencyStore.release()`를 호출한다.

재시도 시 흐름:
1. `findByIdempotencyKey()` → PENDING 주문 발견 → 2계층으로 fall-through
2. `tryAcquire()` → null (키가 삭제됨) → 진행
3. `reserve.lua` → **`ALREADY_PURCHASED`** (confirm이 purchased SET에 이미 추가)

결과적으로 사용자가 결제도 못하고 재예약도 불가능한 dead-lock 상태에 빠진다.

```java
// 수정 방향: confirm() 성공 이후 발생한 예외는 idem release 하지 않음
stockService.confirm(...);
try {
    orderService.markPaid(...);
    ...
} catch (Exception e) {
    // confirm 이후 markPaid 실패 — 배치 정산이 PENDING 주문 처리
    log.error("[BookingFacade] markPaid 실패 — 배치 정산 대상. orderId={}", ctx.order().getId(), e);
    throw e;
}
```

---

## [긴급] 7. `BookingFacade` — PENDING 주문 재시도 시 UNIQUE constraint violation

**위치:** `src/main/java/com/example/booking/booking/BookingFacade.java:95~99`,
`src/main/java/com/example/booking/order/service/OrderServiceImpl.java:44`

`createPending()` 성공 후 `deduct()` 실패(포인트 부족 등) 시 catch 블록이 idem key를 삭제한다.
같은 `Idempotency-Key`로 재시도하면:
1. `findByIdempotencyKey()` → PENDING 주문 발견 → 2계층으로 fall-through
2. `reserve()` → OK
3. `orderService.createPending()` → 동일 `idempotency_key`로 INSERT → **UNIQUE constraint violation → 500**

주석에 "재시도 허용"이라고 명시되어 있지만, 실제로는 500이 반환된다.

```java
// 수정 방향: catch 블록에서 idem 해제 전 잔류 PENDING 주문을 FAILED 처리
} catch (Exception e) {
    orderService.findByIdempotencyKey(command.idempotencyKey())
            .filter(o -> o.getStatus() == OrderStatus.PENDING)
            .ifPresent(o -> orderService.markFailed(o.getId(), e.getMessage()));
    stockService.release(command.eventId(), command.optionId(), command.userId());
    idempotencyStore.release(command.idempotencyKey());
    throw e;
}
```

---

## [권장] 8. `BookingController` — `Idempotency-Key` 헤더 길이 미검증

**위치:** `src/main/java/com/example/booking/controller/booking/BookingController.java:39`

`@RequestHeader("Idempotency-Key")`에 길이 제한이 없다.
DB 컬럼이 `VARCHAR(80)`이므로 80자 초과 키가 들어오면 `createPending()`에서 DB 예외가 발생한다.

```java
// 수정 방향
@RequestHeader("Idempotency-Key") @Size(max = 80) String idempotencyKey
// + 컨트롤러에 @Validated 추가
```

---

## [선택] 5. 핵심 로직 단위 테스트 없음

현재 테스트: `BookingServiceApplicationTests` (컨텍스트 로드만 확인)

과제 필수 요건은 아니나, 다음 로직은 테스트로 동작을 증명하면 신뢰도 향상:
- `reserve.lua` — 재고 0 시 SOLD_OUT, 1인 1구매 차단, 동시 2요청 시 1개만 성공
- 멱등성 3계층 — IN_PROGRESS 충돌(409), PAID replay, DB UNIQUE 최후 차단
- 결제 실패 시 포인트 환불 + 재고 release 흐름

---
name: review
description: Code review agent for booking-service. Validates implementation against CONVENTION.md. Returns PASS or FAIL with specific file:line findings. Use after implement agent finishes.
---

검증 에이전트. 구현된 코드를 `docs/CONVENTION.md` 기준으로 검토한다.

## 사전 작업
`docs/CONVENTION.md`를 읽는다. 이후 검토 대상 파일들을 모두 읽는다.

## 검토 대상
$ARGUMENTS

## 검토 항목

**레이어 흐름**
- Controller → `Request.toCommand()` → Service → Dto → `Response.from(dto)` → `ApiResponse.ok()` 흐름이 올바른가
- Entity가 Controller에 직접 노출되지 않는가
- Repository가 Dto를 반환하지 않는가

**클래스 설계**
- Request / Response record 컴포넌트에 Bean Validation 어노테이션 부착 여부
- Entity에 `record` / `@Data` / `@Setter` 사용 여부 (사용하면 FAIL)
- 기본 생성자가 `protected`인지

**타입 규칙**
- 금액 필드가 `long`인지 (`double` / `float` 사용 시 FAIL)
- 날짜/시간 필드가 `ZonedDateTime`인지 (`LocalDateTime` 사용 시 FAIL)
- `null` 단정 미사용, `orElseThrow(XxxException::new)` 사용 여부

**재고 처리**
- 재고 변경이 `StockService`를 통해서만 이루어지는지
- 실패 시 롤백 처리 여부

**예외 처리**
- 존재하지 않는 엔티티 접근 시 커스텀 `BaseException` 하위 예외 사용 여부
- 다른 사용자 리소스에 접근 가능한 보안 구멍 여부

**네이밍**
- 클래스명이 CONVENTION.md의 패턴을 따르는지
- 단순 조회에 Action 접두사(`Get`, `Find`) 사용 시 FAIL

## 출력
**PASS** 또는 **FAIL**
FAIL이면 `파일경로:라인번호`와 수정 방법을 목록으로 출력한다.

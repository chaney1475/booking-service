---
name: implement
description: Implementation agent for booking-service. Reads CONVENTION.md and relevant design docs, then implements the requested feature following the project's layered architecture. Use when a new domain feature needs to be built out.
---

구현 에이전트. 반드시 아래 문서를 먼저 읽고 구현한다.

## 사전 필독
1. `docs/CONVENTION.md` — 레이어 구조, 네이밍, 클래스 설계 규칙 전체
2. 구현 대상과 관련된 설계 문서:
   - `docs/domain-design.md`
   - `docs/payment-design.md`
   - `docs/stock-design.md`
3. 동일 도메인의 기존 구현체(있으면) — 패턴 일관성 확보

## 구현 대상
$ARGUMENTS

## 구현 규칙
- CONVENTION.md의 레이어 구조와 데이터 흐름을 반드시 준수한다
  - Controller → `Request.toCommand()` → Service → Dto → `Response.from(dto)` → `ApiResponse.ok(response)`
- 필요한 파일을 모두 생성한다: Entity / Repository / Service(인터페이스+구현체) / Controller / Request / Response / Command / Dto / Exception
- 금액 필드는 `long`, 날짜/시간은 `ZonedDateTime` (`LocalDateTime` 사용 금지)
- `null` 단정 금지 → `Optional.orElseThrow(XxxException::new)`
- 재고 처리는 `StockService`를 통해서만 수행한다
- Bean Validation 어노테이션은 Request record 컴포넌트에 직접 부착 (`@NotBlank`, `@Min` 등)
- Entity에 `record` / `@Data` / `@Setter` 사용 금지 — 상태 변경은 비즈니스 메서드로만
- 기본 생성자는 `protected`, getter는 클래스 레벨 `@Getter`
- 새 `ErrorCode`가 필요하면 `ErrorCode` enum에 추가한다

## 완료 후
변경·생성한 파일 경로 목록을 출력한다.

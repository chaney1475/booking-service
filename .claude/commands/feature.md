오케스트레이터. $ARGUMENTS에서 플래그를 파싱해 실행 범위를 결정한다.

## 플래그
- 플래그 없음: 구현만
- `-t`: 구현 + 테스트
- `-r`: 구현 + 검증
- `-a`: 전체 (구현 + 검증 + 테스트)

## 단계별 실행

**구현** (항상 실행)
`implement` 에이전트를 spawn한다.
전달: 기능 설명 + `docs/CONVENTION.md` 전체 + 관련 설계 문서(`docs/domain-design.md`, `docs/payment-design.md`, `docs/stock-design.md`) 중 해당 도메인 섹션
수신: 변경·생성된 파일 경로 목록

**검증** (`-r` 또는 `-a` 일 때만)
`review` 에이전트를 spawn한다.
전달: 구현 단계에서 받은 파일 경로와 실제 파일 내용
수신: PASS/FAIL. FAIL이면 수정 사항을 `implement` 에이전트에 전달해 재작업 (최대 2회)

**테스트** (`-t` 또는 `-a` 일 때만)
`test-writer` 에이전트를 spawn한다.
전달: 구현 파일 목록과 내용, 검증 결과 요약 (검증을 실행했다면)

## 완료 후
실행한 단계와 결과를 간단히 요약 출력한다.

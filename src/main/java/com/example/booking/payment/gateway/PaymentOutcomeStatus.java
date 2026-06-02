package com.example.booking.payment.gateway;

/** PG 응답의 세 가지 상태. 2-state(승인/거절)로는 타임아웃을 표현할 수 없어 UNKNOWN을 추가한다. */
public enum PaymentOutcomeStatus {
    /** PG 승인 확정 */
    APPROVED,
    /** 사용자 오류(한도 초과 등) — 재시도 무의미 */
    REJECTED,
    /** 타임아웃 또는 PG 서버 오류 후 포기 — 동결 후 inquire로 확정 */
    UNKNOWN
}

package com.example.booking.order.entity;

public enum OrderStatus {
    /** T1 커밋 완료 — 포인트 차감됨, PG 승인 대기 중 */
    PENDING,
    /** T2 커밋 완료 — PG 승인 + 결제 기록까지 정상 처리됨 */
    PAID,
    /** PG 실패 확정 — 포인트 환불 및 재고 반납 완료 */
    FAILED,
    /** PG 타임아웃 — 결과 불명 동결, 정산 배치로 사후 확정 (쟁점 6) */
    UNKNOWN,
    CANCELLED
}

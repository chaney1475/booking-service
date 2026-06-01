package com.example.booking.payment.entity;

public enum PaymentStatus {
    /** PG 승인 요청 전 초기 상태 */
    PENDING,
    /** PG 승인 완료 */
    SUCCESS,
    /** PG 승인 실패 확정 */
    FAILED,
    /** PG 타임아웃 — 결과 불명 동결, OrderStatus.UNKNOWN과 쌍으로 관리 */
    UNKNOWN,
    /** PG 환불 완료 */
    REFUNDED
}

package com.example.booking.point.entity;

public enum PointTransactionType {
    /** 결제 시 포인트 차감 */
    USE,
    /** PG 실패 또는 취소 시 포인트 복원 */
    REFUND
}

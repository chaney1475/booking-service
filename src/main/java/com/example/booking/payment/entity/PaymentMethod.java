package com.example.booking.payment.entity;

public enum PaymentMethod {
    CREDIT_CARD,
    /** Y페이 간편결제 */
    PAY,
    /** 서비스 내부 포인트 — PG를 거치지 않고 직접 처리 */
    Y_POINT;

    public boolean isPg() {
        return this == CREDIT_CARD || this == PAY;
    }
}

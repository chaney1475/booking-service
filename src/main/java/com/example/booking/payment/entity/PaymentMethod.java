package com.example.booking.payment.entity;

public enum PaymentMethod {
    CREDIT_CARD,
    /** Y페이 간편결제 */
    PAY,
    /** 내부 포인트 — PG를 거치지 않고 직접 처리 (쟁점 8) */
    POINT;

    public boolean isPg() {
        return this == CREDIT_CARD || this == PAY;
    }
}

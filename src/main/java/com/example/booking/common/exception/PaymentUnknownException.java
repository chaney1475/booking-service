package com.example.booking.common.exception;

public class PaymentUnknownException extends RuntimeException {

    public PaymentUnknownException() {
        super("PG 결과 미확정 — 주문 동결");
    }
}

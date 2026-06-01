package com.example.booking.payment.service;

public record PaymentOutcome(boolean approved, String pgTxRef, String failReason) {
}

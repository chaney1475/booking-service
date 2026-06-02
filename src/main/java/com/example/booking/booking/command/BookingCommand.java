package com.example.booking.booking.command;

import com.example.booking.payment.entity.PaymentMethod;

public record BookingCommand(
        Long userId,
        String idempotencyKey,
        Long eventId,
        Long optionId,
        PaymentMethod paymentMethod,  // Y_POINT 단독이면 null 가능
        long pointsToUse,
        String paymentKey             // 프론트에서 전달받은 PG 토큰 (PG 수단 없으면 null)
) {
}

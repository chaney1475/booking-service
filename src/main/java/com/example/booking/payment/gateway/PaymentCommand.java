package com.example.booking.payment.gateway;

import com.example.booking.payment.entity.PaymentMethod;

/**
 * PG Gateway 호출 단위 커맨드.
 *
 * @param merchantUid   PG 멱등 키 (주문 ID 문자열)
 * @param paymentKey    프론트에서 전달받은 PG 토큰
 * @param paymentMethod 결제 수단
 * @param amount        PG 청구 금액 (원 단위)
 */
public record PaymentCommand(
        String merchantUid,
        String paymentKey,
        PaymentMethod paymentMethod,
        long amount
) {
}

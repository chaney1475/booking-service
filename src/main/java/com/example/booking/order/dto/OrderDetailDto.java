package com.example.booking.order.dto;

import com.example.booking.order.entity.OrderStatus;
import com.example.booking.payment.entity.PaymentMethod;

public record OrderDetailDto(
        Long orderId,
        OrderStatus status,
        long totalAmount,
        long pgAmount,
        long pointsUsed,
        PaymentMethod pgMethod
) {
}

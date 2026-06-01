package com.example.booking.booking.dto;

import com.example.booking.order.entity.OrderStatus;

public record BookingDto(
        Long orderId,
        OrderStatus status,
        long totalAmount,
        long pointsUsed
) {
}

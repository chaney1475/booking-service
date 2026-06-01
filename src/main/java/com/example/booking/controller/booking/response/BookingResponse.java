package com.example.booking.controller.booking.response;

import com.example.booking.booking.dto.BookingDto;
import com.example.booking.order.entity.OrderStatus;

public record BookingResponse(
        Long orderId,
        OrderStatus status,
        long totalAmount,
        long pointsUsed
) {
    public static BookingResponse from(BookingDto dto) {
        return new BookingResponse(dto.orderId(), dto.status(), dto.totalAmount(), dto.pointsUsed());
    }
}

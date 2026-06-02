package com.example.booking.controller.booking.response;

import com.example.booking.booking.dto.BookingDto;
import com.example.booking.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 및 결제 응답")
public record BookingResponse(
        @Schema(description = "주문 ID") Long orderId,
        @Schema(description = "주문 상태") OrderStatus status,
        @Schema(description = "총 결제 금액 (원)") long totalAmount
) {
    public static BookingResponse from(BookingDto dto) {
        return new BookingResponse(dto.orderId(), dto.status(), dto.totalAmount());
    }
}

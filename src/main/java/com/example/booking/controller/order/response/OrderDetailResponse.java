package com.example.booking.controller.order.response;

import com.example.booking.order.dto.OrderDetailDto;
import com.example.booking.order.entity.OrderStatus;
import com.example.booking.payment.entity.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상세 조회 응답")
public record OrderDetailResponse(
        @Schema(description = "주문 ID") Long orderId,
        @Schema(description = "주문 상태") OrderStatus status,
        @Schema(description = "총 결제 금액 (원)") long totalAmount,
        @Schema(description = "PG 결제 금액 (원)") long pgAmount,
        @Schema(description = "Y포인트 사용액 (원)") long pointsUsed,
        @Schema(description = "PG 결제 수단. 포인트 단독 결제 시 null") PaymentMethod pgMethod
) {
    public static OrderDetailResponse from(OrderDetailDto dto) {
        return new OrderDetailResponse(
                dto.orderId(),
                dto.status(),
                dto.totalAmount(),
                dto.pgAmount(),
                dto.pointsUsed(),
                dto.pgMethod()
        );
    }
}

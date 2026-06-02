package com.example.booking.controller.booking.request;

import com.example.booking.booking.command.BookingCommand;
import com.example.booking.payment.entity.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "예약 및 결제 요청")
public record BookingRequest(
        @Schema(description = "이벤트 ID", example = "1")
        @NotNull Long eventId,

        @Schema(description = "이벤트 옵션 ID", example = "1")
        @NotNull Long optionId,

        @Schema(description = "PG 결제 수단. Y_POINT 단독 결제 시 null 가능", example = "CREDIT_CARD",
                allowableValues = {"CREDIT_CARD", "PAY"})
        PaymentMethod paymentMethod,

        @Schema(description = "포인트 사용액 (0 이상)", example = "5000")
        @Min(0) long pointsToUse,

        @Schema(description = "프론트에서 전달받은 PG 토큰. PG 결제 수단 없으면 null 가능", example = "pg_token_abc123")
        String paymentKey
) {
    public BookingCommand toCommand(Long userId, String idempotencyKey) {
        return new BookingCommand(userId, idempotencyKey, eventId, optionId, paymentMethod, pointsToUse, paymentKey);
    }
}

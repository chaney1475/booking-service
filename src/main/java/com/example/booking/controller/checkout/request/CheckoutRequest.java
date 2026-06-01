package com.example.booking.controller.checkout.request;

import com.example.booking.checkout.command.CheckoutCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "체크아웃 조회 요청")
public record CheckoutRequest(
        @Schema(description = "이벤트 ID", example = "1")
        @NotNull Long eventId,

        @Schema(description = "이벤트 옵션 ID", example = "1")
        @NotNull Long optionId
) {
    public CheckoutCommand toCommand(Long userId) {
        return new CheckoutCommand(eventId, optionId, userId);
    }
}

package com.example.booking.controller.checkout.request;

import com.example.booking.checkout.service.command.CheckoutCommand;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull Long eventId,
        @NotNull Long optionId
) {
    public CheckoutCommand toCommand(Long userId) {
        return new CheckoutCommand(eventId, optionId, userId);
    }
}

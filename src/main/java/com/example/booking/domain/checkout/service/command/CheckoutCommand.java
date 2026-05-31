package com.example.booking.domain.checkout.service.command;

public record CheckoutCommand(Long eventId, Long optionId, Long userId) {
}

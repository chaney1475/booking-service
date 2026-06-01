package com.example.booking.checkout.service.command;

public record CheckoutCommand(Long eventId, Long optionId, Long userId) {
}

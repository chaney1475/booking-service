package com.example.booking.checkout.command;

public record CheckoutCommand(Long eventId, Long optionId, Long userId) {
}

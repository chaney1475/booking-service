package com.example.booking.domain.checkout.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

public record CheckoutDto(
        Long eventId,
        ZonedDateTime endsAt,
        String productName,
        long promoPrice,
        Long optionId,
        LocalDate checkInDate,
        LocalTime checkInTime,
        LocalDate checkOutDate,
        LocalTime checkOutTime,
        long userPoints,
        boolean available
) {
}

package com.example.booking.controller.checkout.response;

import com.example.booking.checkout.dto.CheckoutDto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

public record CheckoutResponse(
        EventInfo event,
        ProductInfo product,
        OptionInfo option,
        boolean available,
        long userPoints
) {
    public static CheckoutResponse from(CheckoutDto dto) {
        return new CheckoutResponse(
                new EventInfo(dto.eventId(), dto.endsAt()),
                new ProductInfo(dto.productName()),
                new OptionInfo(
                        dto.optionId(),
                        dto.checkInDate(),
                        dto.checkInTime(),
                        dto.checkOutDate(),
                        dto.checkOutTime(),
                        dto.promoPrice()
                ),
                dto.available(),
                dto.userPoints()
        );
    }

    public record EventInfo(Long eventId, ZonedDateTime endsAt) {}

    public record ProductInfo(String name) {}

    public record OptionInfo(
            Long optionId,
            LocalDate checkInDate,
            LocalTime checkInTime,
            LocalDate checkOutDate,
            LocalTime checkOutTime,
            long promoPrice
    ) {}
}

package com.example.booking.domain.checkout.service;

import com.example.booking.domain.checkout.dto.CheckoutDto;
import com.example.booking.domain.checkout.service.command.CheckoutCommand;
import com.example.booking.domain.event.entity.EventOption;
import com.example.booking.domain.event.service.EventQueryService;
import com.example.booking.domain.point.service.UserPointService;
import com.example.booking.domain.product.entity.RoomOption;
import com.example.booking.domain.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckoutServiceImpl implements CheckoutService {

    private final EventQueryService eventQueryService;
    private final UserPointService userPointService;
    private final StockService stockService;

    @Override
    public CheckoutDto getCheckout(CheckoutCommand command) {
        EventOption eo = eventQueryService.findOptionWithProduct(command.eventId(), command.optionId());
        RoomOption option = eo.getOption();

        // 재고 가용 여부 — GET 시점 힌트, 실제 점유 보장 아님 (reserve.lua에서 확정)
        boolean available = stockService.isAvailable(command.eventId(), command.optionId());

        long userPoints = userPointService.getBalance(command.userId());

        return new CheckoutDto(
                eo.getEvent().getId(),
                eo.getEvent().getEndsAt(),
                option.getProduct().getName(),
                eo.getPromoPrice(),
                option.getId(),
                option.getCheckInDate(),
                option.getCheckInTime(),
                option.checkOutDate(),
                option.getCheckOutTime(),
                userPoints,
                available
        );
    }
}

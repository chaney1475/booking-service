package com.example.booking.checkout;

import com.example.booking.checkout.command.CheckoutCommand;
import com.example.booking.checkout.dto.CheckoutDto;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.service.EventQueryService;
import com.example.booking.point.service.UserPointService;
import com.example.booking.product.entity.RoomOption;
import com.example.booking.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckoutFacade {

    private final EventQueryService eventQueryService;
    private final UserPointService userPointService;
    private final StockService stockService;

    /**
     * 체크아웃 조회 — 이벤트 옵션 정보, 재고 가용 여부, 포인트 잔액을 한 번에 반환함.
     * 재고 가용 여부는 GET 시점 힌트이며 실제 점유는 POST /booking의 reserve.lua에서 확정됨.
     */
    public CheckoutDto getCheckout(CheckoutCommand command) {
        // 이벤트 옵션 조회 — RoomOption(상품·날짜), 프로모션 가격 포함
        EventOption eo = eventQueryService.findOptionWithProduct(command.eventId(), command.optionId());
        RoomOption option = eo.getOption();

        // Redis promo_stock 확인 — 0 이하면 매진 힌트 반환
        boolean available = stockService.isAvailable(command.eventId(), command.optionId());

        // 사용 가능한 포인트 잔액 조회 — 결제 화면에서 차감 가능 금액 표시용
        long userPoints = userPointService.getBalance(command.userId());

        return new CheckoutDto(
                eo.getEvent().getId(),
                eo.getEvent().getEndsAt(),       // 이벤트 종료 시각 — 프론트 카운트다운 표시용
                option.getProduct().getName(),
                eo.getPromoPrice(),              // 이벤트 할인가
                option.getId(),
                option.getCheckInDate(),
                option.getCheckInTime(),
                option.checkOutDate(),           // 1박 고정이라 checkIn + 1일 파생값
                option.getCheckOutTime(),
                userPoints,
                available
        );
    }
}

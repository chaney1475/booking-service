package com.example.booking.booking;

import com.example.booking.booking.command.BookingCommand;
import com.example.booking.booking.dto.BookingDto;
import com.example.booking.booking.idempotency.IdempotencyStore;
import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.common.exception.PaymentUnknownException;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.service.EventQueryService;
import com.example.booking.order.entity.Order;
import com.example.booking.order.entity.OrderStatus;
import com.example.booking.order.service.OrderService;
import com.example.booking.payment.orchestrator.PaymentContext;
import com.example.booking.payment.orchestrator.PaymentOrchestrator;
import com.example.booking.stock.service.ReserveResult;
import com.example.booking.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFacade {

    private final EventQueryService eventQueryService;
    private final StockService stockService;
    private final OrderService orderService;
    private final PaymentOrchestrator paymentOrchestrator;
    private final IdempotencyStore idempotencyStore;

    /**
     * 예약 전체 흐름을 조율한다: 멱등성 검사 → 재고 선점 → 결제 → 확정.
     * <p>결제·재고 실패 시 재고 반납 및 멱등 키를 해제해 재시도를 허용한다.</p>
     */
    public BookingDto book(BookingCommand command) {
        // 1계층: DB 조회 — TTL 만료·Redis 장애 후에도 PAID 반환 가능
        Optional<Order> existingOpt = orderService.findByIdempotencyKey(command.idempotencyKey());
        if (existingOpt.isPresent()) {
            Order order = existingOpt.get();
            if (order.getStatus() == OrderStatus.PAID) {
                return new BookingDto(order.getId(), OrderStatus.PAID, order.getTotalAmount());
            }
            if (order.getStatus() == OrderStatus.UNKNOWN) {
                throw new BaseException(ErrorCode.ORDER_IN_UNKNOWN_STATE);
            }
            // PENDING·FAILED → 2계층으로 (재시도 허용)
        }

        // 2계층: Redis SET NX — 동시 중복 차단 + 성공 결과 캐시 replay
        String idemValue = idempotencyStore.tryAcquire(command.idempotencyKey());
        if (idemValue != null) {
            if (idempotencyStore.isInProgress(idemValue)) {
                throw new BaseException(ErrorCode.DUPLICATE_ENTRY);
            }
            return idempotencyStore.parse(idemValue)
                    .orElseThrow(() -> new BaseException(ErrorCode.DUPLICATE_ENTRY));
        }

        // 3. reserve.lua — 1인 1구매 + oversell 원자 차단
        ReserveResult reserveResult = stockService.reserve(
                command.eventId(), command.optionId(), command.userId());

        if (reserveResult != ReserveResult.OK) {
            idempotencyStore.release(command.idempotencyKey());
            throw switch (reserveResult) {
                case SOLD_OUT          -> new BaseException(ErrorCode.SOLD_OUT);
                case ALREADY_PURCHASED -> new BaseException(ErrorCode.ALREADY_PURCHASED);
                case DUPLICATE_ENTRY   -> new BaseException(ErrorCode.DUPLICATE_ENTRY);
                default                -> new BaseException(ErrorCode.BOOKING_UNAVAILABLE);
            };
        }

        try {
            EventOption eventOption = eventQueryService.findOptionWithProduct(
                    command.eventId(), command.optionId());
            PaymentContext ctx = paymentOrchestrator.process(command, eventOption);

            // T2: 재고 확정 + 주문 PAID + 멱등 결과 캐시
            stockService.confirm(command.eventId(), command.optionId(), command.userId());
            orderService.markPaid(ctx.order().getId(), ctx.pgTxRef(), ctx.pgMethod(),
                    ctx.pgAmount(), command.pointsToUse());

            long promoPrice = eventOption.getPromoPrice();
            BookingDto dto = new BookingDto(ctx.order().getId(), OrderStatus.PAID, promoPrice);
            idempotencyStore.setResult(command.idempotencyKey(), dto);
            return dto;

        } catch (PaymentUnknownException e) {
            // UNKNOWN 동결 — 재고·포인트 그대로 유지, release 하지 않음
            throw e;
        } catch (Exception e) {
            // 보상: 재고 반납 + idem 해제 (재시도 허용)
            stockService.release(command.eventId(), command.optionId(), command.userId());
            idempotencyStore.release(command.idempotencyKey());
            throw e;
        }
    }
}

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
import com.example.booking.payment.entity.PaymentMethod;
import com.example.booking.payment.service.MockPaymentProcessor;
import com.example.booking.payment.service.PaymentOutcome;
import com.example.booking.point.service.UserPointService;
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
    private final UserPointService userPointService;
    private final MockPaymentProcessor paymentProcessor;
    private final IdempotencyStore idempotencyStore;

    public BookingDto book(BookingCommand command) {
        // 1계층: DB 조회 — TTL 만료·Redis 장애 후에도 PAID 반환 가능
        Optional<Order> existingOpt = orderService.findByIdempotencyKey(command.idempotencyKey());
        if (existingOpt.isPresent()) {
            Order order = existingOpt.get();
            if (order.getStatus() == OrderStatus.PAID) {
                return new BookingDto(order.getId(), OrderStatus.PAID, order.getTotalAmount(), 0L);
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
            return executeBooking(command);
        } catch (PaymentUnknownException e) {
            // UNKNOWN 동결 — 재고·포인트 그대로 유지, release 하지 않음 (쟁점 6)
            throw e;
        } catch (Exception e) {
            // 보상: 재고 반납 + idem 해제 (재시도 허용)
            stockService.release(command.eventId(), command.optionId(), command.userId());
            idempotencyStore.release(command.idempotencyKey());
            throw e;
        }
    }

    private BookingDto executeBooking(BookingCommand command) {
        validatePaymentCombination(command);

        EventOption eventOption = eventQueryService.findOptionWithProduct(
                command.eventId(), command.optionId());
        long promoPrice  = eventOption.getPromoPrice();
        long pointsToUse = command.pointsToUse();
        long pgAmount    = promoPrice - pointsToUse;

        // T1: Order + OrderLine + Payment(PENDING) INSERT
        Order order = orderService.createPending(
                eventOption, command.userId(), command.idempotencyKey(), promoPrice, pgAmount);

        // 포인트 차감 — PG 호출 전 (쟁점 5). 잔액 부족 시 예외 → catch가 재고+idem 보상
        if (pointsToUse > 0) {
            userPointService.deduct(command.userId(), pointsToUse, order);
        }

        // PG 호출 (mock: 50% APPROVED / 50% REJECTED)
        PaymentOutcome outcome = paymentProcessor.approve(String.valueOf(order.getId()), pgAmount);

        if (outcome.approved()) {
            return handleApproved(command, order, outcome, promoPrice, pgAmount, pointsToUse);
        } else {
            return handleRejected(command, order, outcome, pointsToUse);
        }
    }

    private BookingDto handleApproved(BookingCommand command, Order order, PaymentOutcome outcome,
                                      long promoPrice, long pgAmount, long pointsToUse) {
        // confirm.lua — sold +1, purchased SET 기록
        stockService.confirm(command.eventId(), command.optionId(), command.userId());

        PaymentMethod pgMethod = (pgAmount > 0 && command.paymentMethod() != null)
                ? command.paymentMethod() : null;

        // T2: Order PAID + Payment SUCCESS + PaymentLine 기록
        orderService.markPaid(order.getId(), outcome.pgTxRef(), pgMethod, pgAmount, pointsToUse);

        BookingDto dto = new BookingDto(order.getId(), OrderStatus.PAID, promoPrice, pointsToUse);
        idempotencyStore.setResult(command.idempotencyKey(), dto);
        return dto;
    }

    private BookingDto handleRejected(BookingCommand command, Order order, PaymentOutcome outcome,
                                      long pointsToUse) {
        // 포인트 환불 — 차감한 주체가 보상 (쟁점 5)
        if (pointsToUse > 0) {
            userPointService.refund(command.userId(), pointsToUse, order);
        }
        // T2: Order FAILED + Payment FAILED
        orderService.markFailed(order.getId(), outcome.failReason());
        // catch가 재고 release + idem release 처리
        throw new BaseException(ErrorCode.PAYMENT_FAILED);
    }

    private void validatePaymentCombination(BookingCommand command) {
        long pointsToUse = command.pointsToUse();
        PaymentMethod method = command.paymentMethod();

        // PG 금액이 0보다 큰데 PG 수단이 없거나, PG 금액이 0인데 PG 수단이 있으면 불일치
        EventOption eo = eventQueryService.findOptionWithProduct(command.eventId(), command.optionId());
        long pgAmount = eo.getPromoPrice() - pointsToUse;

        if (pgAmount < 0) {
            throw new BaseException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (pgAmount > 0 && (method == null || !method.isPg())) {
            throw new BaseException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
        if (pgAmount == 0 && method != null && method.isPg()) {
            throw new BaseException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
    }
}

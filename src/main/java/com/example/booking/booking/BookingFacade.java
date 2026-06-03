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
     *
     * <p>pgApproved 플래그로 PG 승인 전/후 실패를 구분한다.
     * <ul>
     *   <li>PG 승인 전 실패: PENDING 주문 FAILED 처리 + 재고 반납 + idem 해제 → 재시도 허용</li>
     *   <li>PG 승인 후 실패: UNKNOWN 동결 → 재시도 금지 (이중결제 위험), 배치 정산 대상</li>
     * </ul>
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

        // 2계층: 이벤트·옵션 존재 및 OPEN 상태 검증 — 순수 읽기, idem 획득 전에 수행해 실패 시 cleanup 불필요
        EventOption eventOption = eventQueryService.findOptionWithProduct(
                command.eventId(), command.optionId());

        // 3계층: Redis SET NX — 동시 중복 차단 + 성공 결과 캐시 replay
        String idemValue = idempotencyStore.tryAcquire(command.idempotencyKey());
        if (idemValue != null) {
            if (idempotencyStore.isInProgress(idemValue)) {
                throw new BaseException(ErrorCode.DUPLICATE_ENTRY);
            }
            return idempotencyStore.parse(idemValue)
                    .orElseThrow(() -> new BaseException(ErrorCode.DUPLICATE_ENTRY));
        }

        // 4. reserve.lua — 1인 1구매 + oversell 원자 차단
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

        PaymentContext ctx = null;
        boolean pgApproved = false;

        try {
            ctx = paymentOrchestrator.process(command, eventOption);
            pgApproved = true; // PG 승인 완료 (포인트 단독 포함) — 이후 재시도 금지

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
            if (pgApproved) {
                // PG 이미 승인 — 재시도 시 이중결제 위험, UNKNOWN으로 동결
                log.error("[BookingFacade] PG 승인 후 후속 처리 실패 — UNKNOWN 동결. orderId={}",
                        ctx != null ? ctx.order().getId() : "unknown", e);
                if (ctx != null) {
                    orderService.markUnknown(ctx.order().getId(), e.getMessage());
                }
            } else {
                // PG 호출 전 실패 — 잔류 PENDING 주문 정리 후 재시도 허용
                orderService.findByIdempotencyKey(command.idempotencyKey())
                        .filter(o -> o.getStatus() == OrderStatus.PENDING)
                        .ifPresent(o -> orderService.markFailed(o.getId(), e.getMessage()));
                stockService.release(command.eventId(), command.optionId(), command.userId());
                idempotencyStore.release(command.idempotencyKey());
            }
            throw e;
        }
    }
}

package com.example.booking.payment.orchestrator;

import com.example.booking.booking.command.BookingCommand;
import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.common.exception.PaymentUnknownException;
import com.example.booking.event.entity.EventOption;
import com.example.booking.order.entity.Order;
import com.example.booking.order.service.OrderService;
import com.example.booking.payment.gateway.PaymentCommand;
import com.example.booking.payment.gateway.PaymentGateway;
import com.example.booking.payment.gateway.PaymentOutcome;
import com.example.booking.payment.router.PaymentGatewayRouter;
import com.example.booking.point.service.UserPointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 흐름을 총괄한다: 정책 검증 → 주문 PENDING → 포인트 차감 → PG 호출 → 결과 반환.
 * <p>
 * 재고 확정·주문 PAID·멱등 캐시는 호출자({@link com.example.booking.booking.BookingFacade})가 담당한다.
 * 포인트는 PG 호출 전에 차감(내부 먼저), PG 거절 시 즉시 환불한다.
 * UNKNOWN(타임아웃) 시 포인트·재고를 동결하고 {@link PaymentUnknownException}을 던진다.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOrchestrator {

    private final OrderService orderService;
    private final UserPointService userPointService;
    private final PaymentGatewayRouter gatewayRouter;
    private final PaymentPolicy paymentPolicy;

    /**
     * 결제를 처리하고 승인 확정에 필요한 컨텍스트를 반환한다.
     *
     * @param command     예약 커맨드
     * @param eventOption 이벤트 옵션 (가격 기준)
     * @return 승인 확정 컨텍스트
     * @throws PaymentUnknownException PG 결과 미확정(UNKNOWN) 시
     * @throws BaseException           결제 거절·포인트 부족 등 비즈니스 오류 시
     */
    public PaymentContext process(BookingCommand command, EventOption eventOption) {
        paymentPolicy.validate(command, eventOption);

        long promoPrice  = eventOption.getPromoPrice();
        long pointsToUse = command.pointsToUse();
        long pgAmount    = promoPrice - pointsToUse;

        // T1: 주문·결제 PENDING INSERT
        Order order = orderService.createPending(
                eventOption, command.userId(), command.idempotencyKey(), promoPrice, pgAmount);

        // 포인트 차감 — PG 호출 전 (내부 먼저 원칙)
        if (pointsToUse > 0) {
            userPointService.deduct(command.userId(), pointsToUse, order);
        }

        // 포인트 단독 결제: PG 없이 바로 승인 컨텍스트 반환
        if (pgAmount == 0) {
            return new PaymentContext(order, null, null, 0);
        }

        // PG 호출
        PaymentGateway gateway = gatewayRouter.route(command.paymentMethod());
        PaymentOutcome outcome = gateway.approve(new PaymentCommand(
                String.valueOf(order.getId()),
                command.paymentKey(),
                command.paymentMethod(),
                pgAmount
        ));

        if (outcome.isApproved()) {
            return new PaymentContext(order, outcome.pgTxRef(), command.paymentMethod(), pgAmount);
        }
        if (outcome.isUnknown()) {
            throw handleUnknown(order, outcome);
        }
        throw handleRejected(command, order, outcome, pointsToUse);
    }

    /**
     * PG 거절: 포인트 환불 → 주문 FAILED.
     * 재고 반납·멱등 키 해제는 호출자(BookingFacade)의 catch가 담당한다.
     */
    private BaseException handleRejected(BookingCommand command, Order order,
                                         PaymentOutcome outcome, long pointsToUse) {
        if (pointsToUse > 0) {
            userPointService.refund(command.userId(), pointsToUse, order);
        }
        orderService.markFailed(order.getId(), outcome.failReason());
        return new BaseException(ErrorCode.PAYMENT_FAILED);
    }

    /**
     * PG 결과 미확정: 주문 UNKNOWN 저장 후 동결.
     * 포인트·재고를 즉시 해제하지 않는다 (이중 결제 위험).
     */
    private PaymentUnknownException handleUnknown(Order order, PaymentOutcome outcome) {
        orderService.markUnknown(order.getId(), outcome.failReason());
        log.warn("[PaymentOrchestrator] UNKNOWN 동결 — orderId={}", order.getId());
        return new PaymentUnknownException();
    }
}

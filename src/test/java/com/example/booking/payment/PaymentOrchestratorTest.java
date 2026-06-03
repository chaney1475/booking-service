package com.example.booking.payment;

import com.example.booking.booking.command.BookingCommand;
import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.common.exception.PaymentUnknownException;
import com.example.booking.event.entity.EventOption;
import com.example.booking.order.entity.Order;
import com.example.booking.order.service.OrderService;
import com.example.booking.payment.entity.PaymentMethod;
import com.example.booking.payment.gateway.PaymentGateway;
import com.example.booking.payment.gateway.PaymentOutcome;
import com.example.booking.payment.orchestrator.PaymentContext;
import com.example.booking.payment.orchestrator.PaymentOrchestrator;
import com.example.booking.payment.orchestrator.PaymentPolicy;
import com.example.booking.payment.router.PaymentGatewayRouter;
import com.example.booking.point.service.PointProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PaymentOrchestrator.process() 단위 테스트.
 *
 * <p>BookingFacadeTest에서는 결제 실패/UNKNOWN 전파 여부만 확인하므로,
 * 이 테스트에서 포인트 차감·환불, PG 라우팅, 주문 상태 전환을 직접 검증한다.</p>
 *
 * <p>PaymentPolicy는 Mock으로 대체해 정책 검증 로직을 제거하고
 * Orchestrator의 흐름 제어만 집중 검증한다.
 * 정책 규칙은 PaymentPolicyTest에서 별도로 다룬다.</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorTest {

    @Mock OrderService orderService;
    @Mock PointProcessor pointProcessor;
    @Mock PaymentGatewayRouter gatewayRouter;  // 수단별 Gateway 라우팅
    @Mock PaymentPolicy paymentPolicy;          // 금액·수단 조합 검증 — void이므로 별도 스텁 불필요

    @InjectMocks PaymentOrchestrator orchestrator;

    private static final Long USER_ID     = 1L;
    private static final Long ORDER_ID    = 99L;    // markFailed/markUnknown 호출 검증에 사용
    private static final long PROMO_PRICE = 50_000L;
    private static final long POINTS      = 10_000L; // 혼합 결제 시 포인트 사용액

    /** 카드 결제 커맨드. points=0이면 카드 단독, points>0이면 카드+포인트 혼합 */
    private BookingCommand cardCommand(long points) {
        return new BookingCommand(USER_ID, "idem-key", 10L, 100L,
                PaymentMethod.CREDIT_CARD, points, "pg-token");
    }

    /** 포인트 단독 커맨드 (pointsToUse = promoPrice → pgAmount = 0) */
    private BookingCommand pointOnlyCommand() {
        return new BookingCommand(USER_ID, "idem-key", 10L, 100L,
                null, PROMO_PRICE, null);
    }

    private EventOption mockOption(long promoPrice) {
        EventOption opt = mock(EventOption.class);
        given(opt.getPromoPrice()).willReturn(promoPrice);
        return opt;
    }

    /**
     * markFailed/markUnknown 검증 시 order.getId() 값이 필요.
     * 해피패스·포인트 단독은 이 경로를 타지 않으므로 mock(Order.class)를 직접 사용한다.
     */
    private Order mockOrder() {
        Order order = mock(Order.class);
        given(order.getId()).willReturn(ORDER_ID);
        return order;
    }

    // =====================
    @Nested
    @DisplayName("PG APPROVED")
    class Approved {

        @Test
        @DisplayName("포인트 있음: deduct 1회 호출 + PaymentContext 반환")
        void approvedWithPoint() {
            // given
            long pgAmount = PROMO_PRICE - POINTS; // 포인트 차감 후 PG 청구 금액
            BookingCommand cmd = cardCommand(POINTS);
            EventOption option = mockOption(PROMO_PRICE);
            Order order = mockOrder();
            PaymentGateway gateway = mock(PaymentGateway.class);

            given(orderService.createPending(any(), any(), any(), anyLong(), anyLong())).willReturn(order);
            given(gatewayRouter.route(PaymentMethod.CREDIT_CARD)).willReturn(gateway); // 수단에 맞는 Gateway 반환
            given(gateway.approve(any())).willReturn(PaymentOutcome.approved("pg-tx-ref"));

            // when
            PaymentContext ctx = orchestrator.process(cmd, option);

            // then
            assertThat(ctx.order()).isEqualTo(order);
            assertThat(ctx.pgTxRef()).isEqualTo("pg-tx-ref");
            assertThat(ctx.pgAmount()).isEqualTo(pgAmount);
            verify(pointProcessor).deduct(USER_ID, POINTS, order); // 포인트 선차감 (PG 호출 전)
            verify(pointProcessor, never()).refund(anyLong(), anyLong(), any()); // 승인 성공 — 환불 없음
        }

        @Test
        @DisplayName("포인트 없음: deduct/refund 미호출 + PaymentContext 반환")
        void approvedWithoutPoint() {
            // given
            BookingCommand cmd = cardCommand(0L);
            EventOption option = mockOption(PROMO_PRICE);
            Order order = mockOrder();
            PaymentGateway gateway = mock(PaymentGateway.class);

            given(orderService.createPending(any(), any(), any(), anyLong(), anyLong())).willReturn(order);
            given(gatewayRouter.route(PaymentMethod.CREDIT_CARD)).willReturn(gateway);
            given(gateway.approve(any())).willReturn(PaymentOutcome.approved("pg-tx-ref"));

            // when
            PaymentContext ctx = orchestrator.process(cmd, option);

            // then
            assertThat(ctx.pgAmount()).isEqualTo(PROMO_PRICE);
            verify(pointProcessor, never()).deduct(anyLong(), anyLong(), any());
            verify(pointProcessor, never()).refund(anyLong(), anyLong(), any());
        }
    }

    // =====================
    @Nested
    @DisplayName("PG REJECTED")
    class Rejected {

        /**
         * 포인트는 PG 호출 전에 이미 차감된 상태.
         * REJECTED 시 즉시 환불해야 포인트가 묶이지 않는다.
         */
        @Test
        @DisplayName("포인트 있음: refund 1회 호출 + markFailed 호출 + PAYMENT_FAILED 예외")
        void rejectedWithPoint() {
            // given
            BookingCommand cmd = cardCommand(POINTS);
            EventOption option = mockOption(PROMO_PRICE);
            Order order = mockOrder();
            PaymentGateway gateway = mock(PaymentGateway.class);

            given(orderService.createPending(any(), any(), any(), anyLong(), anyLong())).willReturn(order);
            given(gatewayRouter.route(PaymentMethod.CREDIT_CARD)).willReturn(gateway);
            given(gateway.approve(any())).willReturn(PaymentOutcome.rejected("한도 초과"));

            // when & then
            assertThatThrownBy(() -> orchestrator.process(cmd, option))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);
            verify(pointProcessor).refund(USER_ID, POINTS, order); // 선차감된 포인트 즉시 환불
            verify(orderService).markFailed(eq(ORDER_ID), anyString());
        }

        @Test
        @DisplayName("포인트 없음: refund 미호출 + markFailed 호출 + PAYMENT_FAILED 예외")
        void rejectedWithoutPoint() {
            // given
            BookingCommand cmd = cardCommand(0L);
            EventOption option = mockOption(PROMO_PRICE);
            Order order = mockOrder();
            PaymentGateway gateway = mock(PaymentGateway.class);

            given(orderService.createPending(any(), any(), any(), anyLong(), anyLong())).willReturn(order);
            given(gatewayRouter.route(PaymentMethod.CREDIT_CARD)).willReturn(gateway);
            given(gateway.approve(any())).willReturn(PaymentOutcome.rejected("한도 초과"));

            // when & then
            assertThatThrownBy(() -> orchestrator.process(cmd, option))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);
            verify(pointProcessor, never()).refund(anyLong(), anyLong(), any()); // 차감 없었으니 환불도 없음
            verify(orderService).markFailed(eq(ORDER_ID), anyString());
        }
    }

    // =====================
    @Nested
    @DisplayName("PG UNKNOWN")
    class Unknown {

        /**
         * UNKNOWN은 PG 결과 미확정 — 이중 결제 위험이 있으므로 포인트를 즉시 환불하지 않는다.
         * 배치 정산 후 사후 확정 시 처리된다.
         */
        @Test
        @DisplayName("markUnknown 호출 + refund 미호출 + PaymentUnknownException 전파")
        void unknown() {
            // given
            BookingCommand cmd = cardCommand(POINTS);
            EventOption option = mockOption(PROMO_PRICE);
            Order order = mockOrder();
            PaymentGateway gateway = mock(PaymentGateway.class);

            given(orderService.createPending(any(), any(), any(), anyLong(), anyLong())).willReturn(order);
            given(gatewayRouter.route(PaymentMethod.CREDIT_CARD)).willReturn(gateway);
            given(gateway.approve(any())).willReturn(PaymentOutcome.unknown());

            // when & then
            assertThatThrownBy(() -> orchestrator.process(cmd, option))
                    .isInstanceOf(PaymentUnknownException.class);
            verify(orderService).markUnknown(eq(ORDER_ID), anyString()); // 주문 동결
            verify(pointProcessor, never()).refund(anyLong(), anyLong(), any()); // 포인트 동결 유지
        }
    }

    // =====================
    @Nested
    @DisplayName("포인트 단독")
    class PointOnly {

        /**
         * pgAmount = 0이면 PG 라우팅 자체를 건너뛴다.
         * gatewayRouter.route()가 호출되지 않아야 외부 PG 연동이 없음을 보장한다.
         */
        @Test
        @DisplayName("PG 라우팅/승인 미호출 + PaymentContext(pgTxRef=null, pgMethod=null, pgAmount=0) 반환")
        void pointOnly() {
            // given
            BookingCommand cmd = pointOnlyCommand();
            EventOption option = mockOption(PROMO_PRICE);
            Order order = mock(Order.class); // 이 경로는 order.getId() 미호출

            given(orderService.createPending(any(), any(), any(), anyLong(), anyLong())).willReturn(order);

            // when
            PaymentContext ctx = orchestrator.process(cmd, option);

            // then
            assertThat(ctx.pgTxRef()).isNull();
            assertThat(ctx.pgMethod()).isNull();
            assertThat(ctx.pgAmount()).isZero();
            verify(gatewayRouter, never()).route(any()); // PG 라우팅 없음
            verify(pointProcessor).deduct(USER_ID, PROMO_PRICE, order); // 전액 포인트 차감
        }
    }

    // import static 충돌 방지 — Long 타입 eq를 명시적으로 위임
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}

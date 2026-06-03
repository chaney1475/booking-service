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
import com.example.booking.payment.orchestrator.PaymentContext;
import com.example.booking.payment.orchestrator.PaymentOrchestrator;
import com.example.booking.stock.service.ReserveResult;
import com.example.booking.stock.service.StockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingFacadeTest {

    @Mock EventQueryService eventQueryService;
    @Mock StockService stockService;
    @Mock OrderService orderService;
    @Mock PaymentOrchestrator paymentOrchestrator;
    @Mock IdempotencyStore idempotencyStore;

    @InjectMocks BookingFacade bookingFacade;

    private static final Long USER_ID    = 1L;
    private static final Long EVENT_ID   = 10L;
    private static final Long OPTION_ID  = 100L;
    private static final String IDEM_KEY = "idem-key-001";
    private static final long PROMO_PRICE = 50_000L;

    private BookingCommand cardCommand() {
        return new BookingCommand(USER_ID, IDEM_KEY, EVENT_ID, OPTION_ID,
                PaymentMethod.CREDIT_CARD, 0L, "pg-token-001");
    }

    private BookingCommand cardWithPointCommand(long points) {
        return new BookingCommand(USER_ID, IDEM_KEY, EVENT_ID, OPTION_ID,
                PaymentMethod.CREDIT_CARD, points, "pg-token-001");
    }

    private BookingCommand pointOnlyCommand() {
        return new BookingCommand(USER_ID, IDEM_KEY, EVENT_ID, OPTION_ID,
                null, PROMO_PRICE, null);
    }

    private EventOption mockEventOption() {
        EventOption opt = mock(EventOption.class);
        given(opt.getPromoPrice()).willReturn(PROMO_PRICE);
        return opt;
    }

    private Order mockOrder(Long id) {
        Order order = mock(Order.class);
        given(order.getId()).willReturn(id);
        return order;
    }

    // =====================
    @Nested
    @DisplayName("해피 패스")
    class HappyPath {

        @Test
        @DisplayName("카드 단독: markPaid·confirm·setResult 호출")
        void cardOnly() {
            // given
            BookingCommand cmd = cardCommand();
            EventOption option = mockEventOption();
            Order order = mockOrder(999L);

            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.OK);
            given(eventQueryService.findOptionWithProduct(EVENT_ID, OPTION_ID)).willReturn(option);
            given(paymentOrchestrator.process(cmd, option))
                    .willReturn(new PaymentContext(order, "pg-tx-ref", PaymentMethod.CREDIT_CARD, PROMO_PRICE));

            // when
            BookingDto result = bookingFacade.book(cmd);

            // then
            assertThat(result.status()).isEqualTo(OrderStatus.PAID);
            verify(stockService).confirm(EVENT_ID, OPTION_ID, USER_ID);
            verify(orderService).markPaid(eq(999L), eq("pg-tx-ref"), eq(PaymentMethod.CREDIT_CARD),
                    eq(PROMO_PRICE), eq(0L));
            verify(idempotencyStore).setResult(eq(IDEM_KEY), any());
        }

        @Test
        @DisplayName("카드 + 포인트: markPaid에 pgAmount·pointsToUse 분리 전달")
        void cardWithPoint() {
            // given
            long points = 10_000L;
            BookingCommand cmd = cardWithPointCommand(points);
            EventOption option = mockEventOption();
            Order order = mockOrder(998L);

            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.OK);
            given(eventQueryService.findOptionWithProduct(EVENT_ID, OPTION_ID)).willReturn(option);
            given(paymentOrchestrator.process(cmd, option))
                    .willReturn(new PaymentContext(order, "pg-tx-ref", PaymentMethod.CREDIT_CARD,
                            PROMO_PRICE - points));

            // when
            BookingDto result = bookingFacade.book(cmd);

            // then
            assertThat(result.status()).isEqualTo(OrderStatus.PAID);
            verify(orderService).markPaid(eq(998L), anyString(), eq(PaymentMethod.CREDIT_CARD),
                    eq(PROMO_PRICE - points), eq(points));
        }

        @Test
        @DisplayName("포인트 단독: pgMethod=null·pgAmount=0으로 markPaid 호출")
        void pointOnly() {
            // given
            BookingCommand cmd = pointOnlyCommand();
            EventOption option = mockEventOption();
            Order order = mockOrder(997L);

            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.OK);
            given(eventQueryService.findOptionWithProduct(EVENT_ID, OPTION_ID)).willReturn(option);
            given(paymentOrchestrator.process(cmd, option))
                    .willReturn(new PaymentContext(order, null, null, 0));

            // when
            BookingDto result = bookingFacade.book(cmd);

            // then
            assertThat(result.status()).isEqualTo(OrderStatus.PAID);
            verify(orderService).markPaid(eq(997L), eq(null), eq(null), eq(0L), eq(PROMO_PRICE));
        }
    }

    // =====================
    @Nested
    @DisplayName("멱등성")
    class Idempotency {

        @Test
        @DisplayName("DB Layer 1 replay: PAID 주문 존재 → reserve/PG/markPaid 미호출")
        void dbLayerReplay() {
            // given
            Order paidOrder = mockOrder(100L);
            given(paidOrder.getStatus()).willReturn(OrderStatus.PAID);
            given(paidOrder.getTotalAmount()).willReturn(PROMO_PRICE);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.of(paidOrder));

            // when
            BookingDto result = bookingFacade.book(cardCommand());

            // then
            assertThat(result.status()).isEqualTo(OrderStatus.PAID);
            verify(stockService, never()).reserve(anyLong(), anyLong(), anyLong());
            verify(paymentOrchestrator, never()).process(any(), any());
            verify(orderService, never()).markPaid(anyLong(), any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Redis Layer 2 replay: JSON 캐시 존재 → reserve/PG 미호출")
        void redisLayerReplay() {
            // given
            BookingDto cachedDto = new BookingDto(200L, OrderStatus.PAID, PROMO_PRICE);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn("{\"orderId\":200}");
            given(idempotencyStore.isInProgress("{\"orderId\":200}")).willReturn(false);
            given(idempotencyStore.parse("{\"orderId\":200}")).willReturn(Optional.of(cachedDto));

            // when
            BookingDto result = bookingFacade.book(cardCommand());

            // then
            assertThat(result.orderId()).isEqualTo(200L);
            verify(stockService, never()).reserve(anyLong(), anyLong(), anyLong());
            verify(paymentOrchestrator, never()).process(any(), any());
        }

        @Test
        @DisplayName("IN_PROGRESS 중복 요청: DUPLICATE_ENTRY 예외, reserve 미호출")
        void inProgressDuplicate() {
            // given
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn("IN_PROGRESS");
            given(idempotencyStore.isInProgress("IN_PROGRESS")).willReturn(true);

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_ENTRY);
            verify(stockService, never()).reserve(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("UNKNOWN 주문 동일 키 재요청: ORDER_IN_UNKNOWN_STATE 예외")
        void unknownOrderReplay() {
            // given
            Order unknownOrder = mock(Order.class);
            given(unknownOrder.getStatus()).willReturn(OrderStatus.UNKNOWN);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.of(unknownOrder));

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_IN_UNKNOWN_STATE);
            verify(stockService, never()).reserve(anyLong(), anyLong(), anyLong());
        }
    }

    // =====================
    @Nested
    @DisplayName("재고 / 정책 오류")
    class StockAndPolicy {

        @Test
        @DisplayName("SOLD_OUT: idem 해제 호출, markFailed 미호출")
        void soldOut() {
            // given
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.SOLD_OUT);

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SOLD_OUT);
            verify(idempotencyStore).release(IDEM_KEY);
            verify(orderService, never()).markPaid(anyLong(), any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("포인트 초과 (PAYMENT_AMOUNT_MISMATCH): release + idem 해제 호출")
        void paymentAmountMismatch() {
            // given
            EventOption option = mock(EventOption.class);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.OK);
            given(eventQueryService.findOptionWithProduct(EVENT_ID, OPTION_ID)).willReturn(option);
            given(paymentOrchestrator.process(any(), any()))
                    .willThrow(new BaseException(ErrorCode.PAYMENT_AMOUNT_MISMATCH));

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
            verify(stockService).release(EVENT_ID, OPTION_ID, USER_ID);
            verify(idempotencyStore).release(IDEM_KEY);
        }
    }

    // =====================
    @Nested
    @DisplayName("결제 오류")
    class PaymentError {

        @Test
        @DisplayName("PG REJECTED: release + idem 해제 호출")
        void pgRejected() {
            // given
            EventOption option = mock(EventOption.class);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.OK);
            given(eventQueryService.findOptionWithProduct(EVENT_ID, OPTION_ID)).willReturn(option);
            given(paymentOrchestrator.process(any(), any()))
                    .willThrow(new BaseException(ErrorCode.PAYMENT_FAILED));

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);
            verify(stockService).release(EVENT_ID, OPTION_ID, USER_ID);
            verify(idempotencyStore).release(IDEM_KEY);
        }

        @Test
        @DisplayName("PG UNKNOWN: release 미호출, idem 미해제, PaymentUnknownException 전파")
        void pgUnknown() {
            // given
            EventOption option = mock(EventOption.class);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null);
            given(stockService.reserve(EVENT_ID, OPTION_ID, USER_ID)).willReturn(ReserveResult.OK);
            given(eventQueryService.findOptionWithProduct(EVENT_ID, OPTION_ID)).willReturn(option);
            given(paymentOrchestrator.process(any(), any()))
                    .willThrow(new PaymentUnknownException());

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(PaymentUnknownException.class);
            verify(stockService, never()).release(anyLong(), anyLong(), anyLong());
            verify(idempotencyStore, never()).release(anyString());
        }
    }
}

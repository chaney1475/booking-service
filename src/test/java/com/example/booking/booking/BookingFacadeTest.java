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

/**
 * BookingFacade.book() 시나리오 단위 테스트.
 *
 * <p>모든 협력 객체(StockService, PaymentOrchestrator, IdempotencyStore 등)를 Mock으로 대체해
 * Facade 자체의 흐름 제어(멱등성 계층, 보상 로직, 예외 전파)만 검증한다.
 * 결제 내부(포인트 차감·환불, PG 호출)는 PaymentOrchestratorTest에서 별도 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class BookingFacadeTest {

    @Mock EventQueryService eventQueryService;
    @Mock StockService stockService;
    @Mock OrderService orderService;
    @Mock PaymentOrchestrator paymentOrchestrator;
    @Mock IdempotencyStore idempotencyStore;

    @InjectMocks BookingFacade bookingFacade;

    // 테스트 전반에 걸쳐 사용하는 식별자 픽스처
    private static final Long USER_ID    = 1L;
    private static final Long EVENT_ID   = 10L;
    private static final Long OPTION_ID  = 100L;
    private static final String IDEM_KEY = "idem-key-001";
    private static final long PROMO_PRICE = 50_000L;

    /** 카드 단독 결제 커맨드 (포인트 0, PG 토큰 포함) */
    private BookingCommand cardCommand() {
        return new BookingCommand(USER_ID, IDEM_KEY, EVENT_ID, OPTION_ID,
                PaymentMethod.CREDIT_CARD, 0L, "pg-token-001");
    }

    /** 카드 + 포인트 혼합 결제 커맨드 */
    private BookingCommand cardWithPointCommand(long points) {
        return new BookingCommand(USER_ID, IDEM_KEY, EVENT_ID, OPTION_ID,
                PaymentMethod.CREDIT_CARD, points, "pg-token-001");
    }

    /** 포인트 단독 결제 커맨드 (paymentMethod=null, paymentKey=null) */
    private BookingCommand pointOnlyCommand() {
        return new BookingCommand(USER_ID, IDEM_KEY, EVENT_ID, OPTION_ID,
                null, PROMO_PRICE, null);
    }

    /**
     * promoPrice가 스텁된 EventOption Mock.
     * 해피패스에서 Facade가 최종 금액 계산에 사용한다.
     */
    private EventOption mockEventOption() {
        EventOption opt = mock(EventOption.class);
        given(opt.getPromoPrice()).willReturn(PROMO_PRICE);
        return opt;
    }

    /**
     * orderId가 스텁된 Order Mock.
     * markFailed/markUnknown 호출 검증 시 order.getId() 값이 필요하다.
     * 해피패스·포인트 단독 경로는 order.getId()를 호출하지 않으므로 mock(Order.class)를 직접 사용한다.
     */
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
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn(null); // 새 요청 — idem 키 선점 성공
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
            verify(idempotencyStore).setResult(eq(IDEM_KEY), any()); // 성공 결과를 Redis에 캐싱
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
            // Orchestrator가 포인트 차감 후 pgAmount = promoPrice - points 로 반환
            given(paymentOrchestrator.process(cmd, option))
                    .willReturn(new PaymentContext(order, "pg-tx-ref", PaymentMethod.CREDIT_CARD,
                            PROMO_PRICE - points));

            // when
            BookingDto result = bookingFacade.book(cmd);

            // then
            assertThat(result.status()).isEqualTo(OrderStatus.PAID);
            // pgAmount와 pointsToUse가 각각 올바르게 분리되어 markPaid에 전달되어야 함
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
            // 포인트 단독: Orchestrator가 PG 호출 없이 pgTxRef=null, pgMethod=null, pgAmount=0 반환
            given(paymentOrchestrator.process(cmd, option))
                    .willReturn(new PaymentContext(order, null, null, 0));

            // when
            BookingDto result = bookingFacade.book(cmd);

            // then
            assertThat(result.status()).isEqualTo(OrderStatus.PAID);
            // pointsToUse = PROMO_PRICE 전체
            verify(orderService).markPaid(eq(997L), eq(null), eq(null), eq(0L), eq(PROMO_PRICE));
        }
    }

    // =====================
    @Nested
    @DisplayName("멱등성")
    class Idempotency {

        /**
         * 1계층: DB 조회 — Redis TTL 만료·장애 후에도 이미 처리된 PAID 주문이면 그대로 반환.
         * reserve·PG 호출이 일어나지 않아야 이중 결제를 방지할 수 있다.
         */
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

        /**
         * 2계층: Redis SET NX — DB 조회 없이도 성공 결과를 replay.
         * tryAcquire가 JSON 문자열을 반환하면 parse 후 그대로 응답한다.
         */
        @Test
        @DisplayName("Redis Layer 2 replay: JSON 캐시 존재 → reserve/PG 미호출")
        void redisLayerReplay() {
            // given
            BookingDto cachedDto = new BookingDto(200L, OrderStatus.PAID, PROMO_PRICE);
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn("{\"orderId\":200}"); // 이전 성공 결과 캐시
            given(idempotencyStore.isInProgress("{\"orderId\":200}")).willReturn(false);
            given(idempotencyStore.parse("{\"orderId\":200}")).willReturn(Optional.of(cachedDto));

            // when
            BookingDto result = bookingFacade.book(cardCommand());

            // then
            assertThat(result.orderId()).isEqualTo(200L);
            verify(stockService, never()).reserve(anyLong(), anyLong(), anyLong());
            verify(paymentOrchestrator, never()).process(any(), any());
        }

        /**
         * 동일 키로 결제가 이미 진행 중인 경우 (멀티탭·빠른 재클릭 등).
         * reserve를 시도하기 전에 차단해야 Lua 스크립트 호출 낭비를 막는다.
         */
        @Test
        @DisplayName("IN_PROGRESS 중복 요청: DUPLICATE_ENTRY 예외, reserve 미호출")
        void inProgressDuplicate() {
            // given
            given(orderService.findByIdempotencyKey(IDEM_KEY)).willReturn(Optional.empty());
            given(idempotencyStore.tryAcquire(IDEM_KEY)).willReturn("IN_PROGRESS"); // 다른 스레드가 선점 중
            given(idempotencyStore.isInProgress("IN_PROGRESS")).willReturn(true);

            // when & then
            assertThatThrownBy(() -> bookingFacade.book(cardCommand()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_ENTRY);
            verify(stockService, never()).reserve(anyLong(), anyLong(), anyLong());
        }

        /**
         * UNKNOWN 상태는 PG 결과 미확정 동결 상태.
         * 재시도를 허용하면 이중 결제 위험이 있으므로 409로 차단한다.
         */
        @Test
        @DisplayName("UNKNOWN 주문 동일 키 재요청: ORDER_IN_UNKNOWN_STATE 예외")
        void unknownOrderReplay() {
            // given
            Order unknownOrder = mock(Order.class); // getId() 호출 없음 — UNKNOWN 분기에서 즉시 throw
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

        /**
         * SOLD_OUT 시 재고를 선점하지 못했으므로 release는 불필요.
         * idem 키만 해제해 재시도를 허용한다.
         */
        @Test
        @DisplayName("SOLD_OUT: idem 해제 호출, release 미호출")
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

        /**
         * 포인트 사용액이 프로모 가격을 초과하면 pgAmount < 0 → PaymentPolicy가 예외 발생.
         * PG 호출 전 실패이므로 재고 반납 + idem 해제로 재시도를 허용한다.
         */
        @Test
        @DisplayName("포인트 초과 (PAYMENT_AMOUNT_MISMATCH): release + idem 해제 호출")
        void paymentAmountMismatch() {
            // given
            EventOption option = mock(EventOption.class); // getPromoPrice() 호출 없음 — process()에서 throw
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
            // 재고를 선점한 상태에서 실패했으므로 반납 필요
            verify(stockService).release(EVENT_ID, OPTION_ID, USER_ID);
            verify(idempotencyStore).release(IDEM_KEY);
        }
    }

    // =====================
    @Nested
    @DisplayName("결제 오류")
    class PaymentError {

        /**
         * PG 거절은 결과가 확정된 실패.
         * Orchestrator 내부에서 markFailed + 포인트 환불을 처리하고,
         * Facade는 재고 반납 + idem 해제만 담당한다.
         */
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

        /**
         * UNKNOWN은 PG 결과 미확정 동결 상태.
         * 이중 결제 위험이 있으므로 재고·idem 키를 해제하지 않는다.
         * 배치 정산 후 사후 확정된다.
         */
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
            // 동결: 재고·idem 키 해제 금지
            verify(stockService, never()).release(anyLong(), anyLong(), anyLong());
            verify(idempotencyStore, never()).release(anyString());
        }
    }
}

package com.example.booking.payment.orchestrator;

import com.example.booking.order.entity.Order;
import com.example.booking.payment.entity.PaymentMethod;

/**
 * 결제 승인 후 예약 확정에 필요한 컨텍스트.
 * {@link PaymentOrchestrator}가 반환하고 {@link com.example.booking.booking.BookingFacade}가 재고 확정·주문 PAID 처리에 사용한다.
 */
public record PaymentContext(
        Order order,
        String pgTxRef,
        PaymentMethod pgMethod,
        long pgAmount
) {
}

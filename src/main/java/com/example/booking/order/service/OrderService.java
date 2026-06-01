package com.example.booking.order.service;

import com.example.booking.event.entity.EventOption;
import com.example.booking.order.entity.Order;
import com.example.booking.payment.entity.PaymentMethod;

import java.util.Optional;

public interface OrderService {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Order createPending(EventOption eventOption, Long userId, String idempotencyKey,
                        long promoPrice, long pgAmount);

    void markPaid(Long orderId, String pgTxRef, PaymentMethod pgMethod, long pgAmount, long pointsUsed);

    void markFailed(Long orderId, String failReason);

    void markUnknown(Long orderId, String failReason);
}

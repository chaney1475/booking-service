package com.example.booking.order.service;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.event.entity.EventOption;
import com.example.booking.order.dto.OrderDetailDto;
import com.example.booking.order.entity.Order;
import com.example.booking.order.entity.OrderLine;
import com.example.booking.order.repository.OrderLineRepository;
import com.example.booking.order.repository.OrderRepository;
import com.example.booking.payment.entity.Payment;
import com.example.booking.payment.entity.PaymentLine;
import com.example.booking.payment.entity.PaymentMethod;
import com.example.booking.payment.repository.PaymentLineRepository;
import com.example.booking.payment.repository.PaymentRepository;
import com.example.booking.product.entity.RoomOption;
import com.example.booking.user.entity.User;
import com.example.booking.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentLineRepository paymentLineRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return orderRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    @Transactional
    public Order createPending(EventOption eventOption, Long userId, String idempotencyKey,
                               long promoPrice, long pgAmount) {
        // 이전 실패 주문 재사용 — 동일 idempotency_key INSERT 시 UNIQUE 위반 방지 (#7)
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Order order = existing.get();
            order.resetToPending();
            paymentRepository.findByOrderId(order.getId())
                    .ifPresent(p -> p.resetToPending(pgAmount));
            return order;
        }

        User user = userRepository.getReferenceById(userId);
        Order order = new Order(user, idempotencyKey, promoPrice);
        orderRepository.save(order);

        RoomOption roomOption = eventOption.getOption();
        orderLineRepository.save(new OrderLine(
                order, roomOption, eventOption, roomOption.getCheckInDate(), 1, promoPrice));

        paymentRepository.save(new Payment(order, pgAmount));
        return order;
    }

    @Override
    @Transactional
    public void markPaid(Long orderId, String pgTxRef, PaymentMethod pgMethod, long pgAmount, long pointsUsed) {
        Order order = getOrder(orderId);
        order.markPaid();

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BaseException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.approve(pgTxRef);

        if (pgMethod != null && pgAmount > 0) {
            paymentLineRepository.save(new PaymentLine(payment, pgMethod, pgAmount));
        }
        if (pointsUsed > 0) {
            paymentLineRepository.save(new PaymentLine(payment, PaymentMethod.Y_POINT, pointsUsed));
        }
    }

    @Override
    @Transactional
    public void markFailed(Long orderId, String failReason) {
        Order order = getOrder(orderId);
        order.markFailed();
        paymentRepository.findByOrderId(orderId)
                .ifPresent(p -> p.fail(failReason));
    }

    @Override
    @Transactional
    public void markUnknown(Long orderId, String failReason) {
        Order order = getOrder(orderId);
        order.markUnknown();
        paymentRepository.findByOrderId(orderId)
                .ifPresent(p -> p.markUnknown());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailDto findOrderDetail(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BaseException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(userId)) {
            throw new BaseException(ErrorCode.ORDER_NOT_FOUND);
        }

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BaseException(ErrorCode.PAYMENT_NOT_FOUND));

        List<PaymentLine> lines = paymentLineRepository.findByPaymentId(payment.getId());

        long pointsUsed = lines.stream()
                .filter(l -> l.getMethod() == PaymentMethod.Y_POINT)
                .mapToLong(PaymentLine::getAmount)
                .sum();

        PaymentMethod pgMethod = lines.stream()
                .filter(l -> l.getMethod().isPg())
                .map(PaymentLine::getMethod)
                .findFirst()
                .orElse(null);

        long pgAmount = order.getTotalAmount() - pointsUsed;

        return new OrderDetailDto(
                order.getId(),
                order.getStatus(),
                order.getTotalAmount(),
                pgAmount,
                pointsUsed,
                pgMethod
        );
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BaseException(ErrorCode.ORDER_NOT_FOUND));
    }
}

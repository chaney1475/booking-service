package com.example.booking.domain.order.entity;

import com.example.booking.common.entity.BaseEntity;
import com.example.booking.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 80)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // gross: 상품가 = Σorder_line.line_amount = Σpayment_line.amount
    @Column(nullable = false)
    private long totalAmount;

    protected Order() {
    }

    public Order(User user, String idempotencyKey, long totalAmount) {
        this.user = user;
        this.idempotencyKey = idempotencyKey;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PENDING;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public void markFailed() {
        this.status = OrderStatus.FAILED;
    }

    public void markUnknown() {
        this.status = OrderStatus.UNKNOWN;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }
}

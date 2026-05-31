package com.example.booking.domain.payment.entity;

import com.example.booking.common.entity.BaseEntity;
import com.example.booking.domain.order.entity.Order;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "payment")
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // net: PG 청구 현금분 (= totalAmount - 포인트)
    @Column(nullable = false)
    private long amount;

    @Column(length = 100)
    private String pgTxRef;

    @Column(length = 255)
    private String failReason;

    protected Payment() {
    }

    public Payment(Order order, long amount) {
        this.order = order;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    public void approve(String pgTxRef) {
        this.status = PaymentStatus.SUCCESS;
        this.pgTxRef = pgTxRef;
    }

    public void fail(String failReason) {
        this.status = PaymentStatus.FAILED;
        this.failReason = failReason;
    }

    public void markUnknown() {
        this.status = PaymentStatus.UNKNOWN;
    }

    public void refund() {
        this.status = PaymentStatus.REFUNDED;
    }
}

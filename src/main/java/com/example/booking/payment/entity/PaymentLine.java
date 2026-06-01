package com.example.booking.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "payment_line")
public class PaymentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    protected PaymentLine() {
    }

    public PaymentLine(Payment payment, PaymentMethod method, long amount) {
        this.payment = payment;
        this.method = method;
        this.amount = amount;
        this.createdAt = ZonedDateTime.now();
    }
}

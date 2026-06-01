package com.example.booking.point.entity;

import com.example.booking.order.entity.Order;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "point_transaction")
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointTransactionType type;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    protected PointTransaction() {
    }

    public PointTransaction(Long userId, Order order, PointTransactionType type, long amount) {
        this.userId = userId;
        this.order = order;
        this.type = type;
        this.amount = amount;
        this.createdAt = ZonedDateTime.now();
    }
}

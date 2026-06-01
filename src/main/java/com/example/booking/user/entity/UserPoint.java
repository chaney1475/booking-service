package com.example.booking.user.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "user_point")
public class UserPoint {

    @Id
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private long balance;

    private ZonedDateTime updatedAt;

    protected UserPoint() {
    }

    public UserPoint(User user) {
        this.user = user;
        this.balance = 0;
    }

    public void deduct(long amount) {
        if (this.balance < amount) {
            throw new IllegalStateException("포인트 잔액이 부족합니다.");
        }
        this.balance -= amount;
        this.updatedAt = ZonedDateTime.now();   // @PreUpdate 없는 독립 엔티티라 직접 갱신
    }

    public void refund(long amount) {
        this.balance += amount;
        this.updatedAt = ZonedDateTime.now();
    }
}

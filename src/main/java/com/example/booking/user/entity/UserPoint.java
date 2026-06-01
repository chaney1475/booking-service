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

    public UserPoint(User user, long initialBalance) {
        this.user = user;
        this.balance = initialBalance;
    }
}

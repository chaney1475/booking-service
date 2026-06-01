package com.example.booking.domain.order.entity;

import com.example.booking.common.entity.BaseEntity;
import com.example.booking.domain.event.entity.EventOption;
import com.example.booking.domain.product.entity.RoomOption;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "order_line")
public class OrderLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_option_id", nullable = false)
    private RoomOption roomOption;

    // promo 예약이면 채워짐, 일반 예약이면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_option_id")
    private EventOption eventOption;

    @Column(nullable = false)
    private LocalDate checkInDate;

    // check_out_date는 DB Generated Column (check_in_date + nights) — JPA 필드 미매핑
    // DB 범위 쿼리용. Java에서는 checkOutDate() 메서드로 동일한 값을 파생

    @Column(nullable = false)
    private int nights;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private long lineAmount;

    protected OrderLine() {
    }

    public OrderLine(Order order, RoomOption roomOption, EventOption eventOption,
                     LocalDate checkInDate, int nights, long unitPrice) {
        this.order = order;
        this.roomOption = roomOption;
        this.eventOption = eventOption;
        this.checkInDate = checkInDate;
        this.nights = nights;
        this.unitPrice = unitPrice;
        this.lineAmount = unitPrice * nights;
    }

    public LocalDate checkOutDate() {
        return checkInDate.plusDays(nights);
    }

    public boolean isPromo() {
        return eventOption != null;
    }
}

package com.example.booking.domain.event.entity;

import com.example.booking.common.entity.BaseEntity;
import com.example.booking.domain.product.entity.RoomOption;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(
    name = "event_option",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_event_option",
        columnNames = {"event_id", "option_id"}
    )
)
public class EventOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private RoomOption option;

    @Column(nullable = false)
    private long promoPrice;

    // 초기 할당량(=10). 라이브 잔여는 Redis 권위
    @Column(nullable = false)
    private int promoStockTotal;

    protected EventOption() {
    }

    public EventOption(Event event, RoomOption option, long promoPrice, int promoStockTotal) {
        this.event = event;
        this.option = option;
        this.promoPrice = promoPrice;
        this.promoStockTotal = promoStockTotal;
    }
}

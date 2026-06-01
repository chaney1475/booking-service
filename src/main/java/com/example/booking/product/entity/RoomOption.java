package com.example.booking.product.entity;

import com.example.booking.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Getter
@Entity
@Table(
    name = "room_option",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_room_option_product_date",
        columnNames = {"product_id", "check_in_date"}
    )
)
public class RoomOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private LocalDate checkInDate;

    @Column(nullable = false)
    private LocalTime checkInTime;

    @Column(nullable = false)
    private LocalTime checkOutTime;

    @Column(nullable = false)
    private long basePrice;

    @Column(nullable = false)
    private int stock;

    protected RoomOption() {
    }

    public RoomOption(Product product, LocalDate checkInDate,
                      LocalTime checkInTime, LocalTime checkOutTime,
                      long basePrice, int stock) {
        this.product = product;
        this.checkInDate = checkInDate;
        this.checkInTime = checkInTime;
        this.checkOutTime = checkOutTime;
        this.basePrice = basePrice;
        this.stock = stock;
    }

    public LocalDate checkOutDate() {
        return checkInDate.plusDays(1);
    }

    public ZoneId hotelZone() {
        return ZoneId.of(product.getTimezone());
    }
}

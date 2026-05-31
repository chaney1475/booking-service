package com.example.booking.domain.product.entity;

import com.example.booking.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 50)
    private String timezone;  // IANA 타임존 (예: "Asia/Seoul", "Europe/Paris")

    protected Product() {
    }

    public Product(String name, String timezone) {
        this.name = name;
        this.timezone = timezone;
    }
}

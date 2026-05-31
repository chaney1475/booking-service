package com.example.booking.domain.event.entity;

import com.example.booking.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "event")
public class Event extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private ZonedDateTime startsAt;

    @Column(nullable = false)
    private ZonedDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    protected Event() {
    }

    public Event(String name, ZonedDateTime startsAt, ZonedDateTime endsAt) {
        this.name = name;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = EventStatus.SCHEDULED;
    }

    public void open() {
        this.status = EventStatus.OPEN;
    }

    public void close() {
        this.status = EventStatus.CLOSED;
    }
}

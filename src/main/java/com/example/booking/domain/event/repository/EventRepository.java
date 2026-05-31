package com.example.booking.domain.event.repository;

import com.example.booking.domain.event.entity.Event;
import com.example.booking.domain.event.entity.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatusAndStartsAtBefore(EventStatus status, ZonedDateTime threshold);
}

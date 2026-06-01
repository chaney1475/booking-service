package com.example.booking.domain.event.service;

import com.example.booking.domain.event.entity.Event;
import com.example.booking.domain.event.entity.EventStatus;
import com.example.booking.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockSeedService {

    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public List<Event> findEventsToSeed(ZonedDateTime threshold) {
        return eventRepository.findByStatusAndStartsAtBefore(EventStatus.SCHEDULED, threshold);
    }

    // Redis 시드 완료 후 별도 트랜잭션으로 호출 — Redis와 DB가 같은 트랜잭션에 묶이지 않도록
    @Transactional
    public void openEvent(Long eventId) {
        eventRepository.findById(eventId).ifPresent(Event::open);
    }
}

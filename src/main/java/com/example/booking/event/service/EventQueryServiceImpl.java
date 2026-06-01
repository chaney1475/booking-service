package com.example.booking.event.service;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.repository.EventOptionRepository;
import com.example.booking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQueryServiceImpl implements EventQueryService {

    private final EventRepository eventRepository;
    private final EventOptionRepository eventOptionRepository;

    @Override
    public EventOption findOptionWithProduct(Long eventId, Long optionId) {
        if (!eventRepository.existsById(eventId)) {
            throw new BaseException(ErrorCode.EVENT_NOT_FOUND);
        }
        return eventOptionRepository.findByEventIdAndOptionId(eventId, optionId)
                .orElseThrow(() -> new BaseException(ErrorCode.EVENT_OPTION_NOT_FOUND));
    }
}

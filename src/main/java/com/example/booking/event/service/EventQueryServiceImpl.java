package com.example.booking.event.service;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.entity.EventStatus;
import com.example.booking.event.repository.EventOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQueryServiceImpl implements EventQueryService {

    private final EventOptionRepository eventOptionRepository;

    @Override
    @Cacheable(value = "eventOption", key = "#eventId + ':' + #optionId")
    public EventOption findOptionWithProduct(Long eventId, Long optionId) {
        // findByEventIdAndOptionId가 event·option·product를 join fetch하므로 별도 event 조회 불필요
        EventOption eo = eventOptionRepository.findByEventIdAndOptionId(eventId, optionId)
                .orElseThrow(() -> new BaseException(ErrorCode.EVENT_OPTION_NOT_FOUND));
        if (eo.getEvent().getStatus() != EventStatus.OPEN) {
            throw new BaseException(ErrorCode.EVENT_NOT_OPEN);
        }
        return eo;
    }
}

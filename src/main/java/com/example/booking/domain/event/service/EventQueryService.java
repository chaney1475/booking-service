package com.example.booking.domain.event.service;

import com.example.booking.domain.event.entity.EventOption;

public interface EventQueryService {

    EventOption findOptionWithProduct(Long eventId, Long optionId);
}

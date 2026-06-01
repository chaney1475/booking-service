package com.example.booking.event.service;

import com.example.booking.event.entity.EventOption;

public interface EventQueryService {

    EventOption findOptionWithProduct(Long eventId, Long optionId);
}

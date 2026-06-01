package com.example.booking.stock.service;

public interface StockService {

    boolean isAvailable(Long eventId, Long optionId);

    ReserveResult reserve(Long eventId, Long optionId, Long userId);

    void confirm(Long eventId, Long optionId, Long userId);

    void release(Long eventId, Long optionId, Long userId);
}

package com.example.booking.domain.point.service;

public interface UserPointService {

    long getBalance(Long userId);

    void deduct(Long userId, long amount);

    void refund(Long userId, long amount);
}

package com.example.booking.point.service;

import com.example.booking.order.entity.Order;

public interface PointProcessor {

    long getBalance(Long userId);

    void deduct(Long userId, long amount, Order order);

    void refund(Long userId, long amount, Order order);
}

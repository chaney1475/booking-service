package com.example.booking.point.service;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.order.entity.Order;
import com.example.booking.point.entity.PointTransaction;
import com.example.booking.point.entity.PointTransactionType;
import com.example.booking.point.repository.PointTransactionRepository;
import com.example.booking.user.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointProcessorImpl implements PointProcessor {

    private final UserPointRepository userPointRepository;
    private final PointTransactionRepository pointTransactionRepository;

    @Override
    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return userPointRepository.findBalanceByUserId(userId).orElse(0L);
    }

    @Override
    @Transactional
    public void deduct(Long userId, long amount, Order order) {
        if (amount <= 0) return;
        int rows = userPointRepository.deductBalance(userId, amount);
        if (rows == 0) throw new BaseException(ErrorCode.INSUFFICIENT_POINT);
        pointTransactionRepository.save(new PointTransaction(userId, order, PointTransactionType.USE, amount));
    }

    @Override
    @Transactional
    public void refund(Long userId, long amount, Order order) {
        if (amount <= 0) return;
        userPointRepository.refundBalance(userId, amount);
        pointTransactionRepository.save(new PointTransaction(userId, order, PointTransactionType.REFUND, amount));
    }
}

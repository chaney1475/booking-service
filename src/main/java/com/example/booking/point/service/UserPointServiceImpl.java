package com.example.booking.point.service;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.user.entity.UserPoint;
import com.example.booking.user.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserPointServiceImpl implements UserPointService {

    private final UserPointRepository userPointRepository;

    @Override
    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return userPointRepository.findByUserId(userId)
                .map(UserPoint::getBalance)
                .orElse(0L);
    }

    @Override
    @Transactional
    public void deduct(Long userId, long amount) {
        if (amount <= 0) return;
        UserPoint userPoint = userPointRepository.findByUserId(userId)
                .orElseThrow(() -> new BaseException(ErrorCode.INSUFFICIENT_POINT));
        userPoint.deduct(amount);
    }

    @Override
    @Transactional
    public void refund(Long userId, long amount) {
        if (amount <= 0) return;
        userPointRepository.findByUserId(userId)
                .ifPresent(up -> up.refund(amount));
    }
}

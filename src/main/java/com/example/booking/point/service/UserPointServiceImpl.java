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

    /** 포인트 잔액 조회 — 레코드 없는 신규 유저는 0 반환함. */
    @Override
    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return userPointRepository.findByUserId(userId)
                .map(UserPoint::getBalance)
                .orElse(0L);
    }

    /** 포인트 차감 — amount가 0 이하면 포인트 미사용 결제이므로 스킵함. */
    @Override
    @Transactional
    public void deduct(Long userId, long amount) {
        if (amount <= 0) return;
        UserPoint userPoint = userPointRepository.findByUserId(userId)
                .orElseThrow(() -> new BaseException(ErrorCode.INSUFFICIENT_POINT));
        userPoint.deduct(amount);
    }

    /**
     * 포인트 환불 — amount가 0 이하거나 레코드 없으면 스킵함.
     * 레코드 없는 경우 스킵하는 이유: 보상 흐름이 데이터 정합성 이슈로 중단되지 않도록 방어적 처리.
     */
    @Override
    @Transactional
    public void refund(Long userId, long amount) {
        if (amount <= 0) return;
        userPointRepository.findByUserId(userId)
                .ifPresent(up -> up.refund(amount));
    }
}

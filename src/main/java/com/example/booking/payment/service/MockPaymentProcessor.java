package com.example.booking.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

// PG 연동 생략 — 50% APPROVED / 50% REJECTED 랜덤 응답 (실제 구현 시 PaymentGateway 인터페이스로 교체)
@Slf4j
@Component
public class MockPaymentProcessor {

    public PaymentOutcome approve(String merchantUid, long amount) {
        if (Math.random() < 0.5) {
            String pgTxRef = "mock-" + UUID.randomUUID();
            log.info("[MockPG] APPROVED — merchantUid={}, pgTxRef={}", merchantUid, pgTxRef);
            return new PaymentOutcome(true, pgTxRef, null);
        }
        log.info("[MockPG] REJECTED — merchantUid={}", merchantUid);
        return new PaymentOutcome(false, null, "한도 초과");
    }
}

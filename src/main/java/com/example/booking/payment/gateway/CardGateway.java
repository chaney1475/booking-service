package com.example.booking.payment.gateway;

import com.example.booking.payment.entity.PaymentMethod;
import com.example.booking.payment.entity.PgProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 신용카드 PG Gateway (Mock 구현).
 * {@link MockPaymentProperties}의 failureRate / timeoutRate 기반으로 확률적 결과를 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardGateway implements PaymentGateway {

    private final MockPaymentProperties properties;

    @Override
    public PaymentOutcome approve(PaymentCommand command) {
        double roll = Math.random();

        if (roll < properties.failureRate()) {
            log.info("[CardGateway] REJECTED — merchantUid={}", command.merchantUid());
            return PaymentOutcome.rejected("한도 초과");
        }

        if (roll < properties.failureRate() + properties.timeoutRate()) {
            log.warn("[CardGateway] UNKNOWN (타임아웃) — merchantUid={}", command.merchantUid());
            return PaymentOutcome.unknown();
        }

        String pgTxRef = "card-" + UUID.randomUUID();
        log.info("[CardGateway] APPROVED — merchantUid={}, pgTxRef={}", command.merchantUid(), pgTxRef);
        return PaymentOutcome.approved(pgTxRef);
    }

    @Override
    public PaymentOutcome inquire(String merchantUid) {
        log.info("[CardGateway] inquire — merchantUid={} → APPROVED(가정)", merchantUid);
        return PaymentOutcome.approved("card-inquire-" + UUID.randomUUID());
    }

    @Override
    public void refund(String merchantUid) {
        log.info("[CardGateway] refund — merchantUid={}", merchantUid);
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CREDIT_CARD;
    }

    public PgProvider provider() {
        return PgProvider.CARD;
    }
}

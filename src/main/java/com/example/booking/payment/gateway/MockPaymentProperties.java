package com.example.booking.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock PG 동작 설정. application.yml의 {@code payment.mock} 블록에서 바인딩된다.
 *
 * @param failureRate  [0.0, 1.0] 범위. 이 확률로 REJECTED 반환
 * @param timeoutRate  [0.0, 1.0] 범위. 이 확률로 UNKNOWN 반환 (failureRate 판정 후 적용)
 */
@ConfigurationProperties(prefix = "payment.mock")
public record MockPaymentProperties(double failureRate, double timeoutRate) {
}

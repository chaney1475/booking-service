package com.example.booking.payment.entity;

/** PG 제공자 식별자. 각 Gateway 구현체가 담당 Provider를 선언한다. */
public enum PgProvider {
    /** 신용카드 PG */
    CARD,
    /** Y페이 간편결제 PG */
    Y_PAY,
    /** 테스트·개발용 목(Mock) PG */
    MOCK
}

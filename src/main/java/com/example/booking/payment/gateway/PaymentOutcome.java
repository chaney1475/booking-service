package com.example.booking.payment.gateway;

import java.util.UUID;

/**
 * PG 호출 결과를 담는 값 객체.
 * APPROVED / REJECTED / UNKNOWN 세 가지 상태를 표현한다.
 */
public record PaymentOutcome(PaymentOutcomeStatus status, String pgTxRef, String failReason) {

    /** PG 승인 여부 — 재고 확정·주문 PAID 전환 조건 */
    public boolean isApproved() {
        return status == PaymentOutcomeStatus.APPROVED;
    }

    /** PG 결과 미확정 — 주문 동결 후 배치/조회 시 inquire로 확정 */
    public boolean isUnknown() {
        return status == PaymentOutcomeStatus.UNKNOWN;
    }

    /** PG 승인 확정 결과 생성 */
    public static PaymentOutcome approved(String pgTxRef) {
        return new PaymentOutcome(PaymentOutcomeStatus.APPROVED, pgTxRef, null);
    }

    /** PG 명확 거절 결과 생성 (한도 초과 등) */
    public static PaymentOutcome rejected(String reason) {
        return new PaymentOutcome(PaymentOutcomeStatus.REJECTED, null, reason);
    }

    /** PG 결과 미확정 결과 생성 (타임아웃 또는 5xx 후 포기) */
    public static PaymentOutcome unknown() {
        return new PaymentOutcome(PaymentOutcomeStatus.UNKNOWN, null, "PG 결과 미확정");
    }
}

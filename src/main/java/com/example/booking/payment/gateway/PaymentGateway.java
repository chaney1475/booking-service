package com.example.booking.payment.gateway;

import com.example.booking.payment.entity.PaymentMethod;

/**
 * PG(결제 대행사) 연동 인터페이스.
 * 새 결제 수단 추가 시 이 인터페이스를 구현하고 {@link com.example.booking.payment.router.PaymentGatewayRouter}에 등록한다.
 */
public interface PaymentGateway {

    /**
     * PG 승인을 요청한다.
     *
     * @param command PG 호출 파라미터
     * @return APPROVED / REJECTED / UNKNOWN 중 하나
     */
    PaymentOutcome approve(PaymentCommand command);

    /**
     * 결과 미확정(UNKNOWN) 거래를 조회한다. 배치 및 실시간 보정 API에서 사용.
     *
     * @param merchantUid 조회 대상 PG 멱등 키
     * @return APPROVED / REJECTED / UNKNOWN 중 하나
     */
    PaymentOutcome inquire(String merchantUid);

    /**
     * PG 취소(환불)를 요청한다.
     *
     * @param merchantUid 취소 대상 PG 멱등 키
     */
    void refund(String merchantUid);

    /**
     * 이 Gateway가 담당하는 결제 수단을 반환한다. Router가 수단 매핑에 사용한다.
     */
    PaymentMethod method();
}

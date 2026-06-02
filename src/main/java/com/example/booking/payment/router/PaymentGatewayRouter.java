package com.example.booking.payment.router;

import com.example.booking.payment.entity.PaymentMethod;
import com.example.booking.payment.gateway.PaymentGateway;

/**
 * 결제 수단에 따라 적절한 {@link PaymentGateway}를 선택한다.
 * 새 수단 추가 시 Gateway 구현체만 추가하면 되며 이 인터페이스는 수정하지 않는다.
 */
public interface PaymentGatewayRouter {

    /**
     * 지정한 결제 수단을 처리하는 Gateway를 반환한다.
     *
     * @param method PG 결제 수단 (Y_POINT 등 내부 수단은 전달하지 않는다)
     * @return 담당 Gateway
     * @throws com.example.booking.common.exception.BaseException 미지원 수단인 경우
     */
    PaymentGateway route(PaymentMethod method);
}

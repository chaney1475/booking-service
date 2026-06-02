package com.example.booking.payment.router;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.payment.entity.PaymentMethod;
import com.example.booking.payment.gateway.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring이 주입한 {@link PaymentGateway} 목록으로 수단별 Map을 자동 구성한다.
 * 새 Gateway 빈이 추가되면 별도 설정 없이 자동으로 라우팅 대상에 포함된다.
 */
@Slf4j
@Component
public class DefaultPaymentGatewayRouter implements PaymentGatewayRouter {

    private final Map<PaymentMethod, PaymentGateway> gatewayMap;

    public DefaultPaymentGatewayRouter(List<PaymentGateway> gateways) {
        this.gatewayMap = gateways.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentGateway::method, Function.identity()));
        log.info("[PaymentGatewayRouter] 등록된 Gateway: {}", gatewayMap.keySet());
    }

    @Override
    public PaymentGateway route(PaymentMethod method) {
        PaymentGateway gateway = gatewayMap.get(method);
        if (gateway == null) {
            throw new BaseException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
        return gateway;
    }
}

package com.example.booking.payment.orchestrator;

import com.example.booking.booking.command.BookingCommand;
import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import com.example.booking.event.entity.EventOption;
import com.example.booking.payment.entity.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제 수단 조합 규칙을 검증한다.
 * <p>
 * 허용 조합:
 * <ul>
 *   <li>CREDIT_CARD 단독</li>
 *   <li>PAY 단독</li>
 *   <li>Y_POINT 단독</li>
 *   <li>CREDIT_CARD + Y_POINT</li>
 *   <li>PAY + Y_POINT</li>
 * </ul>
 * <p>
 * 불허: CREDIT_CARD + PAY 혼용, PG 금액과 수단 불일치
 */
@Slf4j
@Component
public class PaymentPolicy {

    /**
     * 결제 조합이 유효한지 검증한다.
     *
     * @param command     예약 커맨드 (결제 수단·포인트 사용액 포함)
     * @param eventOption 이벤트 옵션 (프로모션 가격 기준)
     * @throws BaseException 조합 불일치 또는 금액 불일치 시
     */
    public void validate(BookingCommand command, EventOption eventOption) {
        long pointsToUse = command.pointsToUse();
        PaymentMethod method = command.paymentMethod();
        long pgAmount = eventOption.getPromoPrice() - pointsToUse;

        if (pgAmount < 0) {
            throw new BaseException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        // PG 금액이 양수인데 PG 수단이 없거나, 포인트가 아닌 내부 수단이면 불일치
        if (pgAmount > 0 && (method == null || !method.isPg())) {
            throw new BaseException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
        // PG 금액이 0인데 PG 수단이 지정되어 있으면 불일치
        if (pgAmount == 0 && method != null && method.isPg()) {
            throw new BaseException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
    }
}

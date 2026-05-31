package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // Event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    EVENT_NOT_OPEN(HttpStatus.BAD_REQUEST, "현재 오픈된 이벤트가 아닙니다."),
    EVENT_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트 옵션을 찾을 수 없습니다."),

    // Stock
    SOLD_OUT(HttpStatus.CONFLICT, "재고가 모두 소진되었습니다."),
    ALREADY_PURCHASED(HttpStatus.CONFLICT, "이미 구매한 이벤트입니다."),
    DUPLICATE_ENTRY(HttpStatus.CONFLICT, "이미 결제가 진행 중입니다."),

    // Order / Payment
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    DUPLICATE_ORDER(HttpStatus.CONFLICT, "이미 처리된 요청입니다."),
    INVALID_PAYMENT_COMBINATION(HttpStatus.BAD_REQUEST, "결제 수단 조합이 올바르지 않습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 일치하지 않습니다."),
    PAYMENT_REJECTED(HttpStatus.BAD_REQUEST, "결제가 거절되었습니다."),

    // Point
    INSUFFICIENT_POINT(HttpStatus.BAD_REQUEST, "포인트 잔액이 부족합니다."),

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}

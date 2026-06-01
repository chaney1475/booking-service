package com.example.booking.common.exception;

import com.example.booking.common.response.ApiResponse;
import com.example.booking.common.response.ErrorResponse;
import com.example.booking.common.exception.PaymentUnknownException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBaseException(BaseException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.name(), code.getMessage())));
    }

    // DB UNIQUE 제약 위반 — 3계층 멱등성 최후 보루 (Redis 장애·TTL 만료 시 이중 INSERT 차단)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        ErrorCode code = ErrorCode.DUPLICATE_ORDER;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.name(), code.getMessage())));
    }

    @ExceptionHandler(PaymentUnknownException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentUnknown(PaymentUnknownException e) {
        ErrorCode code = ErrorCode.PAYMENT_UNKNOWN;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.name(), code.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(new ErrorResponse(ErrorCode.INVALID_INPUT.name(), message)));
    }
}

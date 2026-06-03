package com.example.booking.common.exception;

import com.example.booking.common.response.ApiResponse;
import com.example.booking.common.response.ErrorResponse;
import com.example.booking.common.exception.PaymentUnknownException;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // MySQL 에러 코드: 1062 = Duplicate key (UNIQUE 위반)
    private static final int MYSQL_DUPLICATE_KEY = 1062;

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBaseException(BaseException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.name(), code.getMessage())));
    }

    /**
     * DB 제약 위반을 에러 종류에 따라 분기한다.
     * - MySQL 1062 (UNIQUE 위반) → DUPLICATE_ORDER: idempotency_key 중복, 3계층 멱등성 최후 보루
     * - 그 외 (FK 위반 1452, NOT NULL 등) → INTERNAL_ERROR: 정상 흐름에서 발생하면 안 되는 케이스
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof SQLIntegrityConstraintViolationException sqe
                && sqe.getErrorCode() == MYSQL_DUPLICATE_KEY) {
            ErrorCode code = ErrorCode.DUPLICATE_ORDER;
            return ResponseEntity
                    .status(code.getStatus())
                    .body(ApiResponse.fail(new ErrorResponse(code.name(), code.getMessage())));
        }
        log.error("[DataIntegrityViolation] UNIQUE 위반 외 제약 오류: {}", cause.getMessage(), e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
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

    // @Validated + @Size 등 메서드 파라미터 제약 위반 (@RequestHeader, @RequestParam)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(new ErrorResponse(ErrorCode.INVALID_INPUT.name(), message)));
    }
}

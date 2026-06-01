package com.example.booking.controller.booking;

import com.example.booking.booking.BookingFacade;
import com.example.booking.booking.dto.BookingDto;
import com.example.booking.common.response.ApiResponse;
import com.example.booking.controller.booking.request.BookingRequest;
import com.example.booking.controller.booking.response.BookingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Booking", description = "예약 및 결제")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/booking")
public class BookingController {

    private final BookingFacade bookingFacade;

    @Operation(
            summary = "예약 및 결제",
            description = "재고 선점 → 결제 → 예약 확정을 수행한다. " +
                          "Idempotency-Key 헤더로 중복 요청을 방지한다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> book(
            @Parameter(description = "사용자 ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @Parameter(description = "클라이언트 발급 멱등키 (UUID 권장)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BookingRequest request
    ) {
        BookingDto dto = bookingFacade.book(request.toCommand(userId, idempotencyKey));
        return ResponseEntity.ok(ApiResponse.ok(BookingResponse.from(dto)));
    }
}

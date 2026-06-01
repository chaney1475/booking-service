package com.example.booking.controller.checkout;

import com.example.booking.checkout.CheckoutFacade;
import com.example.booking.checkout.dto.CheckoutDto;
import com.example.booking.common.response.ApiResponse;
import com.example.booking.controller.checkout.request.CheckoutRequest;
import com.example.booking.controller.checkout.response.CheckoutResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Checkout", description = "예약 전 상품·재고·포인트 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final CheckoutFacade checkoutFacade;

    @Operation(
            summary = "체크아웃 조회",
            description = "이벤트 옵션의 상품 정보, 프로모션 가격, 재고 가용 여부, 사용자 포인트 잔액을 반환함. " +
                          "재고 가용 여부는 GET 시점 힌트이며 실제 점유는 POST /booking 에서 확정됨."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<CheckoutResponse>> getCheckout(
            @ParameterObject @Valid @ModelAttribute CheckoutRequest request,
            @Parameter(description = "사용자 ID", required = true)
            @RequestHeader("X-User-Id") Long userId
    ) {
        CheckoutDto dto = checkoutFacade.getCheckout(request.toCommand(userId));
        return ResponseEntity.ok(ApiResponse.ok(CheckoutResponse.from(dto)));
    }
}

package com.example.booking.domain.checkout.controller;

import com.example.booking.common.response.ApiResponse;
import com.example.booking.domain.checkout.controller.request.CheckoutRequest;
import com.example.booking.domain.checkout.controller.response.CheckoutResponse;
import com.example.booking.domain.checkout.dto.CheckoutDto;
import com.example.booking.domain.checkout.service.CheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final CheckoutService checkoutService;

    @GetMapping
    public ResponseEntity<ApiResponse<CheckoutResponse>> getCheckout(
            @Valid @ModelAttribute CheckoutRequest request,
            @RequestHeader("X-User-Id") Long userId
    ) {
        CheckoutDto dto = checkoutService.getCheckout(request.toCommand(userId));
        return ResponseEntity.ok(ApiResponse.ok(CheckoutResponse.from(dto)));
    }
}

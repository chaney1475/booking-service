package com.example.booking.controller.order;

import com.example.booking.common.response.ApiResponse;
import com.example.booking.controller.order.response.OrderDetailResponse;
import com.example.booking.order.dto.OrderDetailDto;
import com.example.booking.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order", description = "주문 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 상세를 조회한다. 포인트 사용액·PG 결제 금액 등 결제 내역을 포함한다.
     */
    @Operation(summary = "주문 상세 조회", description = "주문 ID로 결제 내역(포인트·PG 금액 등)을 조회한다.")
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> getOrder(
            @Parameter(description = "사용자 ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @Parameter(description = "주문 ID", required = true)
            @PathVariable Long orderId
    ) {
        OrderDetailDto dto = orderService.findOrderDetail(orderId, userId);
        return ResponseEntity.ok(ApiResponse.ok(OrderDetailResponse.from(dto)));
    }
}

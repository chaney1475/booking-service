package com.example.booking.stock.service;

import com.example.booking.common.exception.BaseException;
import com.example.booking.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private static final String CB = "redis";

    // PG timeout(30s) + 여유(60s) — 이 시간 안에 confirm/release 되지 않으면 재고 잠김
    private static final int INFLIGHT_TTL_SECONDS = 90;

    private final StringRedisTemplate redisTemplate;
    private final StockScripts scripts;

    /**
     * 재고 가용 여부 조회 — GET 시점 힌트이며 실제 점유 보장 아님.
     * Redis 장애 시 false(매진 힌트)로 degrade — GET /checkout은 부수효과 없으므로 partial 응답 허용.
     */
    @CircuitBreaker(name = CB, fallbackMethod = "isAvailableFallback")
    @Override
    public boolean isAvailable(Long eventId, Long optionId) {
        String value = (String) redisTemplate.opsForHash().get(stockKey(eventId, optionId), "promo_stock");
        return value != null && Long.parseLong(value) > 0;
    }

    private boolean isAvailableFallback(Long eventId, Long optionId, Throwable t) {
        log.warn("[CircuitBreaker] Redis unavailable — isAvailable degraded: event={} option={} cause={}",
                eventId, optionId, t.getMessage());
        return false;
    }

    /**
     * 재고 선점 — purchased·inflight·stock 세 조건을 Lua로 원자 검사 후 차감함.
     * Redis 장애 시 503 반환 (fail-closed) — 재고 보장 없이 예약 허용 불가.
     */
    @CircuitBreaker(name = CB, fallbackMethod = "reserveFallback")
    @Override
    public ReserveResult reserve(Long eventId, Long optionId, Long userId) {
        String result = redisTemplate.execute(
                scripts.reserve(),
                List.of(
                        purchasedKey(eventId),
                        inflightKey(eventId, optionId, userId),
                        stockKey(eventId, optionId)
                ),
                String.valueOf(userId),
                String.valueOf(INFLIGHT_TTL_SECONDS)
        );
        return ReserveResult.valueOf(result);
    }

    private ReserveResult reserveFallback(Long eventId, Long optionId, Long userId, Throwable t) {
        log.error("[CircuitBreaker] Redis unavailable — reserve rejected: event={} option={} user={} cause={}",
                eventId, optionId, userId, t.getMessage());
        throw new BaseException(ErrorCode.BOOKING_UNAVAILABLE);
    }

    /**
     * 결제 확정 — inflight 제거 후 sold 증가·구매완료 기록을 원자 처리함.
     * Redis 장애 시 로그만 기록 — 주문은 PENDING 잔류, 정산 배치가 사후 처리함.
     */
    @CircuitBreaker(name = CB, fallbackMethod = "confirmFallback")
    @Override
    public void confirm(Long eventId, Long optionId, Long userId) {
        redisTemplate.execute(
                scripts.confirm(),
                List.of(
                        inflightKey(eventId, optionId, userId),
                        stockKey(eventId, optionId),
                        purchasedKey(eventId)
                ),
                String.valueOf(userId)
        );
    }

    private void confirmFallback(Long eventId, Long optionId, Long userId, Throwable t) {
        log.error("[CircuitBreaker] Redis unavailable — confirm skipped, order stays PENDING: event={} option={} user={} cause={}",
                eventId, optionId, userId, t.getMessage());
    }

    /**
     * 재고 반납 — PG 실패·예외 시 inflight 제거 후 promo_stock 복원함.
     * Redis 장애 시 로그만 기록 — under-sell 허용 범위 (DECISIONS.md 쟁점 3 참조).
     */
    @CircuitBreaker(name = CB, fallbackMethod = "releaseFallback")
    @Override
    public void release(Long eventId, Long optionId, Long userId) {
        redisTemplate.execute(
                scripts.release(),
                List.of(
                        inflightKey(eventId, optionId, userId),
                        stockKey(eventId, optionId)
                )
        );
    }

    private void releaseFallback(Long eventId, Long optionId, Long userId, Throwable t) {
        log.error("[CircuitBreaker] Redis unavailable — release skipped, stock slot locked: event={} option={} user={} cause={}",
                eventId, optionId, userId, t.getMessage());
    }

    private String stockKey(Long eventId, Long optionId) {
        return "stock:event:" + eventId + ":option:" + optionId;
    }

    private String inflightKey(Long eventId, Long optionId, Long userId) {
        return "inflight:event:" + eventId + ":option:" + optionId + ":user:" + userId;
    }

    // 이벤트 단위 1인 1구매 — 같은 이벤트 내 다른 옵션도 재구매 차단됨 (DECISIONS.md 쟁점 4 참조)
    private String purchasedKey(Long eventId) {
        return "purchased:event:" + eventId;
    }
}

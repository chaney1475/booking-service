package com.example.booking.stock.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    // PG timeout(30s) + 여유(60s) — 이 시간 안에 confirm/release 되지 않으면 재고 잠김
    private static final int INFLIGHT_TTL_SECONDS = 90;

    private final StringRedisTemplate redisTemplate;
    private final StockScripts scripts;

    /**
     * 재고 가용 여부 조회 — GET 시점 힌트이며 실제 점유 보장 아님.
     * Redis에 키 없으면 미시딩 상태로 간주함.
     */
    @Override
    public boolean isAvailable(Long eventId, Long optionId) {
        String value = (String) redisTemplate.opsForHash().get(stockKey(eventId, optionId), "promo_stock");
        return value != null && Long.parseLong(value) > 0;
    }

    /**
     * 재고 선점 — purchased·inflight·stock 세 조건을 Lua로 원자 검사 후 차감함.
     * KEYS 순서: purchased(이벤트 단위 중복 차단) → inflight(결제진행중 추적) → stock(재고)
     */
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

    /**
     * 결제 확정 — inflight 제거 후 sold 증가·구매완료 기록을 원자 처리함.
     * KEYS 순서: inflight(제거) → stock(sold +1) → purchased(구매완료 영구 기록)
     */
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

    /** 재고 반납 — PG 실패·예외 시 inflight 제거 후 promo_stock 복원함. */
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

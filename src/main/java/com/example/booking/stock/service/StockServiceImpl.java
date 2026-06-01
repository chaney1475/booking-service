package com.example.booking.stock.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private static final int INFLIGHT_TTL_SECONDS = 90;

    private final StringRedisTemplate redisTemplate;
    private final StockScripts scripts;

    @Override
    public boolean isAvailable(Long eventId, Long optionId) {
        String value = (String) redisTemplate.opsForHash().get(stockKey(eventId, optionId), "promo_stock");
        return value != null && Long.parseLong(value) > 0;
    }

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

    private String purchasedKey(Long eventId) {
        return "purchased:event:" + eventId;
    }
}

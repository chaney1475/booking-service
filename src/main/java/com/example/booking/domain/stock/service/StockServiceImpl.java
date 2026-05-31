package com.example.booking.domain.stock.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private static final int INFLIGHT_TTL_SECONDS = 90;

    private final StringRedisTemplate redisTemplate;

    // reserve.lua: 재고 확인 + inflight 등록 (원자적)
    private static final RedisScript<String> RESERVE_SCRIPT = RedisScript.of("""
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return 'ALREADY_PURCHASED'
            end
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 'DUPLICATE_ENTRY'
            end
            local stock = redis.call('HGET', KEYS[3], 'promo_stock')
            if stock == false or tonumber(stock) <= 0 then
                return 'SOLD_OUT'
            end
            redis.call('HINCRBY', KEYS[3], 'promo_stock', -1)
            redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[2]))
            return 'OK'
            """, String.class);

    // confirm.lua: DEL 먼저 → sold+1 (멱등 가드)
    private static final RedisScript<String> CONFIRM_SCRIPT = RedisScript.of("""
            local removed = redis.call('DEL', KEYS[1])
            if removed == 0 then
                return 'ALREADY_CONFIRMED'
            end
            redis.call('HINCRBY', KEYS[2], 'sold', 1)
            redis.call('SADD', KEYS[3], ARGV[1])
            return 'OK'
            """, String.class);

    // release.lua: DEL 후 실제 삭제됐을 때만 promo_stock 복원 (이중 복원 방지)
    private static final RedisScript<String> RELEASE_SCRIPT = RedisScript.of("""
            local removed = redis.call('DEL', KEYS[1])
            if removed == 1 then
                redis.call('HINCRBY', KEYS[2], 'promo_stock', 1)
            end
            return 'OK'
            """, String.class);

    @Override
    public boolean isAvailable(Long eventId, Long optionId) {
        String stockKey = stockKey(eventId, optionId);
        String value = (String) redisTemplate.opsForHash().get(stockKey, "promo_stock");
        if (value == null) return false;
        return Long.parseLong(value) > 0;
    }

    @Override
    public ReserveResult reserve(Long eventId, Long optionId, Long userId) {
        String result = redisTemplate.execute(
                RESERVE_SCRIPT,
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
                CONFIRM_SCRIPT,
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
                RELEASE_SCRIPT,
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

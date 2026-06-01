package com.example.booking.booking.idempotency;

import com.example.booking.booking.dto.BookingDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 키 선점 시도.
     * null  → 새 요청, 진행
     * IN_PROGRESS → 동시 중복, 409
     * JSON  → 이전 성공 결과 replay
     *
     * Redis 예외 시 null 반환 → 진행 허용. reserve.lua도 Redis를 쓰므로 fail-closed가 이미 보장됨.
     * DB UNIQUE가 3계층 최후 보루로 작동.
     */
    public String tryAcquire(String idempotencyKey) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + idempotencyKey, IN_PROGRESS, TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return null;
            }
            return redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        } catch (Exception e) {
            log.warn("[IdempotencyStore] Redis 장애 — idem 체크 스킵, DB UNIQUE가 최후 보루. key={}", idempotencyKey);
            return null;
        }
    }

    public boolean isInProgress(String value) {
        return IN_PROGRESS.equals(value);
    }

    public Optional<BookingDto> parse(String value) {
        if (value == null || IN_PROGRESS.equals(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, BookingDto.class));
        } catch (JsonProcessingException e) {
            log.warn("[IdempotencyStore] parse 실패 — key 무효화 처리. value={}", value);
            return Optional.empty();
        }
    }

    // 결제 확정 후 결과 캐싱 — best-effort, 실패해도 예약은 확정이므로 예외 삼킴
    public void setResult(String idempotencyKey, BookingDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, json, TTL);
        } catch (Exception e) {
            log.error("[IdempotencyStore] setResult 실패 (non-fatal) — key={}", idempotencyKey, e);
        }
    }

    // 실패·소진 등 재시도 허용 케이스: 키 해제
    public void release(String idempotencyKey) {
        try {
            redisTemplate.delete(KEY_PREFIX + idempotencyKey);
        } catch (Exception e) {
            log.warn("[IdempotencyStore] release 실패 (non-fatal) — key={}", idempotencyKey, e);
        }
    }
}

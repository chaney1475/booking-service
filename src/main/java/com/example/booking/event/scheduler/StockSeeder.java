package com.example.booking.event.scheduler;

import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.repository.EventOptionRepository;
import com.example.booking.event.service.StockSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockSeeder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long SEED_AHEAD_MINUTES = 10;

    private final StockSeedService stockSeedService;
    private final EventOptionRepository eventOptionRepository;
    private final StringRedisTemplate redisTemplate;

    // 매 분 정각 실행 — 이벤트 시작 시각과 무관하게 동작 (00시 외 오픈도 자동 처리)
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "stock-seeder", lockAtMostFor = "2m", lockAtLeastFor = "10s")
    public void seedUpcomingEvents() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        ZonedDateTime threshold = now.plusMinutes(SEED_AHEAD_MINUTES);

        List<Event> events = stockSeedService.findEventsToSeed(threshold);
        if (events.isEmpty()) {
            return;
        }

        for (Event event : events) {
            // Redis 호출은 DB 트랜잭션 밖에서 실행
            seedRedis(event);

            // 시작 시각이 지난 이벤트는 OPEN 전환 (재고 없이 열리는 상황 방지)
            if (!event.getStartsAt().isAfter(now)) {
                stockSeedService.openEvent(event.getId());
                log.info("[StockSeeder] Event opened — eventId={}", event.getId());
            }
        }
    }

    private void seedRedis(Event event) {
        List<EventOption> options = eventOptionRepository.findByEventId(event.getId());

        for (EventOption eo : options) {
            String stockKey = "stock:event:" + event.getId() + ":option:" + eo.getOption().getId();

            // HSETNX — 이미 시드된 경우 덮어쓰지 않음 (서버 재시작·중복 실행 안전)
            Boolean seeded = redisTemplate.opsForHash()
                    .putIfAbsent(stockKey, "promo_stock", String.valueOf(eo.getPromoStockTotal()));
            redisTemplate.opsForHash().putIfAbsent(stockKey, "sold", "0");

            if (Boolean.TRUE.equals(seeded)) {
                log.info("[StockSeeder] Seeded — eventId={}, optionId={}, stock={}",
                        event.getId(), eo.getOption().getId(), eo.getPromoStockTotal());
            }
        }
    }
}

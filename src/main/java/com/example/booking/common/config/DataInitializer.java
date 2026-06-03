package com.example.booking.common.config;

import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.repository.EventOptionRepository;
import com.example.booking.event.repository.EventRepository;
import com.example.booking.event.service.StockSeedService;
import com.example.booking.product.entity.Product;
import com.example.booking.product.entity.RoomOption;
import com.example.booking.product.repository.ProductRepository;
import com.example.booking.product.repository.RoomOptionRepository;
import com.example.booking.user.entity.User;
import com.example.booking.user.entity.UserPoint;
import com.example.booking.user.repository.UserPointRepository;
import com.example.booking.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

// local 프로파일에서만 동작 — prod 환경에서 시드 데이터 삽입 방지
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int PROMO_STOCK = 10;

    private final UserRepository userRepository;
    private final UserPointRepository userPointRepository;
    private final ProductRepository productRepository;
    private final RoomOptionRepository roomOptionRepository;
    private final EventRepository eventRepository;
    private final EventOptionRepository eventOptionRepository;
    private final StockSeedService stockSeedService;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("[DataInitializer] skipped — data already exists");
            return;
        }

        // 유저 1000명 생성 — k6 1000 VU 테스트 대응 (재고 10 << 유저 1000, 경합 성립)
        for (int i = 1; i <= 1000; i++) {
            User u = userRepository.save(new User("테스트유저" + i));
            userPointRepository.save(new UserPoint(u, 10_000L));
        }

        ZonedDateTime now = ZonedDateTime.now(KST);

        // 이벤트 1~3: StockSeeder 크론 없이 즉시 Redis 시드 + OPEN — 바로 예약 가능
        seedAndOpen(createEvent("디럭스 오션뷰",   now.minusSeconds(1), LocalDate.of(2026, 6, 15), 80_000L, 50_000L));
        seedAndOpen(createEvent("스탠다드 가든뷰", now.minusSeconds(1), LocalDate.of(2026, 6, 20), 60_000L, 40_000L));
        seedAndOpen(createEvent("패밀리 스위트",   now.minusSeconds(1), LocalDate.of(2026, 6, 25), 120_000L, 90_000L));

        // 이벤트 4: 2분 후 오픈 — EVENT_NOT_OPEN → OPEN 전환 확인용 (StockSeeder가 감지해 처리)
        createEvent("프리미엄 스위트", now.plusMinutes(2), LocalDate.of(2026, 7, 1), 150_000L, 100_000L);

        log.info("[DataInitializer] done — users: 1000명 (각 10,000포인트), events: 4개 (3개 즉시 OPEN, 1개 2분 후 OPEN)");
    }

    private Event createEvent(String productName, ZonedDateTime startsAt,
                              LocalDate checkInDate, long basePrice, long promoPrice) {
        Product product = productRepository.save(new Product(productName, "Asia/Seoul"));

        RoomOption option = roomOptionRepository.save(new RoomOption(
                product,
                checkInDate,
                LocalTime.of(15, 0),
                LocalTime.of(11, 0),
                basePrice,
                PROMO_STOCK
        ));

        Event event = eventRepository.save(
                new Event(productName + " 특가", startsAt, startsAt.plusMinutes(30)));

        EventOption eo = eventOptionRepository.save(new EventOption(event, option, promoPrice, PROMO_STOCK));

        log.info("[DataInitializer] eventId={} optionId={} startsAt={}",
                event.getId(), eo.getId(), startsAt);
        return event;
    }

    private void seedAndOpen(Event event) {
        List<EventOption> options = eventOptionRepository.findByEventId(event.getId());
        for (EventOption eo : options) {
            String key = "stock:event:" + event.getId() + ":option:" + eo.getOption().getId();
            redisTemplate.opsForHash().putIfAbsent(key, "promo_stock", String.valueOf(eo.getPromoStockTotal()));
            redisTemplate.opsForHash().putIfAbsent(key, "sold", "0");
        }
        stockSeedService.openEvent(event.getId());
    }
}

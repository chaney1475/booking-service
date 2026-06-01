package com.example.booking.common.config;

import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventOption;
import com.example.booking.event.repository.EventOptionRepository;
import com.example.booking.event.repository.EventRepository;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("[DataInitializer] skipped — data already exists");
            return;
        }

        // 유저 20명 생성 — 재고(10) < 유저(20)이어야 경합 테스트 성립
        for (int i = 1; i <= 20; i++) {
            User u = userRepository.save(new User("테스트유저" + i));
            userPointRepository.save(new UserPoint(u, 10_000L));
        }

        ZonedDateTime now = ZonedDateTime.now(KST);

        // 이벤트 3개 — 6/7/8분 후 시작. 모두 seeder 윈도우(10분) 안에 있어 첫 틱에 시드됨
        createEvent("디럭스 오션뷰",   now.plusMinutes(6), LocalDate.of(2026, 6, 15), 80_000L, 50_000L);
        createEvent("스탠다드 가든뷰", now.plusMinutes(7), LocalDate.of(2026, 6, 20), 60_000L, 40_000L);
        createEvent("패밀리 스위트",   now.plusMinutes(8), LocalDate.of(2026, 6, 25), 120_000L, 90_000L);

        log.info("[DataInitializer] done — users: 20명 (각 10,000포인트), events: 3개 (startsAt = now+6m/+7m/+8m)");
        log.info("[DataInitializer] StockSeeder가 다음 정각에 셋 다 감지 → Redis 시드 예정");
    }

    private void createEvent(String productName, ZonedDateTime startsAt,
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
    }
}

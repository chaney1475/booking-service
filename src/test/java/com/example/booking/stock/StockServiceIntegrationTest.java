package com.example.booking.stock;

import com.example.booking.stock.service.ReserveResult;
import com.example.booking.stock.service.StockScripts;
import com.example.booking.stock.service.StockServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockServiceImpl + Lua 스크립트(reserve / confirm / release) 통합 테스트.
 *
 * <p>Mock 없이 실제 Redis 7 컨테이너를 사용해 Lua 스크립트의 원자성·멱등성·동시성을 검증한다.
 * 핵심 리스크인 oversell, 중복 구매, release 과복원을 직접 확인하는 것이 목적이다.</p>
 *
 * <p>Spring 컨텍스트 없이 StockServiceImpl을 직접 생성한다.
 * @CircuitBreaker AOP 프록시를 거치지 않지만, 이 테스트의 관심사는 Lua 스크립트 로직이므로 허용한다.</p>
 */
@Testcontainers
class StockServiceIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    static StringRedisTemplate redisTemplate;
    static StockServiceImpl stockService;

    private static final Long EVENT_ID    = 1L;
    private static final Long OPTION_ID   = 10L;
    private static final int  INITIAL_STOCK = 10; // 프로모 재고: 테스트 전 항상 이 값으로 초기화

    // =====================
    // 키 생성 — StockServiceImpl 내부 규칙과 완전히 동일해야 함
    // =====================

    private String stockKey()               { return "stock:event:" + EVENT_ID + ":option:" + OPTION_ID; }
    private String inflightKey(Long userId) { return "inflight:event:" + EVENT_ID + ":option:" + OPTION_ID + ":user:" + userId; }
    private String purchasedKey()           { return "purchased:event:" + EVENT_ID; }

    // =====================
    // Redis 상태 직접 조회 헬퍼 — Mock이 아닌 실제 값 검증
    // =====================

    /** stock HASH의 promo_stock 필드 값 */
    private long promoStock() {
        String val = (String) redisTemplate.opsForHash().get(stockKey(), "promo_stock");
        return val == null ? 0L : Long.parseLong(val);
    }

    /** stock HASH의 sold 필드 값 */
    private long soldCount() {
        String val = (String) redisTemplate.opsForHash().get(stockKey(), "sold");
        return val == null ? 0L : Long.parseLong(val);
    }

    /** inflight 키 존재 여부 (결제 진행 중 여부) */
    private boolean inflightExists(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(inflightKey(userId)));
    }

    /** purchased SET에 userId 포함 여부 (구매 완료 여부) */
    private boolean isPurchased(Long userId) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(purchasedKey(), String.valueOf(userId)));
    }

    // =====================
    // 셋업
    // =====================

    /**
     * 컨테이너 기동 후 1회만 실행.
     * LettuceConnectionFactory와 StockServiceImpl을 공유 인스턴스로 생성한다.
     */
    @BeforeAll
    static void setUpOnce() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();

        // Lua 스크립트는 실제 파일을 로드 — ClassPathResource가 src/main/resources/lua/*.lua를 찾는다
        StockScripts scripts = new StockScripts(
                RedisScript.of(new ClassPathResource("lua/reserve.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/confirm.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/release.lua"), String.class)
        );
        stockService = new StockServiceImpl(redisTemplate, scripts);
    }

    /** 각 테스트 전 Redis를 초기화하고 재고 10개로 시드 */
    @BeforeEach
    void flushAndSeed() {
        redisTemplate.execute((RedisCallback<Void>) conn -> {
            conn.serverCommands().flushAll();
            return null;
        });
        redisTemplate.opsForHash().put(stockKey(), "promo_stock", String.valueOf(INITIAL_STOCK));
        redisTemplate.opsForHash().put(stockKey(), "sold", "0");
    }

    // =====================
    @Nested
    @DisplayName("reserve.lua")
    class Reserve {

        @Test
        @DisplayName("정상: promo_stock -1, inflight 생성")
        void ok() {
            // given
            Long userId = 1L;

            // when
            ReserveResult result = stockService.reserve(EVENT_ID, OPTION_ID, userId);

            // then
            assertThat(result).isEqualTo(ReserveResult.OK);
            assertThat(promoStock()).isEqualTo(INITIAL_STOCK - 1); // 재고 1 차감
            assertThat(inflightExists(userId)).isTrue();            // 결제 진행 마커 생성
        }

        @Test
        @DisplayName("SOLD_OUT: promo_stock 0일 때 재고 변화 없음")
        void soldOut() {
            // given: 재고 0으로 강제 설정
            redisTemplate.opsForHash().put(stockKey(), "promo_stock", "0");
            Long userId = 1L;

            // when
            ReserveResult result = stockService.reserve(EVENT_ID, OPTION_ID, userId);

            // then
            assertThat(result).isEqualTo(ReserveResult.SOLD_OUT);
            assertThat(promoStock()).isZero();               // 음수로 내려가지 않아야 함
            assertThat(inflightExists(userId)).isFalse();   // inflight 생성 안 됨
        }

        @Test
        @DisplayName("ALREADY_PURCHASED: 동일 이벤트 재구매 차단, 재고 변화 없음")
        void alreadyPurchased() {
            // given: purchased SET에 userId를 미리 등록 (이전 구매 완료 상태 시뮬레이션)
            Long userId = 1L;
            redisTemplate.opsForSet().add(purchasedKey(), String.valueOf(userId));

            // when
            ReserveResult result = stockService.reserve(EVENT_ID, OPTION_ID, userId);

            // then: 재고 차감 없이 차단
            assertThat(result).isEqualTo(ReserveResult.ALREADY_PURCHASED);
            assertThat(promoStock()).isEqualTo(INITIAL_STOCK);
            assertThat(inflightExists(userId)).isFalse();
        }

        @Test
        @DisplayName("DUPLICATE_ENTRY: inflight 이미 존재하면 재고 변화 없음")
        void duplicateEntry() {
            // given: inflight 키를 미리 생성 (동일 유저의 결제가 이미 진행 중인 상태)
            Long userId = 1L;
            redisTemplate.opsForValue().set(inflightKey(userId), "1");

            // when
            ReserveResult result = stockService.reserve(EVENT_ID, OPTION_ID, userId);

            // then: 재고 차감 없이 차단
            assertThat(result).isEqualTo(ReserveResult.DUPLICATE_ENTRY);
            assertThat(promoStock()).isEqualTo(INITIAL_STOCK);
        }
    }

    // =====================
    @Nested
    @DisplayName("confirm.lua")
    class Confirm {

        @Test
        @DisplayName("정상: purchased 등록 + inflight 제거 + sold 1 증가")
        void ok() {
            // given: reserve로 inflight 생성
            Long userId = 1L;
            stockService.reserve(EVENT_ID, OPTION_ID, userId);

            // when
            stockService.confirm(EVENT_ID, OPTION_ID, userId);

            // then
            assertThat(inflightExists(userId)).isFalse();  // 결제 진행 마커 제거
            assertThat(isPurchased(userId)).isTrue();       // 구매 완료 영구 기록 (이후 재구매 차단에 사용)
            assertThat(soldCount()).isEqualTo(1);           // 실제 판매 수 집계
        }
    }

    // =====================
    @Nested
    @DisplayName("release.lua")
    class Release {

        @Test
        @DisplayName("정상: promo_stock +1, inflight 제거")
        void ok() {
            // given: reserve로 재고 1 차감
            Long userId = 1L;
            stockService.reserve(EVENT_ID, OPTION_ID, userId);
            assertThat(promoStock()).isEqualTo(INITIAL_STOCK - 1); // reserve 후 재고 확인

            // when
            stockService.release(EVENT_ID, OPTION_ID, userId);

            // then
            assertThat(promoStock()).isEqualTo(INITIAL_STOCK); // 재고 복원
            assertThat(inflightExists(userId)).isFalse();       // inflight 제거
        }

        /**
         * release 이중 호출 멱등성.
         * DEL 반환값이 0이면 재고를 복원하지 않으므로 promo_stock이 초기값을 초과하지 않는다.
         */
        @Test
        @DisplayName("이중 호출: promo_stock 1회만 복원 (멱등)")
        void idempotent() {
            // given
            Long userId = 1L;
            stockService.reserve(EVENT_ID, OPTION_ID, userId);

            // when: 동일 키에 release 두 번 호출
            stockService.release(EVENT_ID, OPTION_ID, userId);
            stockService.release(EVENT_ID, OPTION_ID, userId);

            // then: 과복원 없이 초기 재고와 동일
            assertThat(promoStock()).isEqualTo(INITIAL_STOCK);
        }
    }

    // =====================
    @Nested
    @DisplayName("동시성")
    class Concurrency {

        /**
         * 재고 10개에 50명이 동시에 reserve를 시도하는 시나리오.
         * Lua 스크립트의 원자성이 보장되면 정확히 10명만 OK를 받아야 한다.
         *
         * <p>CountDownLatch(start)로 모든 스레드를 동시에 출발시켜 경쟁 조건을 최대화한다.</p>
         */
        @Test
        @DisplayName("재고 10개에 50명 동시 reserve: 정확히 10명만 성공, 나머지 40명 실패, promo_stock 0 미만 불가")
        void onlyTenSucceed() throws InterruptedException {
            // given
            int totalUsers = 50;
            CountDownLatch ready = new CountDownLatch(totalUsers); // 모든 스레드 준비 대기
            CountDownLatch start = new CountDownLatch(1);          // 동시 출발 신호
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount    = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(totalUsers);

            for (int i = 0; i < totalUsers; i++) {
                long userId = i + 1L; // userId 1~50 (서로 다른 유저 — DUPLICATE_ENTRY 방지)
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(); // 모든 스레드가 준비될 때까지 대기 후 동시 출발
                        ReserveResult result = stockService.reserve(EVENT_ID, OPTION_ID, userId);
                        if (result == ReserveResult.OK) {
                            successCount.incrementAndGet();
                        } else {
                            // SOLD_OUT 또는 DUPLICATE_ENTRY — 정상적인 실패
                            failCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // when: 모든 스레드가 준비되면 동시 출발
            ready.await();
            start.countDown();
            executor.shutdown();
            boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

            // then
            assertThat(finished).as("10초 안에 모든 스레드가 완료되어야 함").isTrue();
            assertThat(successCount.get()).isEqualTo(INITIAL_STOCK);              // 정확히 10명만 성공
            assertThat(failCount.get()).isEqualTo(totalUsers - INITIAL_STOCK);    // 40명 실패 (예외 없이 응답 수신 보장)
            assertThat(promoStock()).isZero();                                     // 재고 정확히 소진, 음수 불가
        }
    }
}

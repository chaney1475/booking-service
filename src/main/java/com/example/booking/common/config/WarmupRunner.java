package com.example.booking.common.config;

import com.example.booking.event.repository.EventOptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 콜드 스타트 방지 워밍업.
 * ApplicationReadyEvent 시점에 자신에게 병렬 HTTP 요청을 보내
 * JVM JIT / HikariCP 풀 / Lettuce 연결 / Tomcat 스레드 풀을 한 번에 초기화한다.
 *
 * 순차 호출은 Tomcat 스레드 1개만 재사용되어 스레드 풀이 달궈지지 않는다.
 * 병렬 호출로 실제 동시 트래픽과 유사한 조건을 만들어 스레드 풀을 미리 확장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmupRunner {

    private static final int CONCURRENCY  = 50; // 동시 호출 수 — Tomcat 스레드 풀 확장 트리거
    private static final int TOTAL_CALLS  = 100; // 총 호출 수 — JIT C1 컴파일 임계치 도달용

    private final EventOptionRepository eventOptionRepository;
    private final WebServerApplicationContext webServerContext;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        var options = eventOptionRepository.findAll();
        if (options.isEmpty()) {
            log.info("[Warmup] 이벤트 옵션 없음 — 워밍업 생략");
            return;
        }

        var eo      = options.get(0);
        long eventId  = eo.getEvent().getId();
        long optionId = eo.getOption().getId();
        int  port     = webServerContext.getWebServer().getPort();

        String url = "http://localhost:" + port + "/api/checkout?eventId=" + eventId + "&optionId=" + optionId;
        log.info("[Warmup] 시작 — {} (동시 {}개 × {}회)", url, CONCURRENCY, TOTAL_CALLS / CONCURRENCY);
        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < TOTAL_CALLS; i++) {
            final int userId = (i % CONCURRENCY) + 1;
            futures.add(pool.submit(() -> {
                try {
                    RestTemplate restTemplate = new RestTemplate();
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("X-User-Id", String.valueOf(userId));
                    var res = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    if (res.getStatusCode().is2xxSuccessful()) success.incrementAndGet();
                } catch (Exception e) {
                    // 워밍업 실패는 무시
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }

        pool.shutdown();
        log.info("[Warmup] 완료 — {}ms, 성공 {}/{}", System.currentTimeMillis() - start, success.get(), TOTAL_CALLS);
    }
}

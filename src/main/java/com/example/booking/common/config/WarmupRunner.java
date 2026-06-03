package com.example.booking.common.config;

import com.example.booking.event.entity.EventOption;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * 콜드 스타트 방지 워밍업.
 *
 * <p>목적: JVM JIT / HikariCP 풀 / Lettuce 연결 / Tomcat 스레드 풀 초기화.
 * 특정 데이터를 미리 올리려는 게 아니라, 실제 트래픽과 유사한 HTTP 호출로 웜업을 한다.
 *
 * <p>전략: 사용 가능한 이벤트 옵션 목록으로 checkout API를 동시에 호출한다.
 * 동시 호출이어야 Tomcat 스레드 풀이 확장되고 Hikari 커넥션이 열린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmupRunner {

    private static final int CONCURRENCY = 50;
    private static final int ROUNDS      = 3;  // 총 호출 = CONCURRENCY × ROUNDS = 150

    private final EventOptionRepository eventOptionRepository;
    private final WebServerApplicationContext webServerContext;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        List<EventOption> options = eventOptionRepository.findAll();
        if (options.isEmpty()) {
            log.info("[Warmup] 이벤트 옵션 없음 — 워밍업 생략");
            return;
        }

        int port = webServerContext.getWebServer().getPort();
        String baseUrl = "http://localhost:" + port;
        int total = CONCURRENCY * ROUNDS;

        log.info("[Warmup] 시작 — 동시 {}개 × {}라운드 = {}회", CONCURRENCY, ROUNDS, total);
        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        RestTemplate restTemplate = new RestTemplate();

        List<Future<?>> futures = IntStream.range(0, total)
                .<Future<?>>mapToObj(i -> {
                    // 라운드별로 다른 이벤트 옵션 사용 — 사용 가능한 옵션 순환
                    EventOption eo = options.get(i % options.size());
                    long eventId  = eo.getEvent().getId();
                    long optionId = eo.getOption().getId();
                    int  userId   = (i % CONCURRENCY) + 1;

                    String url = baseUrl + "/api/checkout?eventId=" + eventId + "&optionId=" + optionId;
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("X-User-Id", String.valueOf(userId));
                    HttpEntity<Void> req = new HttpEntity<>(headers);

                    return pool.submit(() -> {
                        try {
                            var res = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
                            if (res.getStatusCode().is2xxSuccessful()) success.incrementAndGet();
                        } catch (Exception ignored) {}
                    });
                })
                .toList();

        futures.forEach(f -> { try { f.get(); } catch (Exception ignored) {} });
        pool.shutdown();

        log.info("[Warmup] 완료 — {}ms, 성공 {}/{}", System.currentTimeMillis() - start, success.get(), total);
    }
}

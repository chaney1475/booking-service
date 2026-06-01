package com.example.booking.common.config;

import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
// defaultLockAtMostFor: 서버 크래시 시 2분 후 락 자동 해제 — 다른 서버가 다음 틱에 이어받을 수 있도록
@EnableSchedulerLock(defaultLockAtMostFor = "2m")
public class ShedLockConfig {

    @Bean
    public RedisLockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }
}

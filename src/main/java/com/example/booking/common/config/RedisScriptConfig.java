package com.example.booking.common.config;

import com.example.booking.stock.service.StockScripts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScriptConfig {

    /**
     * Lua 스크립트 3개를 record로 묶어 단일 빈으로 등록함.
     * 같은 타입(RedisScript<String>)이 여러 개라 @Qualifier 없이 StockServiceImpl에 주입 가능하도록 래핑함.
     */
    @Bean
    public StockScripts stockScripts() {
        return new StockScripts(
                RedisScript.of(new ClassPathResource("lua/reserve.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/confirm.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/release.lua"), String.class)
        );
    }
}

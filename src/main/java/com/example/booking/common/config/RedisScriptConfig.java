package com.example.booking.common.config;

import com.example.booking.stock.service.StockScripts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScriptConfig {

    @Bean
    public StockScripts stockScripts() {
        return new StockScripts(
                RedisScript.of(new ClassPathResource("lua/reserve.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/confirm.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/release.lua"), String.class)
        );
    }
}

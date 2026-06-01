package com.example.booking.stock.service;

import org.springframework.data.redis.core.script.RedisScript;

public record StockScripts(
        RedisScript<String> reserve,
        RedisScript<String> confirm,
        RedisScript<String> release
) {}

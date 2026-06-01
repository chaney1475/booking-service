package com.example.booking.event.entity;

public enum EventStatus {
    /** 오픈 전 — StockSeeder가 오픈 시각 10분 전부터 Redis 재고 시딩 대기 */
    SCHEDULED,
    /** 예약 가능 — Redis 재고 시딩 완료, reserve.lua 진입 허용 */
    OPEN,
    /** 마감 — 재고 소진 또는 오픈 시간 종료 */
    CLOSED
}

-- release.lua — PG 실패·예외 시 재고 반납 (원자적)
--
-- KEYS[1] = inflight:event:{eventId}:option:{optionId}:user:{userId}
-- KEYS[2] = stock:event:{eventId}:option:{optionId}

-- DEL 반환값이 1일 때만 재고 복원 — 이중 호출 시 promo_stock 과복원 방지
local removed = redis.call('DEL', KEYS[1])
if removed == 1 then
    redis.call('HINCRBY', KEYS[2], 'promo_stock', 1)
end
return 'OK'

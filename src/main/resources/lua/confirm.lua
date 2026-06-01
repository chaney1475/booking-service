-- confirm.lua — PG 승인 후 최종 확정 (원자적)
--
-- KEYS[1] = inflight:event:{eventId}:option:{optionId}:user:{userId}
-- KEYS[2] = stock:event:{eventId}:option:{optionId}
-- KEYS[3] = purchased:event:{eventId}
-- ARGV[1] = userId

-- DEL 먼저 → 실제 삭제됐을 때만 확정 (멱등 가드: 이중 confirm 방지)
local removed = redis.call('DEL', KEYS[1])
if removed == 0 then
    return 'ALREADY_CONFIRMED'
end

redis.call('HINCRBY', KEYS[2], 'sold', 1)
redis.call('SADD', KEYS[3], ARGV[1])  -- 구매완료 영구 기록 (이후 재시도 차단)
return 'OK'

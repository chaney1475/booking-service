-- reserve.lua — 재고 선점 (원자적)
--
-- KEYS[1] = purchased:event:{eventId}                          — 구매완료 SET (이벤트 단위 1인 1구매)
-- KEYS[2] = inflight:event:{eventId}:option:{optionId}:user:{userId}  — 결제진행중 키
-- KEYS[3] = stock:event:{eventId}:option:{optionId}            — 재고 HASH { promo_stock, sold }
-- ARGV[1] = userId
-- ARGV[2] = inflightTtlSeconds

-- 1) 동일 이벤트 구매 완료 차단 (이벤트 단위 1인 1구매)
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
    return 'ALREADY_PURCHASED'
end

-- 2) 결제 진행 중인 동일 유저 차단 (멀티탭·중복 요청)
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 'DUPLICATE_ENTRY'
end

-- 3) 재고 확인
local stock = redis.call('HGET', KEYS[3], 'promo_stock')
if stock == false or tonumber(stock) <= 0 then
    return 'SOLD_OUT'
end

-- 모두 통과 → 재고 차감 + inflight 등록
redis.call('HINCRBY', KEYS[3], 'promo_stock', -1)
redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[2]))
return 'OK'

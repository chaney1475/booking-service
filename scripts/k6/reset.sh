#!/usr/bin/env bash
# 시나리오 간 Redis + DB 상태 초기화
# 사용법: ./scripts/k6/reset.sh [EVENT_ID] [OPTION_ID]
#   ./scripts/k6/reset.sh        # 기본값: eventId=1, optionId=1
#   ./scripts/k6/reset.sh 2 3    # eventId=2, optionId=3

set -e

EVENT_ID=${1:-1}
OPTION_ID=${2:-1}

REDIS="docker compose exec -T redis redis-cli"
MYSQL="docker compose exec -T mysql mysql -uroot -proot booking"

echo "[Reset] Redis 초기화 — event:${EVENT_ID}, option:${OPTION_ID}"

# 재고 해시: promo_stock / sold 복원 (total은 기존값 그대로 사용)
TOTAL=$($REDIS HGET "stock:event:${EVENT_ID}:option:${OPTION_ID}" total)
[ -z "$TOTAL" ] && TOTAL=10
$REDIS HMSET "stock:event:${EVENT_ID}:option:${OPTION_ID}" promo_stock "$TOTAL" sold 0
echo "  stock: promo_stock=${TOTAL}, sold=0"

# purchased SET 초기화
$REDIS DEL "purchased:event:${EVENT_ID}" > /dev/null
echo "  purchased:event:${EVENT_ID} cleared"

# inflight 키 일괄 삭제
INFLIGHT=$($REDIS KEYS "inflight:event:${EVENT_ID}:option:${OPTION_ID}:user:*")
if [ -n "$INFLIGHT" ]; then
  COUNT=$(echo "$INFLIGHT" | wc -l | tr -d ' ')
  echo "$INFLIGHT" | xargs $REDIS DEL > /dev/null
  echo "  inflight keys cleared: ${COUNT}개"
fi

# idempotency 키 일괄 삭제
IDEM=$($REDIS KEYS "idem:*")
if [ -n "$IDEM" ]; then
  COUNT=$(echo "$IDEM" | wc -l | tr -d ' ')
  echo "$IDEM" | xargs $REDIS DEL > /dev/null
  echo "  idem keys cleared: ${COUNT}개"
fi

echo "[Reset] DB 초기화"

$MYSQL -e "
  SET FOREIGN_KEY_CHECKS=0;
  TRUNCATE TABLE payment_line;
  TRUNCATE TABLE payment;
  TRUNCATE TABLE point_transaction;
  TRUNCATE TABLE order_line;
  TRUNCATE TABLE orders;
  UPDATE user_point SET balance = 10000;
  SET FOREIGN_KEY_CHECKS=1;
" 2>/dev/null
echo "  orders / payment / payment_line / point_transaction / order_line truncated"
echo "  user_point balance reset to 10000"

echo "[Reset] 완료"

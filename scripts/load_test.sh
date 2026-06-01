#!/usr/bin/env bash
# load_test.sh — 동시 부하 테스트 (배리어 기반 thundering herd)
#
# 사용법: ./scripts/load_test.sh [eventId] [optionId]
# 기본값: eventId=1, optionId=1

set -uo pipefail

BASE_URL="http://localhost:8080"
EVENT_ID="${1:-1}"
OPTION_ID="${2:-1}"
RESULTS_DIR="/tmp/booking_test_$$"
RUN_ID=$(python3 -c "import uuid; print(uuid.uuid4().hex[:8])")
MYSQL_CMD="docker exec booking-mysql mysql -uroot -proot booking -N -B"
REDIS_CMD="docker exec booking-redis redis-cli"

mkdir -p "$RESULTS_DIR"

ms() { python3 -c "import time; print(int(time.time()*1000))"; }

# ─────────────────────────────────────────────
# 0. 유저 목록 + 재고 사전 검증
# ─────────────────────────────────────────────
echo "=== [0] 사전 검증 (RUN_ID=$RUN_ID) ==="

USER_IDS=$($MYSQL_CMD -e "SELECT id FROM users ORDER BY id;" 2>/dev/null | tr -d ' ' | grep -v '^$')
USER_COUNT=$(echo "$USER_IDS" | grep -c .)
echo "유저 수: ${USER_COUNT}명"

STOCK=$($REDIS_CMD HGET "stock:event:${EVENT_ID}:option:${OPTION_ID}" promo_stock 2>/dev/null || echo "nil")
echo "현재 promo_stock: ${STOCK}"

if [ "$STOCK" = "nil" ] || [ "$STOCK" = "" ]; then
  echo "ERROR: Redis에 재고 키가 없습니다. StockSeeder가 아직 실행되지 않았거나 이벤트 ID가 잘못됐습니다."
  exit 1
fi

if [ "${USER_COUNT}" -le "${STOCK}" ]; then
  echo "WARNING: 유저 수(${USER_COUNT})가 재고(${STOCK})보다 작습니다. 경합 테스트 효과가 떨어집니다."
fi

# ─────────────────────────────────────────────
# 1. GET /checkout 동시 요청 — 배리어 기반
# ─────────────────────────────────────────────
echo ""
echo "=== [1] GET /checkout 동시 요청 (${USER_COUNT}개, 배리어 동시 출발) ==="

FIRE_AT=$(python3 -c "import time; print(time.time() + 2)")
CHECKOUT_START=$(ms)

pids=()
for uid in $USER_IDS; do
  (
    python3 -c "import time; d=$FIRE_AT-time.time(); d>0 and time.sleep(d)"
    body_file="${RESULTS_DIR}/co_body_${uid}.txt"
    http_code=$(curl -s -o "$body_file" -w "%{http_code}" \
      --max-time 10 --connect-timeout 3 \
      "${BASE_URL}/api/checkout?eventId=${EVENT_ID}&optionId=${OPTION_ID}" \
      -H "X-User-Id: ${uid}")
    echo "${http_code}" > "${RESULTS_DIR}/co_${uid}.txt"
  ) &
  pids+=($!)
done
for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done

CHECKOUT_END=$(ms)

co_ok=0; co_unavail=0; co_fail=0
for uid in $USER_IDS; do
  [ -f "${RESULTS_DIR}/co_${uid}.txt" ] || continue
  http_code=$(cat "${RESULTS_DIR}/co_${uid}.txt")
  available=$(python3 -c "
import json
try:
  d=json.load(open('${RESULTS_DIR}/co_body_${uid}.txt'))
  print(d['data']['available'])
except: print('ERROR')
" 2>/dev/null)
  if   [ "$http_code" = "200" ] && [ "$available" = "True" ];  then co_ok=$((co_ok+1))
  elif [ "$http_code" = "200" ] && [ "$available" = "False" ]; then co_unavail=$((co_unavail+1))
  else co_fail=$((co_fail+1))
  fi
done
echo "  완료: $((CHECKOUT_END - CHECKOUT_START))ms"
echo "  available=true  : $co_ok"
echo "  available=false : $co_unavail"
echo "  오류            : $co_fail"

# ─────────────────────────────────────────────
# 2. POST /booking 동시 요청 — 배리어 기반
# ─────────────────────────────────────────────
echo ""
echo "=== [2] POST /booking 동시 요청 (${USER_COUNT}개, 배리어 동시 출발) ==="

FIRE_AT=$(python3 -c "import time; print(time.time() + 2)")
BOOKING_START=$(ms)

pids=()
for uid in $USER_IDS; do
  idem_key="lt-${RUN_ID}-u${uid}"
  (
    python3 -c "import time; d=$FIRE_AT-time.time(); d>0 and time.sleep(d)"
    body_file="${RESULTS_DIR}/bk_body_${uid}.txt"
    http_code=$(curl -s -o "$body_file" -w "%{http_code}" \
      --max-time 15 --connect-timeout 3 \
      -X POST "${BASE_URL}/api/booking" \
      -H "Content-Type: application/json" \
      -H "X-User-Id: ${uid}" \
      -H "Idempotency-Key: ${idem_key}" \
      -d "{\"eventId\":${EVENT_ID},\"optionId\":${OPTION_ID},\"paymentMethod\":\"CREDIT_CARD\",\"pointsToUse\":0}")
    echo "${http_code}" > "${RESULTS_DIR}/bk_${uid}.txt"
  ) &
  pids+=($!)
done
for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done

BOOKING_END=$(ms)

# ─────────────────────────────────────────────
# 3. 결과 집계
# ─────────────────────────────────────────────
echo ""
echo "=== [3] 결과 집계 ($((BOOKING_END - BOOKING_START))ms) ==="

python3 - "$RESULTS_DIR" <<'PYEOF'
import sys, json, os, glob

results_dir = sys.argv[1]
counts = {}

for f in sorted(glob.glob(f"{results_dir}/bk_body_*.txt")):
    try:
        d = json.load(open(f))
        if d["success"]:
            code = d["data"]["status"]
        else:
            code = d["error"]["code"]
    except Exception:
        code = "PARSE_ERROR"
    counts[code] = counts.get(code, 0) + 1

print()
print("  결과 코드별 분포:")
for code, n in sorted(counts.items(), key=lambda x: -x[1]):
    print(f"    {code:<30} : {n}")
total = sum(counts.values())
paid = counts.get("PAID", 0)
print(f"  합계: {total}  |  PAID: {paid}")
PYEOF

# ─────────────────────────────────────────────
# 4. Redis 재고 정합성 검증
# ─────────────────────────────────────────────
echo ""
echo "=== [4] Redis 재고 정합성 ==="

STOCK_KEY="stock:event:${EVENT_ID}:option:${OPTION_ID}"
promo=$($REDIS_CMD HGET "$STOCK_KEY" promo_stock 2>/dev/null || echo "0")
sold=$($REDIS_CMD HGET "$STOCK_KEY" sold 2>/dev/null || echo "0")
purchased=$($REDIS_CMD SCARD "purchased:event:${EVENT_ID}" 2>/dev/null || echo "0")

# inflight: 개별 TTL 키, SCAN으로 카운트
inflight=$($REDIS_CMD --scan --pattern "inflight:event:${EVENT_ID}:option:${OPTION_ID}:user:*" 2>/dev/null | grep -c '' | tr -d ' \n' || echo "0")
inflight=${inflight:-0}

initial_stock=10  # DataInitializer promo_stock_total
total=$(( ${promo:-0} + ${sold:-0} + ${inflight:-0} ))

echo "  promo_stock (잔여)   : $promo"
echo "  sold (확정)          : $sold"
echo "  inflight (진행중)    : $inflight"
echo "  purchased SET 크기   : $purchased"
echo "  ─────────────────────────────"
echo "  promo+sold+inflight  : $total  (정합성 OK → ${initial_stock}과 같아야 함)"

if [ "$total" -eq "$initial_stock" ]; then
  echo "  ✓ 재고 정합성 OK (oversell 없음)"
else
  echo "  ✗ 재고 정합성 FAIL — 초기 재고(${initial_stock})와 불일치"
fi

# ─────────────────────────────────────────────
# 5. DB 주문 현황 (이번 RUN_ID 기준)
# ─────────────────────────────────────────────
echo ""
echo "=== [5] DB 주문 현황 (RUN_ID=$RUN_ID) ==="
$MYSQL_CMD -e "
SELECT status, COUNT(*) AS count
FROM orders
WHERE idempotency_key LIKE 'lt-${RUN_ID}-%'
GROUP BY status
ORDER BY status;" 2>/dev/null || echo "  (조회 실패)"

echo ""
echo "=== 완료 ==="
rm -rf "$RESULTS_DIR"

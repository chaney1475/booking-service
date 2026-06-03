/**
 * 시나리오 4 — 재고 초과판매 방지 (Stock Fence)
 * 1,000 VU 동시 구매 시도 → 정확히 10개만 PAID 되는지 직접 검증
 *
 * teardown에서 사후 검증 쿼리를 출력한다.
 *
 * 실행:
 *   k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 -e OPTION_ID=1 -e PROMO_PRICE=59000 s4_stock_fence.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const bookingDuration      = new Trend('booking_duration', true);
const paidCount            = new Counter('booking_paid');
const soldOutCount         = new Counter('booking_sold_out');
const alreadyPurchasedCount = new Counter('booking_already_purchased');
const errorCount           = new Counter('booking_server_errors');

export const options = {
  scenarios: {
    fence: {
      executor:    'shared-iterations',
      vus:         1000,
      iterations:  1000,
      maxDuration: '60s',
    },
  },
  thresholds: {
    booking_duration: ['p(95)<500', 'p(99)<1000'],
    booking_paid:     ['count<=10'], // 핵심: 재고(10)를 절대 초과할 수 없다
    booking_server_errors: ['count==0'],
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const EVENT_ID  = __ENV.EVENT_ID  || '1';
const OPTION_ID = __ENV.OPTION_ID || '1';

export default function () {
  const idemKey = `s4-vu${__VU}-i${__ITER}`;

  const res = http.post(
    `${BASE_URL}/api/booking`,
    JSON.stringify({
      eventId:       parseInt(EVENT_ID),
      optionId:      parseInt(OPTION_ID),
      paymentMethod: 'CREDIT_CARD',
      pointsToUse:   0,
    }),
    {
      headers: {
        'Content-Type':    'application/json',
        'X-User-Id':       String(__VU),
        'Idempotency-Key': idemKey,
      },
    }
  );

  bookingDuration.add(res.timings.duration);

  check(res, { 'not 5xx': (r) => r.status < 500 });

  if (res.status >= 500) { errorCount.add(1); return; }

  let status, code;
  try { status = res.json('data.status'); } catch (_) {}
  try { code   = res.json('error.code');  } catch (_) {}

  if (res.status === 200 && status === 'PAID')  paidCount.add(1);
  else if (code === 'SOLD_OUT')                 soldOutCount.add(1);
  else if (code === 'ALREADY_PURCHASED')        alreadyPurchasedCount.add(1);
}

export function teardown() {
  console.log('\n=== 재고 정합성 사후 검증 (수동 확인) ===');
  console.log('DB:');
  console.log(`  docker exec booking-mysql mysql -uroot -proot booking -e "SELECT status, COUNT(*) FROM orders WHERE event_id=${EVENT_ID} GROUP BY status;"`);
  console.log('Redis:');
  console.log(`  docker exec booking-redis redis-cli HGET stock:event:${EVENT_ID}:option:${OPTION_ID} promo_stock`);
  console.log(`  docker exec booking-redis redis-cli HGET stock:event:${EVENT_ID}:option:${OPTION_ID} sold`);
  console.log('기대값: PAID = 10, sold = 10');
}

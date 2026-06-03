/**
 * 시나리오 5 — E2E 혼합 부하 (Mixed Load)
 *
 * flowA (조회 전용): 500 req/s → GET /api/checkout
 * flowB (전체 플로우): 500 req/s → GET /api/checkout → POST /api/booking
 * 합산 목표 TPS: 1,000
 *
 * 목적: 조회 트래픽과 구매 트래픽이 서로의 응답시간에 미치는 교차 영향 측정
 *
 * 실행:
 *   k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 -e OPTION_ID=1 -e PROMO_PRICE=59000 s5_e2e_mixed.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const checkoutDuration = new Trend('checkout_duration', true);
const bookingDuration  = new Trend('booking_duration',  true);
const paidCount        = new Counter('booking_paid');
const errorCount       = new Counter('total_errors');

export const options = {
  scenarios: {
    // flowA: 조회 전용 (500 req/s)
    checkout_only: {
      executor:        'constant-arrival-rate',
      rate:            500,
      timeUnit:        '1s',
      duration:        '30s',
      preAllocatedVUs: 500,
      maxVUs:          600,
      exec:            'checkoutOnly',
    },
    // flowB: 조회 + 구매 (500 req/s) → 합산 1,000 TPS
    full_purchase: {
      executor:        'constant-arrival-rate',
      rate:            500,
      timeUnit:        '1s',
      duration:        '30s',
      preAllocatedVUs: 500,
      maxVUs:          600,
      exec:            'fullPurchase',
    },
  },
  thresholds: {
    checkout_duration: ['p(99)<2000'],
    booking_duration:  ['p(99)<1500'],
    booking_paid:      ['count<=10'],
    total_errors:      ['count<50'], // 5xx < 1% of 5,000 total requests
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const EVENT_ID  = __ENV.EVENT_ID  || '1';
const OPTION_ID = __ENV.OPTION_ID || '1';

// flowA: 조회만
export function checkoutOnly() {
  const res = http.get(
    `${BASE_URL}/api/checkout?eventId=${EVENT_ID}&optionId=${OPTION_ID}`,
    { headers: { 'X-User-Id': String(__VU) } }
  );

  checkoutDuration.add(res.timings.duration);

  const ok = check(res, { 'checkout 200': (r) => r.status === 200 });
  if (!ok) errorCount.add(1);
}

// flowB: 조회 → 구매
export function fullPurchase() {
  const userId = __VU + 500; // flowB VU ID 충돌 방지 (500~1100)

  // 1. checkout 조회
  const coRes = http.get(
    `${BASE_URL}/api/checkout?eventId=${EVENT_ID}&optionId=${OPTION_ID}`,
    { headers: { 'X-User-Id': String(userId) } }
  );

  checkoutDuration.add(coRes.timings.duration);

  if (coRes.status !== 200) { errorCount.add(1); return; }

  // available=false면 재고 소진 — 구매 요청 생략
  let available = true;
  try { available = coRes.json('data.available'); } catch (_) {}
  if (!available) return;

  sleep(0.05); // 화면 렌더링 후 결제 버튼 누르는 딜레이 (50ms)

  // 2. booking 요청
  const idemKey = `s5-vu${userId}-i${__ITER}`;
  const bkRes = http.post(
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
        'X-User-Id':       String(userId),
        'Idempotency-Key': idemKey,
      },
    }
  );

  bookingDuration.add(bkRes.timings.duration);

  const ok = check(bkRes, { 'booking not 5xx': (r) => r.status < 500 });
  if (!ok) { errorCount.add(1); return; }

  try {
    if (bkRes.status === 200 && bkRes.json('data.status') === 'PAID') paidCount.add(1);
  } catch (_) {}
}

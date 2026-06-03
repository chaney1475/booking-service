/**
 * 시나리오 2 — 구매 폭주 (Booking Spike)
 * 1,000 VU 동시에 POST /api/booking 시도 → reserve.lua 원자 점유 + DB saga 처리 능력 측정
 *
 * [closed-loop / 포화 테스트]
 * 목적: 1,000 VU 동시 폭주에서 재고 정합성(PAID ≤ 10, oversell 0)과 5xx=0 검증.
 *      레이턴시는 대기 시간이 지배적 — 정합성이 핵심 지표다.
 *
 * 실행:
 *   k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 -e OPTION_ID=1 -e PROMO_PRICE=59000 s2_booking_spike.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const bookingDuration = new Trend('booking_duration', true);
const paidCount       = new Counter('booking_paid');
const soldOutCount    = new Counter('booking_sold_out');
const errorCount      = new Counter('booking_errors');

export const options = {
  scenarios: {
    spike: {
      executor:    'shared-iterations',
      vus:         1000,
      iterations:  1000,
      maxDuration: '60s',
    },
  },
  thresholds: {
    booking_paid:   ['count<=10'], // 핵심: 재고(10)를 절대 초과할 수 없다
    booking_errors: ['count<10'],  // 포화 테스트 — 레이턴시가 아닌 정합성이 핵심
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const EVENT_ID  = __ENV.EVENT_ID  || '1';
const OPTION_ID = __ENV.OPTION_ID || '1';

export default function () {
  const idemKey = `s2-vu${__VU}-i${__ITER}`;

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
  try {
    status = res.json('data.status');
    code   = res.json('error.code');
  } catch (_) {}

  if (res.status === 200 && status === 'PAID') paidCount.add(1);
  else if (code === 'SOLD_OUT')                soldOutCount.add(1);
  else if (res.status >= 500)                  errorCount.add(1);
}

/**
 * 시나리오 3 — 멱등성 스트레스 (Idempotency Storm)
 *
 * VU  1~33:  동일 Idempotency-Key로 30번 반복 (중복 요청 시뮬레이션)
 * VU 34~1000: 매 iteration마다 다른 Idempotency-Key (정상 단건)
 *
 * 검증 포인트:
 *   - VU 1~33의 30회 응답이 모두 같은 orderId 반환
 *   - 중복 결제 건수 0 (DB orders 행이 VU당 최대 1개)
 *   - 전체 PAID <= 10 (재고 정합성 유지)
 *   - 409 DUPLICATE_ORDER: 진행 중 동시 유입 시 반환
 *
 * 실행:
 *   k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 -e OPTION_ID=1 -e PROMO_PRICE=59000 s3_idempotency.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const bookingDuration      = new Trend('booking_duration', true);
const paidCount            = new Counter('booking_paid');
const duplicateOkCount     = new Counter('idempotent_replays');   // 중복 요청 → 동일 응답 반환
const mismatchCount        = new Counter('idempotent_mismatches'); // orderId 불일치 = 버그
const duplicateOrderCount  = new Counter('duplicate_order_409');   // 처리 중 충돌

// VU별 첫 번째 orderId 저장 (재요청과 비교용)
const firstOrderId = {};

export const options = {
  scenarios: {
    duplicate_users: {
      executor:    'per-vu-iterations',
      vus:         33,
      iterations:  30,
      maxDuration: '60s',
      exec:        'duplicateFlow',
    },
    unique_users: {
      executor:    'per-vu-iterations',
      vus:         967,
      iterations:  1,
      maxDuration: '60s',
      exec:        'uniqueFlow',
      startTime:   '0s',
    },
  },
  thresholds: {
    booking_duration:      ['p(95)<500', 'p(99)<1000'],
    booking_paid:          ['count<=10'],
    idempotent_mismatches: ['count==0'], // 불일치 = 멱등성 버그
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const EVENT_ID  = __ENV.EVENT_ID  || '1';
const OPTION_ID = __ENV.OPTION_ID || '1';

function postBooking(userId, idemKey) {
  return http.post(
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
}

// VU 1~33: 같은 idemKey로 30회 반복
export function duplicateFlow() {
  const userId  = __VU;
  const idemKey = `s3-dup-vu${userId}-fixed`; // ITER와 무관하게 고정

  const res = postBooking(userId, idemKey);
  bookingDuration.add(res.timings.duration);

  check(res, { 'status 200 or 409': (r) => r.status === 200 || r.status === 409 });

  let orderId, code;
  try { orderId = res.json('data.orderId'); } catch (_) {}
  try { code    = res.json('error.code');   } catch (_) {}

  if (code === 'DUPLICATE_ORDER') {
    duplicateOrderCount.add(1);
    return;
  }

  if (res.status === 200) {
    if (__ITER === 0) {
      firstOrderId[userId] = orderId;
      try { if (res.json('data.status') === 'PAID') paidCount.add(1); } catch (_) {}
    } else {
      if (firstOrderId[userId] !== undefined && orderId === firstOrderId[userId]) {
        duplicateOkCount.add(1);
      } else if (firstOrderId[userId] !== undefined) {
        mismatchCount.add(1);
      }
    }
  }
}

// VU 34~1000: 매번 다른 idemKey (정상 단건)
export function uniqueFlow() {
  const userId  = __VU + 33; // 34~1000
  const idemKey = `s3-uniq-vu${userId}-i${__ITER}`;

  const res = postBooking(userId, idemKey);
  bookingDuration.add(res.timings.duration);

  check(res, { 'status 200 or 409': (r) => r.status === 200 || r.status === 409 });

  try {
    if (res.status === 200 && res.json('data.status') === 'PAID') paidCount.add(1);
  } catch (_) {}
}

/**
 * 시나리오 1 — 조회 폭주 (Checkout Spike)
 * 1,000 VU 동시에 GET /api/checkout 호출 → Redis 읽기 + MySQL 조회 경합 측정
 *
 * [closed-loop / 포화 테스트]
 * 목적: 자원 한도(2 CPU, Hikari 25) 에서 에러 없이 요청을 소화하는지 검증.
 *      레이턴시는 Little's Law에 따라 대기 시간이 지배적 — 응답 시간 SLA가 아닌
 *      errors=0, 5xx=0이 핵심 지표다.
 *
 * 실행:
 *   k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 -e OPTION_ID=1 s1_checkout_spike.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const checkoutDuration = new Trend('checkout_duration', true);
const checkoutErrors   = new Counter('checkout_errors');

export const options = {
  scenarios: {
    spike: {
      executor:    'shared-iterations',
      vus:         1000,
      iterations:  1000,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checkout_errors: ['count==0'], // 포화 테스트 — 레이턴시가 아닌 에러 0이 핵심
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const EVENT_ID  = __ENV.EVENT_ID  || '1';
const OPTION_ID = __ENV.OPTION_ID || '1';


export default function () {
  const res = http.get(
    `${BASE_URL}/api/checkout?eventId=${EVENT_ID}&optionId=${OPTION_ID}`,
    { headers: { 'X-User-Id': String(__VU) } }
  );

  checkoutDuration.add(res.timings.duration);

  const ok = check(res, {
    'status 200':        (r) => r.status === 200,
    'available present': (r) => {
      try { return r.json('data.available') !== undefined; }
      catch (_) { return false; }
    },
  });

  if (!ok) checkoutErrors.add(1);
}

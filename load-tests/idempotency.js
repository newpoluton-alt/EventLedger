import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.EVENTLEDGER_BASE_URL || 'http://localhost:8080').replace(
  /\/+$/,
  '',
);
const SOURCE_ACCOUNT_ID =
  __ENV.EVENTLEDGER_SOURCE_ACCOUNT_ID ||
  '00000000-0000-0000-0000-000000000001';
const DESTINATION_ACCOUNT_ID =
  __ENV.EVENTLEDGER_DESTINATION_ACCOUNT_ID ||
  '00000000-0000-0000-0000-000000000002';
const API_KEY = __ENV.EVENTLEDGER_API_KEY || 'local-development-key';
const CURRENCY = __ENV.EVENTLEDGER_CURRENCY || 'EUR';
const AMOUNT = positiveNumber(__ENV.EVENTLEDGER_PAYMENT_AMOUNT || '0.01', 'amount');
const RATE = positiveInteger(__ENV.EVENTLEDGER_RATE || '5', 'rate');
const PRE_ALLOCATED_VUS = positiveInteger(
  __ENV.EVENTLEDGER_PRE_ALLOCATED_VUS || '10',
  'pre-allocated VUs',
);
const MAX_VUS = positiveInteger(
  __ENV.EVENTLEDGER_MAX_VUS || '50',
  'maximum VUs',
);
const DURATION = __ENV.EVENTLEDGER_DURATION || '30s';
const PAUSE_SECONDS = nonNegativeNumber(
  __ENV.EVENTLEDGER_ITERATION_PAUSE_SECONDS || '0',
  'iteration pause',
);

const idempotencyMismatches = new Rate('idempotency_mismatches');
const replayRequests = new Counter('idempotency_replay_requests');
const replayDuration = new Trend('idempotency_replay_duration', true);

export const options = {
  scenarios: {
    idempotent_payments: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<750', 'p(99)<1500'],
    idempotency_mismatches: ['rate==0'],
  },
};

export function setup() {
  const readiness = http.get(`${BASE_URL}/actuator/health/readiness`, {
    headers: { Accept: 'application/json' },
    tags: { operation: 'readiness' },
  });
  const ready = check(readiness, {
    'EventLedger is ready before load starts': (response) =>
      response.status === 200,
  });
  if (!ready) {
    throw new Error(
      `EventLedger is not ready at ${BASE_URL}: HTTP ${readiness.status}`,
    );
  }
}

export default function () {
  const token = uniqueToken();
  const idempotencyKey = `k6-${token}`;
  const payload = JSON.stringify({
    sourceAccountId: SOURCE_ACCOUNT_ID,
    destinationAccountId: DESTINATION_ACCOUNT_ID,
    amount: AMOUNT,
    currency: CURRENCY,
    reference: `k6-${token}`,
  });
  const requestParameters = {
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-API-Key': API_KEY,
    },
    tags: { operation: 'create_payment' },
  };

  group('idempotent payment replay', () => {
    const original = http.post(
      `${BASE_URL}/api/v1/payments`,
      payload,
      requestParameters,
    );
    const originalId = paymentId(original);
    check(original, {
      'original payment is accepted': (response) =>
        [200, 201, 202].includes(response.status),
      'original response has a payment ID': () => originalId !== null,
    });

    const replayStartedAt = Date.now();
    const replay = http.post(
      `${BASE_URL}/api/v1/payments`,
      payload,
      {
        ...requestParameters,
        tags: { operation: 'replay_payment' },
      },
    );
    replayDuration.add(Date.now() - replayStartedAt);
    replayRequests.add(1);
    const replayId = paymentId(replay);
    const sameLogicalPayment =
      originalId !== null && replayId !== null && replayId === originalId;
    idempotencyMismatches.add(!sameLogicalPayment);

    check(replay, {
      'replay is accepted': (response) =>
        [200, 201, 202].includes(response.status),
      'replay returns the original payment ID': () => sameLogicalPayment,
    });

    if (originalId !== null) {
      const fetched = http.get(
        `${BASE_URL}/api/v1/payments/${encodeURIComponent(originalId)}`,
        {
          headers: {
            Accept: 'application/json',
            'X-API-Key': API_KEY,
          },
          tags: { operation: 'get_payment' },
        },
      );
      check(fetched, {
        'created payment remains queryable': (response) =>
          response.status === 200,
        'GET returns the same payment ID': (response) =>
          paymentId(response) === originalId,
      });
    }
  });

  if (PAUSE_SECONDS > 0) {
    sleep(PAUSE_SECONDS);
  }
}

function paymentId(response) {
  try {
    const body = response.json();
    if (body && typeof body === 'object') {
      if (typeof body.id === 'string') {
        return body.id;
      }
      if (typeof body.paymentId === 'string') {
        return body.paymentId;
      }
      if (body.payment && typeof body.payment.id === 'string') {
        return body.payment.id;
      }
    }
  } catch (_) {
    // A failed JSON parse is reported by the checks in the caller.
  }
  return null;
}

function uniqueToken() {
  const randomPart = Math.floor(Math.random() * 0x7fffffff).toString(36);
  return `${Date.now()}-${__VU}-${__ITER}-${randomPart}`;
}

function positiveInteger(rawValue, label) {
  const value = Number(rawValue);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${label} must be a positive integer, got "${rawValue}"`);
  }
  return value;
}

function positiveNumber(rawValue, label) {
  const value = Number(rawValue);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${label} must be a positive number, got "${rawValue}"`);
  }
  return value;
}

function nonNegativeNumber(rawValue, label) {
  const value = Number(rawValue);
  if (!Number.isFinite(value) || value < 0) {
    throw new Error(`${label} must be zero or greater, got "${rawValue}"`);
  }
  return value;
}

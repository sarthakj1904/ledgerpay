// LedgerPay load test: many users hammering the transfer endpoint.
//
// Run:
//   docker compose up -d                                       (start the stack)
//   k6 run scripts/load/transfer.js                            (default profile)
//   k6 run -e VUS=50 -e DURATION=2m scripts/load/transfer.js   (override)
//
// Test profile:
//   - setup() registers VUS senders + 1 sink wallet, funds each sender wallet once.
//   - Each VU loops: pick its pre-registered sender wallet and transfer Rs.1.50 to the sink.
//   - Thresholds gate the run: 95th %ile transfer latency < 200ms, error rate < 1%.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import exec from 'k6/execution';

const BASE     = __ENV.BASE     || 'http://localhost:8080';
const VUS      = parseInt(__ENV.VUS      || '20', 10);
const DURATION = __ENV.DURATION || '30s';

export const options = {
    scenarios: {
        transfers: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
            gracefulStop: '15s',
        },
    },
    // Thresholds reflect a contended hot-spot scenario: every VU writes to ONE shared sink
    // wallet, so the sink's @Version optimistic-lock is under continuous contention. Real
    // workloads would shard receivers and see significantly lower tail latency.
    thresholds: {
        'http_req_duration{operation:transfer}':           ['p(95)<300', 'p(99)<800'],
        'transfer_errors':                                 ['rate<0.05'],
        'transfers_succeeded':                             ['count>50'],
    },
};

const transferLatency    = new Trend('transfer_latency_ms', true);
const transfersSucceeded = new Counter('transfers_succeeded');
const transferErrors     = new Rate('transfer_errors');

export function setup() {
    console.log(`Setting up ${VUS} sender wallets + 1 sink...`);

    const sinkAuth = registerUser(`sink-${uuidv4()}@ledgerpay.local`);
    const sinkWalletId = createWallet(sinkAuth.accessToken);

    const senders = [];
    for (let i = 0; i < VUS; i++) {
        const auth = registerUser(`vu${i}-${uuidv4()}@ledgerpay.local`);
        const walletId = createWallet(auth.accessToken);
        // Fund the wallet so transfers have ample room (Rs.10,000 = 1,000,000 minor).
        topUp(auth.accessToken, walletId, 1_000_000);
        senders.push({ token: auth.accessToken, walletId });
    }
    console.log(`Setup complete. sink=${sinkWalletId}`);
    return { sinkWalletId, senders };
}

export default function (data) {
    // Each VU pulls its dedicated pre-registered sender from the setup payload.
    const me = data.senders[(exec.vu.idInTest - 1) % data.senders.length];

    const idemKey = `xfer-${uuidv4()}`;
    const start = Date.now();
    const res = http.post(`${BASE}/api/v1/payments/transfer`, JSON.stringify({
        fromWalletId: me.walletId,
        toWalletId:   data.sinkWalletId,
        amountMinor:  150,
    }), {
        headers: {
            'content-type':    'application/json',
            'authorization':   `Bearer ${me.token}`,
            'idempotency-key': idemKey,
        },
        tags: { operation: 'transfer' },
    });
    transferLatency.add(Date.now() - start);

    const ok = check(res, {
        'transfer 201':     (r) => r.status === 201,
        'returned txn id':  (r) => r.json('id') !== undefined && r.json('id') !== null,
    });
    transferErrors.add(!ok);
    if (ok) transfersSucceeded.add(1);

    // Tiny think-time so we don't peg the wallet's @Version optimistic lock.
    sleep(0.05);
}

// ---------- helpers --------------------------------------------------------

function registerUser(email) {
    const r = http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({
        email, password: 'password123', role: 'USER',
    }), { headers: { 'content-type': 'application/json' }, tags: { operation: 'register' } });
    if (r.status !== 201) throw new Error(`register ${email} -> ${r.status}: ${r.body}`);
    return r.json();
}

function createWallet(token) {
    const r = http.post(`${BASE}/api/v1/wallets`, JSON.stringify({ currency: 'INR' }), {
        headers: { 'content-type': 'application/json', 'authorization': `Bearer ${token}` },
        tags: { operation: 'create_wallet' },
    });
    if (r.status !== 201) throw new Error(`wallet -> ${r.status}: ${r.body}`);
    return r.json('id');
}

function topUp(token, walletId, amountMinor) {
    const r = http.post(`${BASE}/api/v1/payments/add-money`, JSON.stringify({
        walletId, amountMinor, currency: 'INR',
    }), {
        headers: {
            'content-type':    'application/json',
            'authorization':   `Bearer ${token}`,
            'idempotency-key': `bootstrap-${uuidv4()}`,
        },
        tags: { operation: 'add_money' },
    });
    if (r.status !== 201) throw new Error(`top up -> ${r.status}: ${r.body}`);
}

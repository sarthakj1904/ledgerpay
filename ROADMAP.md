# LedgerPay roadmap

Items intentionally out of scope for the current build. Each entry is sized so that an engineer
(or an AI agent with access to this repo) can pick it up cold. **Effort labels:**

- **S** = a few hours, single PR
- **M** = a day or two, possibly two PRs
- **L** = multi-day, design doc warranted first

Items are roughly grouped by theme and listed in suggested execution order within each group.

---

## Reliability & resilience

### 1. Resilience4j circuit breakers on merchant webhooks  *(S)*
The notification-service POSTs to merchant `webhookUrl`s. A flaky merchant currently slows the
whole consumer because every event waits for the retry budget to exhaust.

- Add `resilience4j-spring-boot3` to `services/notification-service/pom.xml`.
- Wrap [`WebhookClient.send`](services/notification-service/src/main/java/com/ledgerpay/notification/webhook/WebhookClient.java)
  with a `@CircuitBreaker(name = "merchantWebhook")` keyed by merchant host so one bad merchant
  does not affect another.
- Configure `slidingWindowSize=20`, `failureRateThreshold=50%`, `waitDurationInOpenState=30s`.
- Expose breaker state via `/actuator/circuitbreakers`; emit transitions to `notification.events`.
- **Done when**: a unit test simulates 20 consecutive 500s and asserts the breaker opens; events
  during the open window land in the DLQ topic without invoking the WebClient.

### 2. Pessimistic-locking opt-in for hot-spot wallets  *(M)*
The current optimistic-lock + retry pattern works well for most wallets but degrades under
sustained contention on a single account (e.g. a central treasury or platform fee wallet).
Add a `wallets.locking_strategy ENUM('OPTIMISTIC','PESSIMISTIC')` column with default
`OPTIMISTIC`; have [`LedgerService.post`](monolith/src/main/java/com/ledgerpay/ledger/service/LedgerService.java)
use `EntityManager.find(Wallet.class, id, LockModeType.PESSIMISTIC_WRITE)` when the leg's wallet
is marked `PESSIMISTIC`.

- Migration `V3__wallet_locking_strategy.sql`.
- New admin endpoint `PUT /api/v1/admin/wallets/{id}/locking-strategy`.
- Concurrency test that pessimistic-locking eliminates retry-exhaustion errors at the cost of
  serial throughput.
- **Done when**: a k6 run targeting a `PESSIMISTIC` wallet shows zero `OptimisticLockingFailureException`,
  with a measurable throughput drop documented in the README.

### 3. Idempotency-key TTL cleanup job  *(S)*
`idempotency_keys.expires_at` is populated but nothing prunes the table. A `@Scheduled` job
should `DELETE FROM idempotency_keys WHERE expires_at < now() - INTERVAL '1 day'` daily and
emit a metric for rows reclaimed.

### 4. Outbox poison-event metric + DLQ inspection endpoint  *(S)*
After `max-retries` is exceeded the row is `FAILED`. Today there is no first-class way to
operate on these rows. Add:

- `GET /api/v1/admin/outbox?status=FAILED&page=0` admin endpoint.
- `POST /api/v1/admin/outbox/{id}/replay` to mark a `FAILED` row back to `PENDING`.
- Gauge `ledgerpay.outbox.failed` so Grafana / Prometheus can alert on growth.

---

## Throttling & abuse protection

### 5. Bucket4j rate limiting per API key  *(M)*
Public payment endpoints are unauthenticated-attacker-shaped: anyone with a stolen JWT can
burn balance. Add a Redis-backed Bucket4j bucket per `(userId or apiKey, endpoint)`:

- `100 req / 60 s` for `/api/v1/payments/**`.
- `10 req / 1 s` per IP on `/api/v1/auth/login`.
- Return `429 Too Many Requests` with a `Retry-After` header.
- Configure thresholds via `ledgerpay.ratelimit.*` properties.
- **Done when**: a k6 burst of 200 RPS from one VU sees the expected mix of `201` and `429`
  responses, and the bucket replenishes after the window.

### 6. Feature flags  *(S)*
A small flag table `feature_flags(name PK, enabled BOOLEAN, rolloutPercent INT)` and a
`FeatureService.isEnabled(name, userId)` helper. Use it to gate the `merchant-pay` endpoint
or new fraud rules without redeploying. Admin endpoint to flip flags. Cache reads with a
30-second in-memory cache.

---

## Money lifecycle features

### 7. Chargebacks / disputes lifecycle  *(L)*
Today refunds are merchant-initiated. Chargebacks are bank-initiated and have a multi-state
flow: `OPENED → EVIDENCE_REQUESTED → EVIDENCE_SUBMITTED → WON/LOST`. Money moves on `OPENED`
(merchant balance debited into a holding wallet) and on resolution (returned to merchant on
`WON`, paid to user on `LOST`).

Design notes:

- New entity `Chargeback(id, txn_id, status, amount_minor, reason_code, opened_at, due_by, …)`.
- New `SYSTEM` wallet `CHARGEBACK_HOLDING` so funds escrow without leaving the ledger.
- New events: `CHARGEBACK_OPENED`, `CHARGEBACK_EVIDENCE_DUE`, `CHARGEBACK_RESOLVED`.
- Notification-service notifies merchants when evidence is due.
- A `@Scheduled` worker auto-loses chargebacks past their `due_by`.

Acceptance: full happy path + lose path + win path covered by an integration test.

### 8. Scheduled merchant payouts  *(M)*
Merchants accumulate balance from `MERCHANT_PAY` events but currently have no way to extract
it. Implement a daily payout job:

- `payout_schedules(merchant_id PK, frequency, min_amount_minor, currency, next_run_at, target_account_id)`.
- A worker that, when `next_run_at <= now()`, transfers all but a small floor balance to a
  pre-registered external account (modelled as another wallet of `ownerType=EXTERNAL`).
- Emits `PAYOUT_INITIATED` and `PAYOUT_COMPLETED` events.

Acceptance: an integration test that fast-forwards `next_run_at`, runs the worker, and asserts
the merchant balance fell while the external wallet rose by the same minor amount.

### 9. Multi-currency wallets  *(M)*
Today every payment requires same-currency wallets. Add an `fx_rates` table and an
`FxService.convert(amount, fromCcy, toCcy, asOf)` that consults it. `LedgerService` already
takes `currency` per leg, so the cross-currency path becomes:

- DEBIT 100 USD from source
- CREDIT 8,300 INR to destination
- DEBIT 8,300 INR from `SYSTEM:FX_INVENTORY_INR`
- CREDIT 100 USD to `SYSTEM:FX_INVENTORY_USD`

This keeps double-entry holding per-currency. Spreads can be added by widening the FX leg.

---

## Operations & ops experience

### 10. Real Kubernetes manifests  *(M)*
The `k8s/` directory currently only has stubs. Produce manifests / Helm chart that:

- Deploys monolith + notification + fraud as `Deployment`s with proper readiness probes
  pointing at `/actuator/health/readiness`.
- Externalises secrets via `Secret` (`LEDGERPAY_JWT_SECRET`, DB password).
- Pins resource requests/limits derived from the README's load-test numbers.
- Optionally a `KafkaCluster` via Strimzi and a Postgres Operator instance.
- A `CronJob` calling `POST /api/v1/admin/reconciliation/run` (or replace the in-process
  scheduler entirely with a Kubernetes-native cron).

Acceptance: `kubectl apply -k k8s/overlays/local-kind` brings up the full stack on Kind.

### 11. Multi-region simulation  *(L)*
Two write regions with conflict-free wallet partitioning by `wallet_id` hash. Inter-region
replication via Kafka MirrorMaker 2 or Debezium. Demonstrate that partitioning eliminates
cross-region writes for a single wallet.

This is essentially a small system-design exercise; a design doc should precede code.

### 12. Distributed tracing  *(S)*
Spring Boot 3 + Micrometer Tracing + OTLP exporter. Add a Jaeger or Tempo container to
`docker-compose.yml`, propagate trace context across the Kafka boundary via
`ProducerInterceptor` / `ConsumerInterceptor`. Verify a single payment's full path
(controller → DB → outbox → Kafka → notification-service → merchant webhook) appears as one
trace.

---

## Frontend

### 13. Admin dashboard (Next.js 14 + Tailwind)  *(L)*
Pages:

- Login (uses `/api/v1/auth/login`).
- Wallet list with balance, freeze/unfreeze actions.
- Transaction explorer with filters (status, type, wallet, time range).
- Reconciliation reports list + detail viewer (renders the `mismatches` JSON nicely).
- Fraud-flag inbox with one-click "approve" / "reject" actions (the latter requires a small
  admin endpoint that flips status back to `SUCCESS`).
- Live event feed via SSE from a new `GET /api/v1/admin/events/stream` endpoint that tails
  the `audit_logs` table.

Acceptance: a Playwright e2e test logs in as admin, sees at least one wallet, runs
reconciliation, and observes the report appear.

### 14. Merchant self-serve portal  *(M)*
A separate Next.js app for merchants:

- Webhook URL configuration UI.
- API-key rotation.
- Payouts schedule editor (depends on item 8).
- Payment & chargeback list scoped to merchant's wallets.

---

## Smaller polish items

| Item | Effort | Notes |
|---|---|---|
| Replace embedded Kafka producer/consumer config with `spring-cloud-stream` | M | One config file per binding instead of three boilerplate `KafkaConfig`s |
| Migrate the deprecated `KafkaContainer(DockerImageName)` API to the new builder | S | Test infra cleanup; Testcontainers 1.21 emits a deprecation warning |
| Fix `VelocityRuleTest` `unchecked` Mockito generics warning | S | Use `Mockito.mock(StringRedisTemplate.class)` with `Answers.RETURNS_DEEP_STUBS` |
| Use exponential backoff with jitter in the optimistic-lock retry loop | S | Currently linear `10ms * attempt`; jitter would smooth lockstep retries |
| Add `pgcrypto`-based password rotation flow | S | Today `users.password_hash` has no rotation timestamp |
| Remove `org.mapstruct` from monolith deps if it stays unused | S | Currently declared but no mappers; either start using it or drop it |
| Bundle a Postman environment file alongside the collection | S | Pre-populated `baseUrl` and example tokens make import painless |
| Health endpoint should report Kafka + DB depth | S | `HealthIndicator` for outbox queue depth |

---

## Done (delivered, not on the wishlist)

For reference:

- Auth (JWT + RBAC) ✓
- Wallet lifecycle ✓
- Double-entry ledger with DB-level immutability triggers ✓
- Idempotency-Key filter + cached body wrapper + DB UNIQUE backstop ✓
- Transactional outbox + Kafka publisher with DLQ ✓
- Audit consumer ✓
- Fraud-service: velocity / amount-spike / failure-burst / IP-blocklist rules ✓
- Notification-service with HMAC-signed merchant webhooks ✓
- Reconciliation engine (per-wallet + global double-entry) ✓
- Bootstrap admin user ✓
- Prometheus metrics + Grafana dashboard ✓
- 18 tests across unit + Testcontainers integration + concurrent ✓
- k6 load script with measured numbers in the README ✓

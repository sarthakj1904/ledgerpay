-- LedgerPay initial schema
-- Money is stored in minor units (paise/cents) as BIGINT to avoid floating-point drift.
-- Mutable rows carry `version` (BIGINT) for JPA optimistic locking.
-- ledger_entries is append-only — no application path should ever UPDATE or DELETE rows in it.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =====================================================================
-- users
-- =====================================================================
CREATE TABLE users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email            VARCHAR(255) NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    role             VARCHAR(16)  NOT NULL CHECK (role IN ('USER', 'MERCHANT', 'ADMIN')),
    kyc_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                        CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);

-- =====================================================================
-- merchants
-- =====================================================================
CREATE TABLE merchants (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_name    VARCHAR(255) NOT NULL,
    webhook_url      VARCHAR(500),
    webhook_secret   VARCHAR(255),
    api_key          VARCHAR(64)  NOT NULL UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_merchants_user UNIQUE (user_id)
);

-- =====================================================================
-- wallets
-- =====================================================================
CREATE TABLE wallets (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id         UUID NOT NULL,
    owner_type       VARCHAR(16) NOT NULL CHECK (owner_type IN ('USER', 'MERCHANT', 'SYSTEM')),
    currency         VARCHAR(3)  NOT NULL DEFAULT 'INR',
    balance_minor    BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'FROZEN')),
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- SYSTEM wallets are allowed to go negative (they represent the external world).
    -- Non-negativity for USER/MERCHANT wallets is enforced in the service layer.
    CONSTRAINT chk_wallet_balance_non_negative
        CHECK (owner_type = 'SYSTEM' OR balance_minor >= 0)
);
CREATE INDEX idx_wallets_owner ON wallets(owner_id, owner_type);

-- =====================================================================
-- transactions
-- =====================================================================
CREATE TABLE transactions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key    VARCHAR(128) NOT NULL UNIQUE,
    type               VARCHAR(24)  NOT NULL
                          CHECK (type IN ('ADD_MONEY', 'TRANSFER', 'MERCHANT_PAY', 'REFUND')),
    status             VARCHAR(16)  NOT NULL
                          CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'FLAGGED')),
    source_wallet_id   UUID REFERENCES wallets(id),
    dest_wallet_id     UUID REFERENCES wallets(id),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency           VARCHAR(3)   NOT NULL,
    parent_txn_id      UUID REFERENCES transactions(id),
    risk_score         INT          NOT NULL DEFAULT 0,
    initiator_user_id  UUID REFERENCES users(id),
    client_ip          VARCHAR(64),
    metadata           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    failure_reason     TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_txn_source ON transactions(source_wallet_id, created_at DESC);
CREATE INDEX idx_txn_dest   ON transactions(dest_wallet_id, created_at DESC);
CREATE INDEX idx_txn_status ON transactions(status);
CREATE INDEX idx_txn_parent ON transactions(parent_txn_id);

-- =====================================================================
-- ledger_entries (append-only, immutable)
-- =====================================================================
CREATE TABLE ledger_entries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_id              UUID        NOT NULL REFERENCES transactions(id),
    wallet_id           UUID        NOT NULL REFERENCES wallets(id),
    entry_type          VARCHAR(8)  NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount_minor        BIGINT      NOT NULL CHECK (amount_minor > 0),
    balance_after_minor BIGINT      NOT NULL,
    currency            VARCHAR(3)  NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_wallet ON ledger_entries(wallet_id, created_at DESC);
CREATE INDEX idx_ledger_txn    ON ledger_entries(txn_id);

-- Enforce immutability at the DB level: block UPDATE/DELETE on ledger_entries.
CREATE OR REPLACE FUNCTION ledger_entries_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update
    BEFORE UPDATE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_block_mutation();

CREATE TRIGGER trg_ledger_entries_no_delete
    BEFORE DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_block_mutation();

-- =====================================================================
-- idempotency_keys
-- =====================================================================
CREATE TABLE idempotency_keys (
    key              VARCHAR(128) PRIMARY KEY,
    user_id          UUID,
    method           VARCHAR(8)   NOT NULL,
    path             VARCHAR(500) NOT NULL,
    request_hash     VARCHAR(128) NOT NULL,
    response_body    JSONB,
    response_status  INT,
    status           VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS'
                        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_idem_expires ON idempotency_keys(expires_at);

-- =====================================================================
-- fraud_flags
-- =====================================================================
CREATE TABLE fraud_flags (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_id      UUID        NOT NULL REFERENCES transactions(id),
    rule_name   VARCHAR(64) NOT NULL,
    severity    VARCHAR(16) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    details     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_txn ON fraud_flags(txn_id);

-- =====================================================================
-- audit_logs
-- =====================================================================
CREATE TABLE audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    actor_id       UUID,
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(64)  NOT NULL,
    resource_id    VARCHAR(128),
    details        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    ip             VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor   ON audit_logs(actor_id, created_at DESC);
CREATE INDEX idx_audit_created ON audit_logs(created_at DESC);

-- =====================================================================
-- outbox (transactional-outbox pattern)
-- =====================================================================
CREATE TABLE outbox (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type   VARCHAR(64)  NOT NULL,
    aggregate_id     VARCHAR(128) NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    topic            VARCHAR(128) NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count      INT          NOT NULL DEFAULT 0,
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox(status, created_at) WHERE status = 'PENDING';

-- =====================================================================
-- Seeds: system suspense wallet for ADD_MONEY double-entry
-- =====================================================================
INSERT INTO wallets (id, owner_id, owner_type, currency, balance_minor, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'SYSTEM',
    'INR',
    0,
    'ACTIVE'
);

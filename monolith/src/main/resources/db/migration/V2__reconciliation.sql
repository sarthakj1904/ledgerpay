-- Reconciliation reports: each row is one full integrity-check pass.
-- A pass verifies two things at the moment it runs:
--   1. Per currency, sum(DEBIT amount) == sum(CREDIT amount) across all ledger_entries.
--   2. For every non-SYSTEM wallet, wallets.balance_minor matches the running sum derived
--      from its ledger entries. SYSTEM wallets are excluded because they're allowed to
--      drift negative (they represent the external world).
-- Mismatches are captured in `mismatches` jsonb so an operator can investigate.

CREATE TABLE reconciliation_reports (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at               TIMESTAMPTZ NOT NULL,
    finished_at              TIMESTAMPTZ NOT NULL,
    status                   VARCHAR(16) NOT NULL CHECK (status IN ('BALANCED', 'MISMATCH')),
    wallets_checked          INT         NOT NULL,
    transactions_checked     INT         NOT NULL,
    ledger_entries_checked   INT         NOT NULL,
    total_debits_minor       BIGINT      NOT NULL,
    total_credits_minor      BIGINT      NOT NULL,
    mismatches               JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recon_created_at ON reconciliation_reports(created_at DESC);
CREATE INDEX idx_recon_status     ON reconciliation_reports(status);

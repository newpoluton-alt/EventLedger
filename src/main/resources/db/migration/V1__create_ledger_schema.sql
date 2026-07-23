CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_reference VARCHAR(120) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('CUSTOMER', 'MERCHANT', 'SYSTEM')),
    currency CHAR(3) NOT NULL CHECK (currency = 'EUR'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    allow_negative BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_reference, currency, account_type)
);

CREATE TABLE account_balances (
    account_id UUID PRIMARY KEY REFERENCES accounts(id),
    balance_minor BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'EUR'),
    reference VARCHAR(140),
    status VARCHAR(24) NOT NULL CHECK (status IN ('POSTED', 'SETTLED', 'NEEDS_ATTENTION')),
    created_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX payments_source_created_idx ON payments (source_account_id, created_at DESC);
CREATE INDEX payments_destination_created_idx ON payments (destination_account_id, created_at DESC);
CREATE INDEX payments_status_updated_idx ON payments (status, updated_at);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    payment_id UUID UNIQUE REFERENCES payments(id),
    entry_type VARCHAR(20) NOT NULL CHECK (entry_type IN ('PAYMENT', 'FUNDING', 'REVERSAL', 'ADJUSTMENT')),
    currency CHAR(3) NOT NULL CHECK (currency = 'EUR'),
    reference VARCHAR(140),
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (payment_id IS NULL OR entry_type = 'PAYMENT')
);

CREATE TABLE ledger_postings (
    entry_id UUID NOT NULL REFERENCES ledger_entries(id),
    line_number SMALLINT NOT NULL CHECK (line_number > 0),
    account_id UUID NOT NULL REFERENCES accounts(id),
    side VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (entry_id, line_number)
);

CREATE INDEX ledger_postings_account_idx ON ledger_postings (account_id, created_at, entry_id);

CREATE TABLE idempotency_records (
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    resource_id UUID,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation, idempotency_key)
);

CREATE INDEX idempotency_expiry_idx ON idempotency_records (expires_at);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence INTEGER NOT NULL CHECK (aggregate_sequence > 0),
    event_type VARCHAR(120) NOT NULL,
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    topic VARCHAR(200) NOT NULL,
    event_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(160),
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(1000),
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    UNIQUE (aggregate_id, aggregate_sequence),
    UNIQUE (aggregate_id, event_type, event_version)
);

CREATE INDEX outbox_available_idx
    ON outbox_events (available_at, occurred_at, aggregate_sequence)
    WHERE status IN ('PENDING', 'IN_FLIGHT');

CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    event_fingerprint CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE settlement_events (
    event_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    provider_reference VARCHAR(160),
    settled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    UNIQUE (payment_id)
);

CREATE TABLE dead_letter_incidents (
    id UUID PRIMARY KEY,
    event_id UUID,
    payment_id UUID REFERENCES payments(id),
    original_topic VARCHAR(200) NOT NULL,
    partition_number INTEGER,
    offset_number BIGINT,
    error_message VARCHAR(1000) NOT NULL,
    payload JSONB,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'REPLAYED', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    UNIQUE (original_topic, partition_number, offset_number)
);

CREATE INDEX dead_letter_status_idx ON dead_letter_incidents (status, created_at);

CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED')),
    trigger_type VARCHAR(20) NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    discrepancy_count INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(1000)
);

CREATE TABLE reconciliation_discrepancies (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
    discrepancy_type VARCHAR(80) NOT NULL,
    aggregate_id UUID,
    expected JSONB,
    actual JSONB,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX reconciliation_discrepancy_run_idx ON reconciliation_discrepancies (run_id);

CREATE FUNCTION reject_ledger_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger history is immutable; write a reversal or adjustment instead'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_postings_immutable
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE FUNCTION enforce_balanced_ledger_entry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_entry_id UUID;
    debit_total NUMERIC;
    credit_total NUMERIC;
    posting_count INTEGER;
    currency_mismatch_count INTEGER;
BEGIN
    target_entry_id := COALESCE(NEW.entry_id, OLD.entry_id);

    SELECT
        COALESCE(SUM(amount_minor) FILTER (WHERE side = 'DEBIT'), 0),
        COALESCE(SUM(amount_minor) FILTER (WHERE side = 'CREDIT'), 0),
        COUNT(*)
    INTO debit_total, credit_total, posting_count
    FROM ledger_postings
    WHERE entry_id = target_entry_id;

    SELECT COUNT(*)
    INTO currency_mismatch_count
    FROM ledger_postings posting
    JOIN accounts account ON account.id = posting.account_id
    JOIN ledger_entries entry ON entry.id = posting.entry_id
    WHERE posting.entry_id = target_entry_id
      AND account.currency <> entry.currency;

    IF posting_count < 2 OR debit_total <> credit_total OR currency_mismatch_count > 0 THEN
        RAISE EXCEPTION 'ledger entry % is not balanced: debits %, credits %, postings %',
            target_entry_id, debit_total, credit_total, posting_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entry_must_balance
    AFTER INSERT OR UPDATE OR DELETE ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_balanced_ledger_entry();

CREATE FUNCTION enforce_ledger_entry_has_balanced_postings()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    debit_total NUMERIC;
    credit_total NUMERIC;
    posting_count INTEGER;
    currency_mismatch_count INTEGER;
BEGIN
    SELECT
        COALESCE(SUM(amount_minor) FILTER (WHERE side = 'DEBIT'), 0),
        COALESCE(SUM(amount_minor) FILTER (WHERE side = 'CREDIT'), 0),
        COUNT(*)
    INTO debit_total, credit_total, posting_count
    FROM ledger_postings
    WHERE entry_id = NEW.id;

    SELECT COUNT(*)
    INTO currency_mismatch_count
    FROM ledger_postings posting
    JOIN accounts account ON account.id = posting.account_id
    JOIN ledger_entries entry ON entry.id = posting.entry_id
    WHERE posting.entry_id = NEW.id
      AND account.currency <> entry.currency;

    IF posting_count < 2 OR debit_total <> credit_total OR currency_mismatch_count > 0 THEN
        RAISE EXCEPTION 'ledger entry % is not balanced: debits %, credits %, postings %',
            NEW.id, debit_total, credit_total, posting_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entry_requires_balanced_postings
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_ledger_entry_has_balanced_postings();

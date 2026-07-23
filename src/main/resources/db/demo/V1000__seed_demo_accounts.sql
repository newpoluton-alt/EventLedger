INSERT INTO accounts (
    id,
    owner_reference,
    account_type,
    currency,
    status,
    allow_negative,
    created_at
)
VALUES
    ('00000000-0000-0000-0000-000000000000', 'eventledger-clearing', 'SYSTEM', 'EUR', 'ACTIVE', TRUE, NOW()),
    ('00000000-0000-0000-0000-000000000001', 'demo-customer', 'CUSTOMER', 'EUR', 'ACTIVE', FALSE, NOW()),
    ('00000000-0000-0000-0000-000000000002', 'demo-merchant', 'MERCHANT', 'EUR', 'ACTIVE', FALSE, NOW());

INSERT INTO account_balances (account_id, balance_minor, version, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000000', -1000000, 1, NOW()),
    ('00000000-0000-0000-0000-000000000001', 1000000, 1, NOW()),
    ('00000000-0000-0000-0000-000000000002', 0, 0, NOW());

INSERT INTO ledger_entries (
    id,
    payment_id,
    entry_type,
    currency,
    reference,
    effective_at,
    created_at
)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    NULL,
    'FUNDING',
    'EUR',
    'Demo customer opening balance',
    NOW(),
    NOW()
);

INSERT INTO ledger_postings (
    entry_id,
    line_number,
    account_id,
    side,
    amount_minor,
    created_at
)
VALUES
    (
        '10000000-0000-0000-0000-000000000001',
        1,
        '00000000-0000-0000-0000-000000000000',
        'DEBIT',
        1000000,
        NOW()
    ),
    (
        '10000000-0000-0000-0000-000000000001',
        2,
        '00000000-0000-0000-0000-000000000001',
        'CREDIT',
        1000000,
        NOW()
    );

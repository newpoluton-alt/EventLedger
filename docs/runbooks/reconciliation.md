# Reconciliation mismatch response

## First response

A reconciliation mismatch is a correctness incident. Record the reconciliation
run ID and time window, stop automated replay for the affected records, and
preserve the database snapshot and relevant event offsets.

Do not repair balances with ad-hoc updates. A correction must be a new,
auditable, balanced journal transaction linked to the original payment and the
incident.

## Classify

For each mismatch, determine whether it is:

- an unbalanced journal transaction;
- a materialized balance differing from journal-entry sums;
- a payment without its expected journal transaction;
- a published event without its durable outbox source;
- an outbox event not yet published;
- a duplicate external event already rejected by the inbox/idempotency guard.

Correlate by immutable payment ID, transaction ID, event ID, and idempotency key.
Redact account and customer data from incident channels.

## Run and inspect

The endpoint requires the operational API key. Retrieve it through the
organization's approved secret-access workflow; do not paste it into tickets
or shell history. For the local stack the default is
`local-development-key`.

```bash
export EVENTLEDGER_URL="http://127.0.0.1:8080"
export EVENTLEDGER_API_KEY="local-development-key"

RUN_JSON="$(
  curl --fail --silent --show-error \
    --request POST \
    --header "X-API-Key: ${EVENTLEDGER_API_KEY}" \
    "${EVENTLEDGER_URL}/api/v1/reconciliation/runs"
)"
RUN_ID="$(printf '%s' "$RUN_JSON" | jq --raw-output '.id')"
printf '%s\n' "$RUN_JSON" | jq

curl --fail --silent --show-error \
  --header "X-API-Key: ${EVENTLEDGER_API_KEY}" \
  "${EVENTLEDGER_URL}/api/v1/reconciliation/runs/${RUN_ID}" | jq

curl --fail --silent --show-error \
  --header "X-API-Key: ${EVENTLEDGER_API_KEY}" \
  "${EVENTLEDGER_URL}/api/v1/reconciliation/runs/latest" | jq
```

A clean completed response has `status: "COMPLETED"`,
`discrepancyCount: 0`, a non-null `completedAt`, and no `errorMessage`. HTTP
409 means another task holds the advisory lock; inspect that run rather than
starting concurrent checks.

## Recover

1. Stop the affected replay or consumer path without taking unrelated payment
   traffic offline.
2. Identify the first bad write and the application/schema version that made
   it.
3. Reproduce on a sanitized snapshot or deterministic test fixture.
4. Deploy the code or data-migration fix through review.
5. Apply compensating journal transactions; never rewrite posted history.
6. Replay only the bounded event range after confirming event-ID
   deduplication.
7. Run reconciliation for the affected window, then a full reconciliation.

Close only after the ledger is balanced, materialized balances equal journal
sums, the outbox and DLT have been accounted for, and two consecutive full
runs report zero mismatches. Retain before/after query results, correction
transaction IDs, event offsets, approvals, and reconciliation run IDs.

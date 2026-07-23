# k6 idempotency load test

`idempotency.js` creates a unique payment command per iteration, immediately
replays the exact request with the same `Idempotency-Key`, and queries the
returned payment. The run fails if any replay resolves to a different logical
payment ID.

The conservative defaults generate five logical payments per second for 30
seconds, with an amount of EUR 0.01. Each iteration performs three HTTP
requests. Ensure the seeded source account has enough balance for the selected
rate, duration, and amount.

Protected calls use `EVENTLEDGER_API_KEY`; its default is
`local-development-key`, matching the Compose stack.

```bash
./scripts/run-k6.sh
```

Common overrides:

```bash
EVENTLEDGER_RATE=20 \
EVENTLEDGER_DURATION=2m \
EVENTLEDGER_PRE_ALLOCATED_VUS=30 \
EVENTLEDGER_MAX_VUS=100 \
./scripts/run-k6.sh
```

The test also accepts `EVENTLEDGER_BASE_URL`,
`EVENTLEDGER_API_KEY`,
`EVENTLEDGER_SOURCE_ACCOUNT_ID`, `EVENTLEDGER_DESTINATION_ACCOUNT_ID`,
`EVENTLEDGER_PAYMENT_AMOUNT`, `EVENTLEDGER_CURRENCY`, and
`EVENTLEDGER_ITERATION_PAUSE_SECONDS`.

Use dedicated accounts for sustained or shared-environment runs. The script
creates real ledger entries and does not attempt to delete or reverse them.

# EventLedger black-box tests

These pytest scenarios exercise the public HTTP API against a separately running
EventLedger instance. They cover sequential and concurrent idempotent replays,
idempotency-key conflicts, exactly-once balance effects, reconciliation, process
crashes, and temporary Kafka/PostgreSQL outages.

Protected API calls use `EVENTLEDGER_API_KEY`, which defaults to
`local-development-key` to match Docker Compose.

The normal integration suite is opt-in:

```bash
./scripts/bootstrap-python-tests.sh
EVENTLEDGER_RUN_INTEGRATION=1 ./scripts/run-python-tests.sh
```

The defaults use the two demo account IDs listed in
`resilience.env.example`. Every value can be overridden with an environment
variable. The service must be healthy before the suite starts, and the source
account must have enough available EUR balance.

## Fault injection

Fault tests are intentionally disabled unless both integration and fault flags
are enabled:

```bash
EVENTLEDGER_RUN_INTEGRATION=1 \
EVENTLEDGER_RUN_FAULT_TESTS=1 \
./scripts/run-python-tests.sh
```

Configure all commands used by a selected scenario first. The example file uses
Docker Compose service names only as illustrations. Commands are JSON arrays,
not shell snippets: the harness parses the array and passes it directly to the
operating system with `shell=False`. It never evaluates pipes, redirections,
substitutions, or command separators.

To load a reviewed copy of the example into the current shell:

```bash
set -a
source tests/resilience.env.example
set +a
```

Run fault tests only against disposable local or CI infrastructure. The process
test kills the application; the Kafka and PostgreSQL tests stop their respective
services and restore them in `finally` blocks. A host termination can still
prevent cleanup, so verify service state after an interrupted run.

To run just one scenario:

```bash
EVENTLEDGER_RUN_INTEGRATION=1 \
EVENTLEDGER_RUN_FAULT_TESTS=1 \
./scripts/run-python-tests.sh -k kafka_outage
```

Tests marked `integration` are skipped when the opt-in flag is absent. Tests
marked `fault` additionally require the fault flag and the commands relevant to
that scenario.

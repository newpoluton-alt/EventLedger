# Application process failure and recovery

## Triage

1. Check load-balancer target health and the ECS deployment event stream.
2. Compare crash start time with the last image, task-definition, secret, or
   infrastructure change.
3. Inspect CloudWatch logs for out-of-memory exits, failed health checks,
   blocked startup migrations, and dependency timeouts.
4. Use trace IDs and idempotency keys to determine whether affected requests
   committed before the process exited.

The ECS service starts replacement tasks and its deployment circuit breaker
rolls back failed releases. Avoid starting another rollout until the current
deployment reaches a terminal state.

## Correctness checks

A crash can happen:

- before the database transaction commits: retrying the same idempotency key
  may create the one intended payment;
- after commit but before the HTTP response: retrying the same key must return
  the already committed result;
- after Kafka accepts an outbox event but before it is marked published: the
  publisher may resend it, and the consumer must deduplicate the event ID.

Do not infer transaction outcome from an HTTP timeout. Query the idempotency
record or payment status using the supported API, then retry only with the
original key.

## Local crash drill

```bash
docker compose up --build -d
docker compose kill --signal=KILL app
docker compose up -d app
docker compose logs --since=5m app
```

Run the drill while sending repeated requests with one idempotency key. It
passes when one payment and one balanced journal transaction exist, outbox
publication resumes, consumers apply the event once, and reconciliation finds
no mismatch.

## Close

Verify stable target health, a drained outbox, normal consumer lag, no new DLT
records, and two successful reconciliation runs. Preserve the task stop reason,
trace and request IDs, deployment events, and the corrected release identifier.

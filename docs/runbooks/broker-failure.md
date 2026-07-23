# Broker failure and outbox recovery

## Purpose

Use this runbook when Kafka is unavailable, producers time out, the outbox
backlog grows, consumer lag rises, or records accumulate in a dead-letter
topic. Payment acceptance and event publication are separate phases: a
committed payment remains authoritative in PostgreSQL even while Kafka is
unavailable. The transactional outbox must later publish it.

Never delete outbox rows, lower `min.insync.replicas`, enable unclean leader
election, or reset consumer offsets during initial mitigation.

## Detect and assess

1. Confirm API and database health independently of Kafka.
2. Record the incident start time and deployed task definition.
3. Check the `EventLedgerOutboxBacklog` alert, producer error rate, consumer
   lag, MSK `OfflinePartitionsCount`, and under-replicated partitions.
4. Determine whether the impact is producer-only, consumer-only, one topic,
   one availability zone, or the entire cluster.
5. Verify MSK cluster state and recent events:

   ```bash
   aws kafka describe-cluster-v2 --cluster-arn "$MSK_CLUSTER_ARN"
   aws kafka list-cluster-operations --cluster-arn "$MSK_CLUSTER_ARN"
   ```

The API may continue accepting payments while the durable outbox is growing.
Apply traffic controls before PostgreSQL storage or the agreed recovery-time
budget is threatened.

## Recover production

1. Check security-group, subnet, DNS, certificate, and SCRAM-secret changes.
   A simultaneous failure across every broker is usually a client path or
   credential problem.
2. If MSK reports `ACTIVE` and brokers are healthy, compare a failing task's
   bootstrap servers and SASL configuration with the Terraform outputs.
3. Roll back the last application or infrastructure deployment if its timing
   matches the incident. Let the ECS deployment circuit breaker complete; do
   not start overlapping deployments.
4. If AWS reports impaired brokers, open an AWS support case and let MSK
   replace them. Do not attempt destructive topic maintenance.
5. After connectivity returns, watch the outbox backlog decrease, consumer lag
   converge, and database load remain within bounds. Temporarily scale workers
   only if the database and downstream consumers have safe headroom.
6. Keep the incident open until reconciliation reports zero mismatches for two
   consecutive runs.

Retries can publish a record more than once if the process crashes after Kafka
accepts the record but before the outbox row is marked published. This is
expected. Consumers must enforce their inbox or business idempotency key before
applying a ledger mutation.

### Requeue an exhausted outbox event

An outbox row in `DEAD` is not a Kafka DLT record. Requeue it only after fixing
the broker or payload problem, confirming its immutable event ID, and obtaining
incident-commander approval. Use one exact ID per reviewed transaction:

```sql
\set event_id '00000000-0000-0000-0000-000000000000'
BEGIN;
SELECT id, aggregate_id, event_type, attempt_count, last_error
FROM outbox_events
WHERE id = :'event_id'::uuid AND status = 'DEAD'
FOR UPDATE;

UPDATE outbox_events
SET status = 'PENDING',
    attempt_count = 0,
    available_at = clock_timestamp(),
    locked_by = NULL,
    locked_until = NULL,
    last_error = NULL
WHERE id = :'event_id'::uuid AND status = 'DEAD';
COMMIT;
```

Require exactly one updated row. Preserve the before/after output in the
incident record, then watch for publication and run reconciliation. The event
may have reached Kafka before it was marked dead, so its original event ID must
remain unchanged and the consumer must deduplicate it.

## Dead-letter recovery

Before replaying a DLT:

1. classify a representative sample and fix the root cause;
2. confirm payload compatibility and the target topic;
3. record the source topic, partition, offset range, and replay owner;
4. verify the destination consumer deduplicates the event ID;
5. replay one reviewed incident through the authenticated recovery endpoint;
6. run reconciliation and retain the replay response;
7. repeat as a small bounded batch;
8. increase the rate gradually while watching database saturation and lag.

Never reuse an event ID for a semantically different payment. Never purge the
DLT until the replay audit and reconciliation evidence are retained.

```bash
curl --fail-with-body \
  --request POST \
  --header "X-API-Key: $EVENTLEDGER_API_KEY" \
  "https://eventledger.example/api/v1/dead-letters/$INCIDENT_ID/replay"
```

The endpoint accepts only an `OPEN` incident. It locks the incident and payment,
validates the stored settlement envelope, applies settlement and its outbox
event, and changes the incident to `REPLAYED` in one database transaction.
Malformed, unknown-payment, future-dated, or contradictory events return `422`
and remain open for investigation.

## Local failure drill

```bash
docker compose up --build -d
docker compose stop kafka
# Submit a payment with a stable Idempotency-Key and observe the durable outbox.
docker compose start kafka
docker compose logs --since=5m app kafka
```

Pass criteria:

- the API never creates two payments for the same idempotency key;
- ledger rows remain balanced while Kafka is stopped;
- the unpublished outbox count increases and then returns to zero;
- every logical event is applied once by consumers despite delivery retries;
- reconciliation finds no mismatch.

## Evidence to retain

- relevant trace IDs and idempotency keys, with customer data redacted;
- MSK events and broker metrics;
- outbox depth and oldest-row age over time;
- consumer lag and DLT offset ranges;
- reconciliation run identifiers before and after recovery.

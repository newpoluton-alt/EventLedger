# Database failure and recovery

## Purpose

Use this runbook for PostgreSQL connection exhaustion, high latency, storage
pressure, a Multi-AZ failover, or an unavailable primary. PostgreSQL is the
system of record. Prefer rejecting or slowing writes to accepting a payment
whose durable accounting result is uncertain.

Never manually edit ledger balances, delete journal entries, disable
constraints, or promote an unverified snapshot.

## Detect and assess

1. Record the incident start time, current release, and last schema migration.
2. Check readiness failures, Hikari pool metrics, request latency, RDS events,
   CPU, connections, free storage, replica lag, and transaction age.
3. Inspect RDS state:

   ```bash
   aws rds describe-db-instances --db-instance-identifier "$DB_INSTANCE_ID"
   aws rds describe-events \
     --source-type db-instance \
     --source-identifier "$DB_INSTANCE_ID" \
     --duration 180
   ```

4. Classify the failure as application pool exhaustion, blocking/long
   transactions, network or credential failure, storage exhaustion, or an RDS
   infrastructure event.
5. Preserve evidence before terminating sessions. Capture only sanitized query
   text and identifiers.

## Mitigate

- **Connection exhaustion:** stop deployment churn, identify which task or
  query owns the connections, and reduce application concurrency before
  increasing `max_connections`. Scaling ECS out can make this worse.
- **Blocking transaction:** identify the owner and business operation. Cancel a
  query before terminating its session; terminate only with incident-commander
  approval.
- **Storage pressure:** confirm storage autoscaling status and the maximum
  allocation. Increase the Terraform limit through a reviewed change. Do not
  delete journal or outbox history as emergency cleanup.
- **Bad migration:** stop further rollouts. Use a tested forward-fix when
  possible; schema and application rollback must remain compatible.
- **RDS impairment:** allow automatic Multi-AZ failover. If AWS support directs
  or the documented recovery objective requires it, an authorized operator can
  initiate a failover:

  ```bash
  aws rds reboot-db-instance \
    --db-instance-identifier "$DB_INSTANCE_ID" \
    --force-failover
  ```

Expect in-flight transactions to roll back and clients to reconnect. API
retries are safe only when they reuse the original idempotency key.

## Restore from backup

Point-in-time restore creates a new instance; it does not overwrite production.

1. select a restore time before the confirmed corruption;
2. restore into isolated security groups;
3. apply the same parameter settings and verify TLS;
4. run schema, balanced-ledger, outbox, and reconciliation checks;
5. compare the expected recovery-point loss with upstream evidence;
6. obtain incident-commander and data-owner approval before cutover;
7. retain the old instance read-only until the audit is complete.

Never merge ledgers from two independently writable primaries.

## Local failure drill

```bash
docker compose up --build -d
docker compose pause postgres
# Retry one request with the exact same Idempotency-Key while the DB is paused.
docker compose unpause postgres
docker compose logs --since=5m app postgres
```

Pass criteria:

- requests fail clearly or time out within the configured bound;
- no success is returned before the transaction commits;
- retrying the original key returns the one committed result;
- every journal transaction remains balanced;
- process recovery resumes outbox publication;
- reconciliation reports zero mismatches.

## Close

Keep the incident open until readiness is stable, pool saturation is normal,
the outbox has drained, all retried idempotency keys resolve to one payment,
and two consecutive reconciliation runs pass. Retain RDS events, relevant
traces, migration records, and reconciliation run IDs.

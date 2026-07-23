package dev.eventledger.reconciliation

import dev.eventledger.config.EventLedgerProperties
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ReconciliationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun tryAcquireLock(): Boolean =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(:lockId)",
            mapOf("lockId" to RECONCILIATION_LOCK_ID),
            Boolean::class.java,
        ) ?: false

    fun insertRun(
        id: UUID,
        trigger: ReconciliationTrigger,
        status: ReconciliationStatus,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO reconciliation_runs (
                id,
                status,
                trigger_type,
                started_at,
                completed_at
            )
            VALUES (
                :id,
                :status,
                :trigger,
                :now,
                CASE
                    WHEN CAST(:status AS varchar) = 'SKIPPED'
                    THEN CAST(:now AS timestamptz)
                    ELSE NULL
                END
            )
            """.trimIndent(),
            mapOf(
                "id" to id,
                "status" to status.name,
                "trigger" to trigger.name,
                "now" to Timestamp.from(now),
            ),
        )
    }

    fun findById(id: UUID): ReconciliationRun? =
        jdbc
            .query(
                runSelect("WHERE id = :id"),
                mapOf("id" to id),
                ::mapRun,
            ).firstOrNull()

    fun findLatest(): ReconciliationRun? =
        jdbc
            .query(
                runSelect("ORDER BY started_at DESC LIMIT 1"),
                emptyMap<String, Any>(),
                ::mapRun,
            ).firstOrNull()

    fun detect(
        now: Instant,
        properties: EventLedgerProperties.Reconciliation,
    ): List<DiscrepancyCandidate> {
        val candidates = mutableListOf<DiscrepancyCandidate>()
        candidates +=
            queryCandidates(
                """
                SELECT
                    le.id AS aggregate_id,
                    '{"balanced":true,"minimumPostings":2}'::jsonb::text AS expected_json,
                    jsonb_build_object(
                        'debitMinor', COALESCE(SUM(lp.amount_minor) FILTER (WHERE lp.side = 'DEBIT'), 0),
                        'creditMinor', COALESCE(SUM(lp.amount_minor) FILTER (WHERE lp.side = 'CREDIT'), 0),
                        'postingCount', COUNT(lp.entry_id)
                    )::text AS actual_json
                FROM ledger_entries le
                LEFT JOIN ledger_postings lp ON lp.entry_id = le.id
                GROUP BY le.id
                HAVING
                    COUNT(lp.entry_id) < 2
                    OR COALESCE(SUM(lp.amount_minor) FILTER (WHERE lp.side = 'DEBIT'), 0)
                       <> COALESCE(SUM(lp.amount_minor) FILTER (WHERE lp.side = 'CREDIT'), 0)
                """.trimIndent(),
                "UNBALANCED_LEDGER_ENTRY",
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    p.id AS aggregate_id,
                    jsonb_build_object(
                        'ledgerEntryCount', 1,
                        'postingCount', 2,
                        'entryType', 'PAYMENT',
                        'sourceDebitMinor', p.amount_minor,
                        'destinationCreditMinor', p.amount_minor,
                        'currency', p.currency
                    )::text AS expected_json,
                    jsonb_build_object(
                        'ledgerEntryCount', COUNT(DISTINCT le.id),
                        'postingCount', COUNT(lp.entry_id),
                        'matchingSourceDebits',
                            COUNT(lp.entry_id) FILTER (
                                WHERE lp.side = 'DEBIT'
                                  AND le.entry_type = 'PAYMENT'
                                  AND lp.account_id = p.source_account_id
                                  AND lp.amount_minor = p.amount_minor
                                  AND le.currency = p.currency
                            ),
                        'matchingDestinationCredits',
                            COUNT(lp.entry_id) FILTER (
                                WHERE lp.side = 'CREDIT'
                                  AND le.entry_type = 'PAYMENT'
                                  AND lp.account_id = p.destination_account_id
                                  AND lp.amount_minor = p.amount_minor
                                  AND le.currency = p.currency
                            )
                    )::text AS actual_json
                FROM payments p
                LEFT JOIN ledger_entries le ON le.payment_id = p.id
                LEFT JOIN ledger_postings lp ON lp.entry_id = le.id
                GROUP BY
                    p.id,
                    p.amount_minor,
                    p.currency,
                    p.source_account_id,
                    p.destination_account_id
                HAVING
                    COUNT(DISTINCT le.id) <> 1
                    OR COUNT(lp.entry_id) <> 2
                    OR COUNT(lp.entry_id) FILTER (
                        WHERE lp.side = 'DEBIT'
                          AND le.entry_type = 'PAYMENT'
                          AND lp.account_id = p.source_account_id
                          AND lp.amount_minor = p.amount_minor
                          AND le.currency = p.currency
                    ) <> 1
                    OR COUNT(lp.entry_id) FILTER (
                        WHERE lp.side = 'CREDIT'
                          AND le.entry_type = 'PAYMENT'
                          AND lp.account_id = p.destination_account_id
                          AND lp.amount_minor = p.amount_minor
                          AND le.currency = p.currency
                    ) <> 1
                """.trimIndent(),
                "PAYMENT_LEDGER_MISMATCH",
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    a.id AS aggregate_id,
                    '{"balanceProjectionPresent":true}'::jsonb::text AS expected_json,
                    '{"balanceProjectionPresent":false}'::jsonb::text AS actual_json
                FROM accounts a
                LEFT JOIN account_balances b ON b.account_id = a.id
                WHERE b.account_id IS NULL
                """.trimIndent(),
                "MISSING_BALANCE_PROJECTION",
            )
        candidates +=
            queryCandidates(
                """
                WITH derived AS (
                    SELECT
                        a.id,
                        COALESCE(
                            SUM(
                                CASE lp.side
                                    WHEN 'CREDIT' THEN lp.amount_minor
                                    ELSE -lp.amount_minor
                                END
                            ),
                            0
                        ) AS balance_minor
                    FROM accounts a
                    LEFT JOIN ledger_postings lp ON lp.account_id = a.id
                    GROUP BY a.id
                )
                SELECT
                    b.account_id AS aggregate_id,
                    jsonb_build_object('balanceMinor', d.balance_minor)::text AS expected_json,
                    jsonb_build_object('balanceMinor', b.balance_minor)::text AS actual_json
                FROM account_balances b
                JOIN derived d ON d.id = b.account_id
                WHERE b.balance_minor <> d.balance_minor
                """.trimIndent(),
                "BALANCE_PROJECTION_DRIFT",
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    p.id AS aggregate_id,
                    '{"paymentPostedEventCount":1,"validEnvelopeCount":1}'::jsonb::text AS expected_json,
                    jsonb_build_object(
                        'paymentPostedEventCount', COUNT(o.id),
                        'validEnvelopeCount',
                            COUNT(o.id) FILTER (
                                WHERE o.aggregate_sequence = 1
                                  AND o.event_version = 1
                                  AND o.payload ->> 'eventType' = 'payment.posted.v1'
                                  AND o.payload ->> 'eventVersion' = '1'
                                  AND o.payload ->> 'aggregateId' = p.id::text
                                  AND o.payload #>> '{payload,paymentId}' = p.id::text
                                  AND o.payload #>> '{payload,sourceAccountId}' = p.source_account_id::text
                                  AND o.payload #>> '{payload,destinationAccountId}' = p.destination_account_id::text
                                  AND o.payload #>> '{payload,amountMinor}' = p.amount_minor::text
                                  AND o.payload #>> '{payload,currency}' = p.currency
                            )
                    )::text AS actual_json
                FROM payments p
                LEFT JOIN outbox_events o
                    ON o.aggregate_id = p.id
                   AND o.event_type = 'payment.posted.v1'
                GROUP BY
                    p.id,
                    p.source_account_id,
                    p.destination_account_id,
                    p.amount_minor,
                    p.currency
                HAVING
                    COUNT(o.id) <> 1
                    OR COUNT(o.id) FILTER (
                        WHERE o.aggregate_sequence = 1
                          AND o.event_version = 1
                          AND o.payload ->> 'eventType' = 'payment.posted.v1'
                          AND o.payload ->> 'eventVersion' = '1'
                          AND o.payload ->> 'aggregateId' = p.id::text
                          AND o.payload #>> '{payload,paymentId}' = p.id::text
                          AND o.payload #>> '{payload,sourceAccountId}' = p.source_account_id::text
                          AND o.payload #>> '{payload,destinationAccountId}' = p.destination_account_id::text
                          AND o.payload #>> '{payload,amountMinor}' = p.amount_minor::text
                          AND o.payload #>> '{payload,currency}' = p.currency
                    ) <> 1
                """.trimIndent(),
                "MISSING_PAYMENT_OUTBOX_EVENT",
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    p.id AS aggregate_id,
                    jsonb_build_object(
                        'status', p.status,
                        'settlementEventCount', CASE WHEN p.status = 'SETTLED' THEN 1 ELSE 0 END,
                        'paymentSettledEventCount', CASE WHEN p.status = 'SETTLED' THEN 1 ELSE 0 END,
                        'validEnvelopeCount', CASE WHEN p.status = 'SETTLED' THEN 1 ELSE 0 END,
                        'settledAt', p.settled_at
                    )::text AS expected_json,
                    jsonb_build_object(
                        'status', p.status,
                        'settlementEventCount', COUNT(DISTINCT s.event_id),
                        'paymentSettledEventCount', COUNT(DISTINCT o.id),
                        'validEnvelopeCount',
                            COUNT(DISTINCT o.id) FILTER (
                                WHERE o.aggregate_sequence = 2
                                  AND o.event_version = 1
                                  AND o.payload ->> 'eventType' = 'payment.settled.v1'
                                  AND o.payload ->> 'eventVersion' = '1'
                                  AND o.payload ->> 'aggregateId' = p.id::text
                                  AND o.payload #>> '{payload,paymentId}' = p.id::text
                                  AND (
                                      o.payload #>> '{payload,providerReference}'
                                      IS NOT DISTINCT FROM s.provider_reference
                                  )
                                  AND CASE
                                      WHEN o.payload #>> '{payload,settledAt}'
                                          ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})${'$'}'
                                      THEN (o.payload #>> '{payload,settledAt}')::timestamptz = p.settled_at
                                      ELSE FALSE
                                  END
                            ),
                        'settlementSettledAt', MIN(s.settled_at),
                        'paymentSettledAt', p.settled_at
                    )::text AS actual_json
                FROM payments p
                LEFT JOIN settlement_events s ON s.payment_id = p.id
                LEFT JOIN outbox_events o
                    ON o.aggregate_id = p.id
                   AND o.event_type = 'payment.settled.v1'
                GROUP BY p.id, p.status, p.settled_at
                HAVING
                    (
                        p.status = 'SETTLED'
                        AND (
                            COUNT(DISTINCT s.event_id) <> 1
                            OR COUNT(DISTINCT o.id) <> 1
                            OR COUNT(DISTINCT o.id) FILTER (
                                WHERE o.aggregate_sequence = 2
                                  AND o.event_version = 1
                                  AND o.payload ->> 'eventType' = 'payment.settled.v1'
                                  AND o.payload ->> 'eventVersion' = '1'
                                  AND o.payload ->> 'aggregateId' = p.id::text
                                  AND o.payload #>> '{payload,paymentId}' = p.id::text
                                  AND (
                                      o.payload #>> '{payload,providerReference}'
                                      IS NOT DISTINCT FROM s.provider_reference
                                  )
                                  AND CASE
                                      WHEN o.payload #>> '{payload,settledAt}'
                                          ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})${'$'}'
                                      THEN (o.payload #>> '{payload,settledAt}')::timestamptz = p.settled_at
                                      ELSE FALSE
                                  END
                            ) <> 1
                            OR p.settled_at IS NULL
                            OR p.settled_at <> MIN(s.settled_at)
                        )
                    )
                    OR (
                        p.status <> 'SETTLED'
                        AND (
                            COUNT(DISTINCT s.event_id) <> 0
                            OR COUNT(DISTINCT o.id) <> 0
                            OR p.settled_at IS NOT NULL
                        )
                    )
                """.trimIndent(),
                "PAYMENT_SETTLEMENT_MISMATCH",
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    aggregate_id,
                    '{"stale":false}'::jsonb::text AS expected_json,
                    jsonb_build_object(
                        'eventId', id,
                        'status', status,
                        'attemptCount', attempt_count,
                        'availableAt', available_at
                    )::text AS actual_json
                FROM outbox_events
                WHERE status IN ('PENDING', 'IN_FLIGHT', 'DEAD')
                  AND occurred_at < :staleBefore
                """.trimIndent(),
                "STALE_OUTBOX_EVENT",
                mapOf(
                    "staleBefore" to
                        Timestamp.from(
                            now.minus(properties.staleOutboxAge),
                        ),
                ),
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    p.id AS aggregate_id,
                    '{"settlementPending":false}'::jsonb::text AS expected_json,
                    jsonb_build_object('status', p.status, 'updatedAt', p.updated_at)::text AS actual_json
                FROM payments p
                WHERE p.status = 'POSTED'
                  AND p.updated_at < :staleBefore
                """.trimIndent(),
                "STALE_POSTED_PAYMENT",
                mapOf(
                    "staleBefore" to
                        Timestamp.from(
                            now.minus(properties.stalePaymentAge),
                        ),
                ),
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    b.account_id AS aggregate_id,
                    '{"negativeBalance":false}'::jsonb::text AS expected_json,
                    jsonb_build_object('balanceMinor', balance_minor)::text AS actual_json
                FROM account_balances b
                JOIN accounts a ON a.id = b.account_id
                WHERE a.allow_negative = FALSE
                  AND b.balance_minor < 0
                """.trimIndent(),
                "NEGATIVE_CUSTOMER_BALANCE",
            )
        candidates +=
            queryCandidates(
                """
                SELECT
                    COALESCE(payment_id, id) AS aggregate_id,
                    '{"openIncident":false}'::jsonb::text AS expected_json,
                    jsonb_build_object(
                        'incidentId', id,
                        'topic', original_topic,
                        'error', error_message
                    )::text AS actual_json
                FROM dead_letter_incidents
                WHERE status = 'OPEN'
                """.trimIndent(),
                "OPEN_DEAD_LETTER_INCIDENT",
            )
        return candidates
    }

    fun insertDiscrepancies(
        runId: UUID,
        candidates: List<DiscrepancyCandidate>,
        now: Instant,
    ) {
        if (candidates.isEmpty()) {
            return
        }
        jdbc.batchUpdate(
            """
            INSERT INTO reconciliation_discrepancies (
                id,
                run_id,
                discrepancy_type,
                aggregate_id,
                expected,
                actual,
                status,
                detected_at
            )
            VALUES (
                :id,
                :runId,
                :type,
                :aggregateId,
                CAST(:expected AS jsonb),
                CAST(:actual AS jsonb),
                'OPEN',
                :now
            )
            """.trimIndent(),
            candidates
                .map { candidate ->
                    MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("runId", runId)
                        .addValue("type", candidate.type)
                        .addValue("aggregateId", candidate.aggregateId)
                        .addValue("expected", candidate.expectedJson)
                        .addValue("actual", candidate.actualJson)
                        .addValue("now", Timestamp.from(now))
                }.toTypedArray(),
        )
    }

    fun completeRun(
        runId: UUID,
        completedAt: Instant,
        discrepancyCount: Int,
    ) {
        jdbc.update(
            """
            UPDATE reconciliation_runs
            SET status = 'COMPLETED',
                completed_at = :completedAt,
                discrepancy_count = :discrepancyCount
            WHERE id = :runId
              AND status = 'RUNNING'
            """.trimIndent(),
            mapOf(
                "runId" to runId,
                "completedAt" to Timestamp.from(completedAt),
                "discrepancyCount" to discrepancyCount,
            ),
        )
    }

    private fun queryCandidates(
        sql: String,
        type: String,
        parameters: Map<String, Any> = emptyMap(),
    ): List<DiscrepancyCandidate> =
        jdbc.query(sql, parameters) { resultSet, _ ->
            DiscrepancyCandidate(
                type = type,
                aggregateId = resultSet.getObject("aggregate_id", UUID::class.java),
                expectedJson = resultSet.getString("expected_json"),
                actualJson = resultSet.getString("actual_json"),
            )
        }

    private fun runSelect(suffix: String): String =
        """
        SELECT
            id,
            status,
            trigger_type,
            started_at,
            completed_at,
            discrepancy_count,
            error_message
        FROM reconciliation_runs
        $suffix
        """.trimIndent()

    private fun mapRun(
        resultSet: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): ReconciliationRun =
        ReconciliationRun(
            id = resultSet.getObject("id", UUID::class.java),
            status = ReconciliationStatus.valueOf(resultSet.getString("status")),
            triggerType = ReconciliationTrigger.valueOf(resultSet.getString("trigger_type")),
            startedAt = resultSet.getTimestamp("started_at").toInstant(),
            completedAt = resultSet.getTimestamp("completed_at")?.toInstant(),
            discrepancyCount = resultSet.getInt("discrepancy_count"),
            errorMessage = resultSet.getString("error_message"),
        )

    companion object {
        private const val RECONCILIATION_LOCK_ID = 728_346_192_041L
    }
}

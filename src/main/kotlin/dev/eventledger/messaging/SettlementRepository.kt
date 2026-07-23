package dev.eventledger.messaging

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class LockedPayment(
    val status: String,
    val createdAt: Instant,
)

data class SettlementRecord(
    val eventId: UUID,
    val providerReference: String?,
    val settledAt: Instant,
)

data class DeadLetterIncident(
    val id: UUID,
    val paymentId: UUID?,
    val payload: String,
    val status: String,
)

enum class InboxRecordResult {
    RECORDED,
    DUPLICATE,
    CONFLICT,
}

@Repository
class SettlementRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun recordInbox(
        consumerName: String,
        eventId: UUID,
        aggregateId: UUID,
        eventFingerprint: String,
        now: Instant,
    ): InboxRecordResult {
        val inserted =
            jdbc.update(
                """
                INSERT INTO consumer_inbox (
                    consumer_name,
                    event_id,
                    aggregate_id,
                    event_fingerprint,
                    processed_at
                )
                VALUES (
                    :consumerName,
                    :eventId,
                    :aggregateId,
                    :eventFingerprint,
                    :now
                )
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "consumerName" to consumerName,
                    "eventId" to eventId,
                    "aggregateId" to aggregateId,
                    "eventFingerprint" to eventFingerprint,
                    "now" to Timestamp.from(now),
                ),
            ) == 1
        if (inserted) {
            return InboxRecordResult.RECORDED
        }

        val existing =
            jdbc
                .query(
                    """
                    SELECT aggregate_id, event_fingerprint
                    FROM consumer_inbox
                    WHERE consumer_name = :consumerName
                      AND event_id = :eventId
                    """.trimIndent(),
                    mapOf(
                        "consumerName" to consumerName,
                        "eventId" to eventId,
                    ),
                ) { resultSet, _ ->
                    resultSet.getObject("aggregate_id", UUID::class.java) to
                        resultSet.getString("event_fingerprint")
                }.firstOrNull()
                ?: return InboxRecordResult.CONFLICT

        return if (existing.first == aggregateId && existing.second == eventFingerprint) {
            InboxRecordResult.DUPLICATE
        } else {
            InboxRecordResult.CONFLICT
        }
    }

    fun lockPayment(paymentId: UUID): LockedPayment? =
        jdbc
            .query(
                """
                SELECT status, created_at
                FROM payments
                WHERE id = :paymentId
                FOR UPDATE
                """.trimIndent(),
                mapOf("paymentId" to paymentId),
            ) { resultSet, _ ->
                LockedPayment(
                    status = resultSet.getString("status"),
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                )
            }.firstOrNull()

    fun findSettlement(paymentId: UUID): SettlementRecord? =
        jdbc
            .query(
                """
                SELECT event_id, provider_reference, settled_at
                FROM settlement_events
                WHERE payment_id = :paymentId
                """.trimIndent(),
                mapOf("paymentId" to paymentId),
            ) { resultSet, _ ->
                SettlementRecord(
                    eventId = resultSet.getObject("event_id", UUID::class.java),
                    providerReference = resultSet.getString("provider_reference"),
                    settledAt = resultSet.getTimestamp("settled_at").toInstant(),
                )
            }.firstOrNull()

    fun recordSettlement(
        eventId: UUID,
        paymentId: UUID,
        providerReference: String?,
        settledAt: Instant,
        receivedAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO settlement_events (
                event_id,
                payment_id,
                provider_reference,
                settled_at,
                received_at
            )
            VALUES (
                :eventId,
                :paymentId,
                :providerReference,
                :settledAt,
                :receivedAt
            )
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("paymentId", paymentId)
                .addValue("providerReference", providerReference)
                .addValue("settledAt", Timestamp.from(settledAt))
                .addValue("receivedAt", Timestamp.from(receivedAt)),
        )
    }

    fun markPaymentSettled(
        paymentId: UUID,
        settledAt: Instant,
        now: Instant,
        allowNeedsAttention: Boolean,
    ): Boolean =
        jdbc.update(
            """
            UPDATE payments
            SET status = 'SETTLED',
                settled_at = :settledAt,
                updated_at = :now
            WHERE id = :paymentId
              AND (
                  status = 'POSTED'
                  OR (:allowNeedsAttention = TRUE AND status = 'NEEDS_ATTENTION')
              )
            """.trimIndent(),
            mapOf(
                "paymentId" to paymentId,
                "settledAt" to Timestamp.from(settledAt),
                "now" to Timestamp.from(now),
                "allowNeedsAttention" to allowNeedsAttention,
            ),
        ) == 1

    fun recordDeadLetter(
        id: UUID,
        eventId: UUID?,
        paymentId: UUID?,
        originalTopic: String,
        partition: Int,
        offset: Long,
        errorMessage: String,
        payload: String,
        now: Instant,
    ): Boolean {
        val knownPaymentId =
            paymentId?.takeIf { candidate ->
                jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM payments WHERE id = :paymentId)",
                    mapOf("paymentId" to candidate),
                    Boolean::class.java,
                ) ?: false
            }
        val inserted =
            jdbc.update(
                """
                INSERT INTO dead_letter_incidents (
                    id,
                    event_id,
                    payment_id,
                    original_topic,
                    partition_number,
                    offset_number,
                    error_message,
                    payload,
                    status,
                    created_at
                )
                VALUES (
                    :id,
                    :eventId,
                    :paymentId,
                    :originalTopic,
                    :partition,
                    :offset,
                    :errorMessage,
                    CAST(:payload AS jsonb),
                    'OPEN',
                    :now
                )
                ON CONFLICT (original_topic, partition_number, offset_number) DO NOTHING
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("eventId", eventId)
                    .addValue("paymentId", knownPaymentId)
                    .addValue("originalTopic", originalTopic)
                    .addValue("partition", partition)
                    .addValue("offset", offset)
                    .addValue("errorMessage", errorMessage.take(1000))
                    .addValue("payload", payload)
                    .addValue("now", Timestamp.from(now)),
            ) == 1

        if (inserted && knownPaymentId != null) {
            jdbc.update(
                """
                UPDATE payments
                SET status = 'NEEDS_ATTENTION',
                    updated_at = :now
                WHERE id = :paymentId
                  AND status = 'POSTED'
                """.trimIndent(),
                mapOf(
                    "paymentId" to knownPaymentId,
                    "now" to Timestamp.from(now),
                ),
            )
        }
        return inserted
    }

    fun lockDeadLetterIncident(id: UUID): DeadLetterIncident? =
        jdbc
            .query(
                """
                SELECT id, payment_id, payload::text AS payload, status
                FROM dead_letter_incidents
                WHERE id = :id
                FOR UPDATE
                """.trimIndent(),
                mapOf("id" to id),
            ) { resultSet, _ ->
                DeadLetterIncident(
                    id = resultSet.getObject("id", UUID::class.java),
                    paymentId = resultSet.getObject("payment_id", UUID::class.java),
                    payload = resultSet.getString("payload"),
                    status = resultSet.getString("status"),
                )
            }.firstOrNull()

    fun markDeadLetterReplayed(
        id: UUID,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE dead_letter_incidents
            SET status = 'REPLAYED',
                resolved_at = :now
            WHERE id = :id
              AND status = 'OPEN'
            """.trimIndent(),
            mapOf(
                "id" to id,
                "now" to Timestamp.from(now),
            ),
        ) == 1
}

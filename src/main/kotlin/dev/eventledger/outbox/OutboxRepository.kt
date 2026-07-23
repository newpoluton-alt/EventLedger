package dev.eventledger.outbox

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val topic: String,
    val eventKey: String,
    val payload: String,
    val attemptCount: Int,
)

@Repository
class OutboxRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun insert(
        id: UUID,
        aggregateType: String,
        aggregateId: UUID,
        aggregateSequence: Int,
        eventType: String,
        eventVersion: Int,
        topic: String,
        eventKey: String,
        payload: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO outbox_events (
                id,
                aggregate_type,
                aggregate_id,
                aggregate_sequence,
                event_type,
                event_version,
                topic,
                event_key,
                payload,
                status,
                available_at,
                occurred_at
            )
            VALUES (
                :id,
                :aggregateType,
                :aggregateId,
                :aggregateSequence,
                :eventType,
                :eventVersion,
                :topic,
                :eventKey,
                CAST(:payload AS jsonb),
                'PENDING',
                :now,
                :now
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("aggregateSequence", aggregateSequence)
                .addValue("eventType", eventType)
                .addValue("eventVersion", eventVersion)
                .addValue("topic", topic)
                .addValue("eventKey", eventKey)
                .addValue("payload", payload)
                .addValue("now", Timestamp.from(now)),
        )
    }

    @Transactional
    fun claimBatch(
        owner: String,
        limit: Int,
        lockedUntil: Instant,
    ): List<OutboxEvent> =
        jdbc.query(
            """
            WITH candidates AS (
                SELECT candidate.id
                FROM outbox_events candidate
                WHERE (
                    (
                        candidate.status = 'PENDING'
                        AND candidate.available_at <= NOW()
                    ) OR (
                        candidate.status = 'IN_FLIGHT'
                        AND candidate.locked_until < NOW()
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM outbox_events prior
                    WHERE prior.aggregate_id = candidate.aggregate_id
                      AND prior.aggregate_sequence < candidate.aggregate_sequence
                      AND prior.status <> 'PUBLISHED'
                )
                ORDER BY candidate.available_at, candidate.occurred_at, candidate.aggregate_sequence
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            UPDATE outbox_events AS event
            SET status = 'IN_FLIGHT',
                locked_by = :owner,
                locked_until = :lockedUntil,
                attempt_count = event.attempt_count + 1
            FROM candidates
            WHERE event.id = candidates.id
            RETURNING
                event.id,
                event.aggregate_id,
                event.event_type,
                event.topic,
                event.event_key,
                event.payload::text AS payload,
                event.attempt_count
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("limit", limit)
                .addValue("lockedUntil", Timestamp.from(lockedUntil)),
            ::mapEvent,
        )

    fun renewLease(
        eventId: UUID,
        owner: String,
        lockedUntil: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE outbox_events
            SET locked_until = :lockedUntil
            WHERE id = :eventId
              AND status = 'IN_FLIGHT'
              AND locked_by = :owner
            """.trimIndent(),
            mapOf(
                "eventId" to eventId,
                "owner" to owner,
                "lockedUntil" to Timestamp.from(lockedUntil),
            ),
        ) == 1

    fun markPublished(
        eventId: UUID,
        owner: String,
        publishedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = :publishedAt,
                locked_by = NULL,
                locked_until = NULL,
                last_error = NULL
            WHERE id = :eventId
              AND status = 'IN_FLIGHT'
              AND locked_by = :owner
            """.trimIndent(),
            mapOf(
                "eventId" to eventId,
                "owner" to owner,
                "publishedAt" to Timestamp.from(publishedAt),
            ),
        ) == 1

    fun markFailed(
        eventId: UUID,
        owner: String,
        nextAttemptAt: Instant,
        error: String,
        dead: Boolean,
    ): Boolean =
        jdbc.update(
            """
            UPDATE outbox_events
            SET status = :status,
                available_at = :nextAttemptAt,
                locked_by = NULL,
                locked_until = NULL,
                last_error = :error
            WHERE id = :eventId
              AND status = 'IN_FLIGHT'
              AND locked_by = :owner
            """.trimIndent(),
            mapOf(
                "eventId" to eventId,
                "owner" to owner,
                "status" to if (dead) "DEAD" else "PENDING",
                "nextAttemptAt" to Timestamp.from(nextAttemptAt),
                "error" to error.take(1000),
            ),
        ) == 1

    fun pendingCount(): Long =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_events
            WHERE status IN ('PENDING', 'IN_FLIGHT')
            """.trimIndent(),
            emptyMap<String, Any>(),
            Long::class.java,
        ) ?: 0L

    private fun mapEvent(
        resultSet: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): OutboxEvent =
        OutboxEvent(
            id = resultSet.getObject("id", UUID::class.java),
            aggregateId = resultSet.getObject("aggregate_id", UUID::class.java),
            eventType = resultSet.getString("event_type"),
            topic = resultSet.getString("topic"),
            eventKey = resultSet.getString("event_key"),
            payload = resultSet.getString("payload"),
            attemptCount = resultSet.getInt("attempt_count"),
        )
}

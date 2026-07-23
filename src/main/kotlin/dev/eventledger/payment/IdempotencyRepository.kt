package dev.eventledger.payment

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class IdempotencyRecord(
    val requestHash: String,
    val state: String,
    val resourceId: UUID?,
    val responseBody: String?,
)

@Repository
class IdempotencyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun tryStart(
        operation: String,
        key: String,
        requestHash: String,
        now: Instant,
        expiresAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO idempotency_records (
                operation,
                idempotency_key,
                request_hash,
                state,
                created_at,
                expires_at
            )
            VALUES (
                :operation,
                :key,
                :requestHash,
                'IN_PROGRESS',
                :now,
                :expiresAt
            )
            ON CONFLICT (operation, idempotency_key) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("operation", operation)
                .addValue("key", key)
                .addValue("requestHash", requestHash)
                .addValue("now", Timestamp.from(now))
                .addValue("expiresAt", Timestamp.from(expiresAt)),
        ) == 1

    fun find(
        operation: String,
        key: String,
    ): IdempotencyRecord? =
        jdbc
            .query(
                """
                SELECT request_hash, state, resource_id, response_body::text AS response_body
                FROM idempotency_records
                WHERE operation = :operation
                  AND idempotency_key = :key
                """.trimIndent(),
                mapOf("operation" to operation, "key" to key),
            ) { resultSet, _ ->
                IdempotencyRecord(
                    requestHash = resultSet.getString("request_hash"),
                    state = resultSet.getString("state"),
                    resourceId = resultSet.getObject("resource_id", UUID::class.java),
                    responseBody = resultSet.getString("response_body"),
                )
            }.firstOrNull()

    fun complete(
        operation: String,
        key: String,
        resourceId: UUID,
        responseBody: String,
    ) {
        val updated =
            jdbc.update(
                """
                UPDATE idempotency_records
                SET state = 'COMPLETED',
                    resource_id = :resourceId,
                    response_status = 201,
                    response_body = CAST(:responseBody AS jsonb)
                WHERE operation = :operation
                  AND idempotency_key = :key
                  AND state = 'IN_PROGRESS'
                """.trimIndent(),
                mapOf(
                    "operation" to operation,
                    "key" to key,
                    "resourceId" to resourceId,
                    "responseBody" to responseBody,
                ),
            )
        check(updated == 1) { "Idempotency record was not completed exactly once" }
    }
}

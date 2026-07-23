package dev.eventledger.payment

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class PaymentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun insert(
        id: UUID,
        idempotencyKey: String,
        sourceAccountId: UUID,
        destinationAccountId: UUID,
        amountMinor: Long,
        currency: String,
        reference: String?,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO payments (
                id,
                idempotency_key,
                source_account_id,
                destination_account_id,
                amount_minor,
                currency,
                reference,
                status,
                created_at,
                updated_at
            )
            VALUES (
                :id,
                :idempotencyKey,
                :sourceAccountId,
                :destinationAccountId,
                :amountMinor,
                :currency,
                :reference,
                'POSTED',
                :now,
                :now
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("sourceAccountId", sourceAccountId)
                .addValue("destinationAccountId", destinationAccountId)
                .addValue("amountMinor", amountMinor)
                .addValue("currency", currency)
                .addValue("reference", reference)
                .addValue("now", Timestamp.from(now)),
        )
    }

    fun findById(id: UUID): PaymentRecord? =
        jdbc
            .query(
                paymentSelect("WHERE id = :id"),
                mapOf("id" to id),
                ::mapPayment,
            ).firstOrNull()

    fun findByIdempotencyKey(idempotencyKey: String): PaymentRecord? =
        jdbc
            .query(
                paymentSelect("WHERE idempotency_key = :idempotencyKey"),
                mapOf("idempotencyKey" to idempotencyKey),
                ::mapPayment,
            ).firstOrNull()

    private fun paymentSelect(suffix: String): String =
        """
        SELECT
            id,
            idempotency_key,
            source_account_id,
            destination_account_id,
            amount_minor,
            currency,
            reference,
            status,
            created_at,
            settled_at,
            updated_at
        FROM payments
        $suffix
        """.trimIndent()

    private fun mapPayment(
        resultSet: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): PaymentRecord =
        PaymentRecord(
            id = resultSet.getObject("id", UUID::class.java),
            idempotencyKey = resultSet.getString("idempotency_key"),
            sourceAccountId = resultSet.getObject("source_account_id", UUID::class.java),
            destinationAccountId = resultSet.getObject("destination_account_id", UUID::class.java),
            amountMinor = resultSet.getLong("amount_minor"),
            currency = resultSet.getString("currency").trim(),
            reference = resultSet.getString("reference"),
            status = PaymentStatus.valueOf(resultSet.getString("status")),
            createdAt = resultSet.getTimestamp("created_at").toInstant(),
            settledAt = resultSet.getTimestamp("settled_at")?.toInstant(),
            updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
        )
}

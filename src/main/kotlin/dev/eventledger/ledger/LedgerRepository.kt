package dev.eventledger.ledger

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class LedgerRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun postPayment(
        entryId: UUID,
        paymentId: UUID,
        sourceAccountId: UUID,
        destinationAccountId: UUID,
        amountMinor: Long,
        currency: String,
        reference: String?,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO ledger_entries (
                id,
                payment_id,
                entry_type,
                currency,
                reference,
                effective_at,
                created_at
            )
            VALUES (
                :entryId,
                :paymentId,
                'PAYMENT',
                :currency,
                :reference,
                :now,
                :now
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("entryId", entryId)
                .addValue("paymentId", paymentId)
                .addValue("currency", currency)
                .addValue("reference", reference)
                .addValue("now", Timestamp.from(now)),
        )

        val base =
            MapSqlParameterSource()
                .addValue("entryId", entryId)
                .addValue("amountMinor", amountMinor)
                .addValue("now", Timestamp.from(now))
        jdbc.batchUpdate(
            """
            INSERT INTO ledger_postings (
                entry_id,
                line_number,
                account_id,
                side,
                amount_minor,
                created_at
            )
            VALUES (
                :entryId,
                :lineNumber,
                :accountId,
                :side,
                :amountMinor,
                :now
            )
            """.trimIndent(),
            arrayOf(
                MapSqlParameterSource(base.values)
                    .addValue("lineNumber", 1)
                    .addValue("accountId", sourceAccountId)
                    .addValue("side", "DEBIT"),
                MapSqlParameterSource(base.values)
                    .addValue("lineNumber", 2)
                    .addValue("accountId", destinationAccountId)
                    .addValue("side", "CREDIT"),
            ),
        )
    }
}

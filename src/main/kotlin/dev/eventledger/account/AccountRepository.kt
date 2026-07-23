package dev.eventledger.account

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class AccountRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun insert(
        id: UUID,
        ownerReference: String,
        type: AccountType,
        currency: String,
        now: Instant,
    ) {
        val parameters =
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerReference", ownerReference)
                .addValue("accountType", type.name)
                .addValue("currency", currency)
                .addValue("now", Timestamp.from(now))

        jdbc.update(
            """
            INSERT INTO accounts (
                id, owner_reference, account_type, currency, status, allow_negative, created_at
            )
            VALUES (
                :id, :ownerReference, :accountType, :currency, 'ACTIVE', FALSE, :now
            )
            """.trimIndent(),
            parameters,
        )
        jdbc.update(
            """
            INSERT INTO account_balances (account_id, balance_minor, version, updated_at)
            VALUES (:id, 0, 0, :now)
            """.trimIndent(),
            parameters,
        )
    }

    fun findById(id: UUID): AccountRecord? =
        jdbc
            .query(
                accountSelect("WHERE a.id = :id"),
                mapOf("id" to id),
                ::mapAccount,
            ).firstOrNull()

    fun lockForTransfer(ids: Set<UUID>): List<AccountRecord> =
        jdbc.query(
            accountSelect(
                """
                WHERE a.id IN (:ids)
                ORDER BY a.id
                FOR UPDATE OF a, b
                """.trimIndent(),
            ),
            mapOf("ids" to ids),
            ::mapAccount,
        )

    fun applyTransfer(
        sourceAccountId: UUID,
        destinationAccountId: UUID,
        amountMinor: Long,
        now: Instant,
    ) {
        val parameters =
            mapOf(
                "sourceAccountId" to sourceAccountId,
                "destinationAccountId" to destinationAccountId,
                "amountMinor" to amountMinor,
                "now" to Timestamp.from(now),
            )

        val sourceRows =
            jdbc.update(
                """
                UPDATE account_balances
                SET balance_minor = balance_minor - :amountMinor,
                    version = version + 1,
                    updated_at = :now
                WHERE account_id = :sourceAccountId
                """.trimIndent(),
                parameters,
            )
        val destinationRows =
            jdbc.update(
                """
                UPDATE account_balances
                SET balance_minor = balance_minor + :amountMinor,
                    version = version + 1,
                    updated_at = :now
                WHERE account_id = :destinationAccountId
                """.trimIndent(),
                parameters,
            )

        check(sourceRows == 1 && destinationRows == 1) {
            "Expected exactly one balance row for each transfer account"
        }
    }

    fun derivedBalance(accountId: UUID): Long? =
        jdbc
            .query(
                """
                SELECT
                    a.id,
                    COALESCE(
                        SUM(
                            CASE p.side
                                WHEN 'CREDIT' THEN p.amount_minor
                                ELSE -p.amount_minor
                            END
                        ),
                        0
                    ) AS balance_minor
                FROM accounts a
                LEFT JOIN ledger_postings p ON p.account_id = a.id
                WHERE a.id = :accountId
                GROUP BY a.id
                """.trimIndent(),
                mapOf("accountId" to accountId),
            ) { resultSet, _ -> resultSet.getLong("balance_minor") }
            .firstOrNull()

    private fun accountSelect(suffix: String): String =
        """
        SELECT
            a.id,
            a.owner_reference,
            a.account_type,
            a.currency,
            a.status,
            a.allow_negative,
            a.created_at,
            b.balance_minor,
            b.version,
            b.updated_at
        FROM accounts a
        JOIN account_balances b ON b.account_id = a.id
        $suffix
        """.trimIndent()

    private fun mapAccount(
        resultSet: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): AccountRecord =
        AccountRecord(
            id = resultSet.getObject("id", UUID::class.java),
            ownerReference = resultSet.getString("owner_reference"),
            accountType = AccountType.valueOf(resultSet.getString("account_type")),
            currency = resultSet.getString("currency").trim(),
            status = AccountStatus.valueOf(resultSet.getString("status")),
            allowNegative = resultSet.getBoolean("allow_negative"),
            balanceMinor = resultSet.getLong("balance_minor"),
            version = resultSet.getLong("version"),
            createdAt = resultSet.getTimestamp("created_at").toInstant(),
            updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
        )
}

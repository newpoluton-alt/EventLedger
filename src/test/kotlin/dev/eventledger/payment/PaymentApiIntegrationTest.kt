package dev.eventledger.payment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.eventledger.messaging.DeadLetterService
import dev.eventledger.messaging.InvalidEventException
import dev.eventledger.messaging.SettlementEventParser
import dev.eventledger.messaging.SettlementMessage
import dev.eventledger.messaging.SettlementService
import dev.eventledger.reconciliation.ReconciliationService
import dev.eventledger.reconciliation.ReconciliationTrigger
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var reconciliationService: ReconciliationService

    @Autowired
    private lateinit var deadLetterService: DeadLetterService

    @Autowired
    private lateinit var settlementService: SettlementService

    @Autowired
    private lateinit var settlementEventParser: SettlementEventParser

    @Test
    fun `duplicate request returns the original result and posts only once`() {
        val key = "duplicate-${UUID.randomUUID()}"
        val sourceBefore = balance(SOURCE_ACCOUNT_ID)
        val destinationBefore = balance(DESTINATION_ACCOUNT_ID)

        val first =
            createPayment(key, amount = "12.34")
                .andExpect(status().isCreated)
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn()
        val replay =
            createPayment(key, amount = "12.34")
                .andExpect(status().isCreated)
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andReturn()

        val firstBody = json(first)
        val replayBody = json(replay)
        val paymentId = UUID.fromString(firstBody["id"].asText())

        assertEquals(firstBody, replayBody)
        assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE id = :id", paymentId))
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = :id", paymentId))
        assertEquals(
            2,
            count(
                """
                SELECT COUNT(*)
                FROM ledger_postings posting
                JOIN ledger_entries entry ON entry.id = posting.entry_id
                WHERE entry.payment_id = :id
                """.trimIndent(),
                paymentId,
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :id", paymentId))
        assertEquals(sourceBefore - 1_234, balance(SOURCE_ACCOUNT_ID))
        assertEquals(destinationBefore + 1_234, balance(DESTINATION_ACCOUNT_ID))
    }

    @Test
    fun `same idempotency key with a different payload is rejected`() {
        val key = "conflict-${UUID.randomUUID()}"
        createPayment(key, amount = "1.00")
            .andExpect(status().isCreated)

        createPayment(key, amount = "2.00")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
    }

    @Test
    fun `idempotent replay returns the original response after payment state changes`() {
        val key = "stable-replay-${UUID.randomUUID()}"
        val original =
            createPayment(key, amount = "1.25")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn()
        val paymentId = UUID.fromString(json(original)["id"].asText())
        jdbc.update(
            """
            UPDATE payments
            SET status = 'SETTLED',
                settled_at = NOW(),
                updated_at = NOW()
            WHERE id = :paymentId
            """.trimIndent(),
            mapOf("paymentId" to paymentId),
        )

        createPayment(key, amount = "1.25")
            .andExpect(status().isCreated)
            .andExpect(header().string("X-Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.status").value("POSTED"))
    }

    @Test
    fun `protected API rejects a missing API key`() {
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Idempotency-Key", "unauthorized-${UUID.randomUUID()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "sourceAccountId": "$SOURCE_ACCOUNT_ID",
                          "destinationAccountId": "$DESTINATION_ACCOUNT_ID",
                          "amount": "1.00",
                          "currency": "EUR"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
    }

    @Test
    fun `concurrent duplicate requests converge on one payment`() {
        val key = "concurrent-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures =
                executor.invokeAll(
                    (1..12).map {
                        Callable {
                            createPayment(key, amount = "0.50")
                                .andExpect(status().isCreated)
                                .andReturn()
                        }
                    },
                )
            val ids =
                futures.map { future ->
                    UUID.fromString(json(future.get(20, TimeUnit.SECONDS))["id"].asText())
                }

            assertEquals(1, ids.toSet().size)
            assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE id = :id", ids.first()))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent independent debits cannot overspend an account`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(
                    "overspend-a-${UUID.randomUUID()}",
                    "overspend-b-${UUID.randomUUID()}",
                ).map { key ->
                    executor.submit<MvcResult> {
                        createPayment(key, amount = "6000.00").andReturn()
                    }
                }
            val responses = futures.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(setOf(201, 422), responses.map { it.response.status }.toSet())
            assertTrue(balance(SOURCE_ACCOUNT_ID) >= 0)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `immutable ledger rejects in-place history edits`() {
        val result =
            createPayment("immutable-${UUID.randomUUID()}", amount = "0.75")
                .andExpect(status().isCreated)
                .andReturn()
        val paymentId = UUID.fromString(json(result)["id"].asText())

        val exception =
            org.junit.jupiter.api.assertThrows<DataIntegrityViolationException> {
                jdbc.update(
                    """
                    UPDATE ledger_entries
                    SET reference = 'tampered'
                    WHERE payment_id = :paymentId
                    """.trimIndent(),
                    mapOf("paymentId" to paymentId),
                )
            }
        assertTrue(exception.message.orEmpty().contains("ledger history is immutable"))
    }

    @Test
    fun `ledger entry cannot commit without balanced postings`() {
        org.junit.jupiter.api.assertThrows<DataIntegrityViolationException> {
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
                    :id,
                    NULL,
                    'ADJUSTMENT',
                    'EUR',
                    'invalid-empty-entry',
                    NOW(),
                    NOW()
                )
                """.trimIndent(),
                mapOf("id" to UUID.randomUUID()),
            )
        }
    }

    @Test
    fun `dead-letter redelivery creates one incident and flags the payment`() {
        val result =
            createPayment("dead-letter-${UUID.randomUUID()}", amount = "0.25")
                .andExpect(status().isCreated)
                .andReturn()
        val paymentId = UUID.fromString(json(result)["id"].asText())
        val eventId = UUID.randomUUID()
        val payload =
            """
            {
              "eventId": "$eventId",
              "payload": {
                "paymentId": "$paymentId",
                "settledAt": "2026-07-23T09:00:00Z"
              }
            }
            """.trimIndent()
        val record =
            ConsumerRecord(
                "settlements.v1.DLT",
                2,
                42L,
                paymentId.toString(),
                payload,
            )

        deadLetterService.record(record)
        deadLetterService.record(record)

        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM dead_letter_incidents WHERE payment_id = :id",
                paymentId,
            ),
        )
        assertEquals(
            "NEEDS_ATTENTION",
            jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = :id",
                mapOf("id" to paymentId),
                String::class.java,
            ),
        )
    }

    @Test
    fun `dead letter for an unknown payment remains auditable without blocking its partition`() {
        val unknownPaymentId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val record =
            ConsumerRecord(
                "settlements.v1.DLT",
                3,
                99L,
                unknownPaymentId.toString(),
                """
                {
                  "eventId": "$eventId",
                  "payload": {
                    "paymentId": "$unknownPaymentId",
                    "settledAt": "2026-07-23T09:00:00Z"
                  }
                }
                """.trimIndent(),
            )

        deadLetterService.record(record)

        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM dead_letter_incidents
                WHERE event_id = :eventId
                  AND payment_id IS NULL
                """.trimIndent(),
                mapOf("eventId" to eventId),
                Int::class.java,
            ),
        )
    }

    @Test
    fun `authorized dead-letter replay applies settlement and closes the incident atomically`() {
        val result =
            createPayment("dead-letter-replay-${UUID.randomUUID()}", amount = "0.40")
                .andExpect(status().isCreated)
                .andReturn()
        val paymentId = UUID.fromString(json(result)["id"].asText())
        val eventId = UUID.randomUUID()
        val settledAt = Instant.now()
        val payload =
            """
            {
              "eventId": "$eventId",
              "eventType": "settlement.confirmed.v1",
              "eventVersion": 1,
              "aggregateId": "$paymentId",
              "occurredAt": "$settledAt",
              "payload": {
                "paymentId": "$paymentId",
                "providerReference": "provider-replay-001",
                "settledAt": "$settledAt"
              }
            }
            """.trimIndent()
        deadLetterService.record(
            ConsumerRecord(
                "settlements.v1.DLT",
                1,
                501L,
                paymentId.toString(),
                payload,
            ),
        )
        val incidentId =
            jdbc.queryForObject(
                """
                SELECT id
                FROM dead_letter_incidents
                WHERE event_id = :eventId
                """.trimIndent(),
                mapOf("eventId" to eventId),
                UUID::class.java,
            ) ?: error("Missing dead-letter incident")

        mockMvc
            .perform(
                post("/api/v1/dead-letters/$incidentId/replay")
                    .header("X-API-Key", "test-integration-api-key"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REPLAYED"))
            .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
            .andExpect(jsonPath("$.applied").value(true))

        assertEquals(
            "SETTLED",
            jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = :id",
                mapOf("id" to paymentId),
                String::class.java,
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM settlement_events WHERE payment_id = :id", paymentId))
        assertEquals(2, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :id", paymentId))

        mockMvc
            .perform(
                post("/api/v1/dead-letters/$incidentId/replay")
                    .header("X-API-Key", "test-integration-api-key"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DEAD_LETTER_INCIDENT_CLOSED"))
    }

    @Test
    fun `settlement event IDs cannot be reused for another payment`() {
        val firstResult =
            createPayment("event-id-first-${UUID.randomUUID()}", amount = "0.30")
                .andExpect(status().isCreated)
                .andReturn()
        val firstPaymentId = UUID.fromString(json(firstResult)["id"].asText())
        val eventId = UUID.randomUUID()
        val settledAt = Instant.now()
        settlementService.process(
            SettlementMessage(
                eventId = eventId,
                eventType = "settlement.confirmed.v1",
                eventVersion = 1,
                aggregateId = firstPaymentId,
                occurredAt = settledAt,
                paymentId = firstPaymentId,
                providerReference = null,
                settledAt = settledAt,
            ),
        )

        val secondResult =
            createPayment("event-id-second-${UUID.randomUUID()}", amount = "0.30")
                .andExpect(status().isCreated)
                .andReturn()
        val secondPaymentId = UUID.fromString(json(secondResult)["id"].asText())
        val reusedEvent =
            SettlementMessage(
                eventId = eventId,
                eventType = "settlement.confirmed.v1",
                eventVersion = 1,
                aggregateId = secondPaymentId,
                occurredAt = settledAt,
                paymentId = secondPaymentId,
                providerReference = null,
                settledAt = settledAt,
            )

        val exception =
            org.junit.jupiter.api.assertThrows<InvalidEventException> {
                settlementService.process(reusedEvent)
            }
        assertTrue(exception.message.orEmpty().contains("reused with different content"))
        assertEquals(
            "POSTED",
            jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = :id",
                mapOf("id" to secondPaymentId),
                String::class.java,
            ),
        )
    }

    @Test
    fun `explicit null settlement provider reference is normalized to absent`() {
        val paymentId = UUID.randomUUID()
        val timestamp = Instant.now()
        val parsed =
            settlementEventParser.parse(
                """
                {
                  "eventId": "${UUID.randomUUID()}",
                  "eventType": "settlement.confirmed.v1",
                  "eventVersion": 1,
                  "aggregateId": "$paymentId",
                  "occurredAt": "$timestamp",
                  "payload": {
                    "paymentId": "$paymentId",
                    "providerReference": null,
                    "settledAt": "$timestamp"
                  }
                }
                """.trimIndent(),
            )

        assertEquals(null, parsed.providerReference)
    }

    @Test
    fun `reconciliation detects balance projection drift`() {
        jdbc.update(
            """
            UPDATE account_balances
            SET balance_minor = balance_minor + 7
            WHERE account_id = :accountId
            """.trimIndent(),
            mapOf("accountId" to DESTINATION_ACCOUNT_ID),
        )
        try {
            val run = reconciliationService.run(ReconciliationTrigger.MANUAL)
            assertNotEquals(0, run.discrepancyCount)
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM reconciliation_discrepancies
                    WHERE run_id = :runId
                      AND discrepancy_type = 'BALANCE_PROJECTION_DRIFT'
                    """.trimIndent(),
                    mapOf("runId" to run.id),
                    Int::class.java,
                ),
            )
        } finally {
            jdbc.update(
                """
                UPDATE account_balances
                SET balance_minor = balance_minor - 7
                WHERE account_id = :accountId
                """.trimIndent(),
                mapOf("accountId" to DESTINATION_ACCOUNT_ID),
            )
        }
    }

    private fun createPayment(
        key: String,
        amount: String,
    ) = mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", key)
                .header("X-API-Key", "test-integration-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceAccountId": "$SOURCE_ACCOUNT_ID",
                      "destinationAccountId": "$DESTINATION_ACCOUNT_ID",
                      "amount": "$amount",
                      "currency": "EUR",
                      "reference": "integration-test"
                    }
                    """.trimIndent(),
                ),
        )

    private fun json(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    private fun balance(accountId: UUID): Long =
        jdbc.queryForObject(
            "SELECT balance_minor FROM account_balances WHERE account_id = :accountId",
            mapOf("accountId" to accountId),
            Long::class.java,
        ) ?: error("Missing account balance")

    private fun count(
        sql: String,
        id: UUID,
    ): Int =
        jdbc.queryForObject(
            sql,
            mapOf("id" to id),
            Int::class.java,
        ) ?: 0

    companion object {
        private val SOURCE_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val DESTINATION_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002")

        @Container
        @JvmStatic
        val postgres = EventLedgerPostgreSQLContainer()

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}

class EventLedgerPostgreSQLContainer : PostgreSQLContainer("postgres:17-alpine")

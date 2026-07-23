package dev.eventledger.reconciliation

import java.time.Instant
import java.util.UUID

enum class ReconciliationStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

enum class ReconciliationTrigger {
    MANUAL,
    SCHEDULED,
}

data class ReconciliationRun(
    val id: UUID,
    val status: ReconciliationStatus,
    val triggerType: ReconciliationTrigger,
    val startedAt: Instant,
    val completedAt: Instant?,
    val discrepancyCount: Int,
    val errorMessage: String?,
)

data class DiscrepancyCandidate(
    val type: String,
    val aggregateId: UUID?,
    val expectedJson: String,
    val actualJson: String,
)

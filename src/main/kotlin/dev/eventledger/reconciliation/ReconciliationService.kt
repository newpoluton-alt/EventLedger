package dev.eventledger.reconciliation

import dev.eventledger.config.EventLedgerProperties
import dev.eventledger.shared.NotFoundException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class ReconciliationService(
    private val repository: ReconciliationRepository,
    private val properties: EventLedgerProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val mismatchCounter: Counter =
        meterRegistry.counter("eventledger.reconciliation.mismatches")

    @Transactional
    fun run(trigger: ReconciliationTrigger): ReconciliationRun {
        val runId = UUID.randomUUID()
        val startedAt = clock.instant()
        if (!repository.tryAcquireLock()) {
            repository.insertRun(runId, trigger, ReconciliationStatus.SKIPPED, startedAt)
            return repository.findById(runId)
                ?: error("Skipped reconciliation run was not persisted")
        }

        repository.insertRun(runId, trigger, ReconciliationStatus.RUNNING, startedAt)
        val discrepancies = repository.detect(startedAt, properties.reconciliation)
        val completedAt = clock.instant()
        repository.insertDiscrepancies(runId, discrepancies, completedAt)
        repository.completeRun(runId, completedAt, discrepancies.size)
        mismatchCounter.increment(discrepancies.size.toDouble())

        return repository.findById(runId)
            ?: error("Completed reconciliation run was not persisted")
    }

    fun get(runId: UUID): ReconciliationRun =
        repository.findById(runId)
            ?: throw NotFoundException(
                "RECONCILIATION_RUN_NOT_FOUND",
                "Reconciliation run '$runId' does not exist.",
            )

    fun latest(): ReconciliationRun =
        repository.findLatest()
            ?: throw NotFoundException(
                "RECONCILIATION_RUN_NOT_FOUND",
                "No reconciliation run has been recorded yet.",
            )
}

@Component
@ConditionalOnProperty(
    prefix = "event-ledger.reconciliation",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ReconciliationScheduler(
    private val service: ReconciliationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${event-ledger.reconciliation.fixed-delay-ms:30000}")
    fun reconcile() {
        runCatching { service.run(ReconciliationTrigger.SCHEDULED) }
            .onFailure { exception ->
                logger.error("Scheduled reconciliation failed", exception)
            }
    }
}

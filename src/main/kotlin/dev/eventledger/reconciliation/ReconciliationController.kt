package dev.eventledger.reconciliation

import dev.eventledger.shared.ConflictException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reconciliation/runs")
class ReconciliationController(
    private val service: ReconciliationService,
) {
    @PostMapping
    fun run(): ResponseEntity<ReconciliationRun> {
        val reconciliation = service.run(ReconciliationTrigger.MANUAL)
        if (reconciliation.status == ReconciliationStatus.SKIPPED) {
            throw ConflictException(
                "RECONCILIATION_ALREADY_RUNNING",
                "Another EventLedger instance is already running reconciliation.",
            )
        }
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/reconciliation/runs/${reconciliation.id}"))
            .body(reconciliation)
    }

    @GetMapping("/latest")
    fun latest(): ReconciliationRun = service.latest()

    @GetMapping("/{runId}")
    fun get(
        @PathVariable runId: UUID,
    ): ReconciliationRun = service.get(runId)
}

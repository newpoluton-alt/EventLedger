package dev.eventledger.messaging

import dev.eventledger.shared.ConflictException
import dev.eventledger.shared.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DeadLetterReplayResponse(
    val incidentId: UUID,
    val paymentId: UUID,
    val status: String,
    val applied: Boolean,
    val replayedAt: Instant,
)

@RestController
@RequestMapping("/api/v1/dead-letters")
class DeadLetterRecoveryController(
    private val recoveryService: DeadLetterRecoveryService,
) {
    @PostMapping("/{incidentId}/replay")
    fun replay(
        @PathVariable incidentId: UUID,
    ): ResponseEntity<DeadLetterReplayResponse> = ResponseEntity.ok(recoveryService.replay(incidentId))
}

@Service
class DeadLetterRecoveryService(
    private val settlementRepository: SettlementRepository,
    private val settlementEventParser: SettlementEventParser,
    private val settlementService: SettlementService,
    private val clock: Clock,
) {
    @Transactional
    fun replay(incidentId: UUID): DeadLetterReplayResponse {
        val incident =
            settlementRepository.lockDeadLetterIncident(incidentId)
                ?: throw NotFoundException(
                    "DEAD_LETTER_INCIDENT_NOT_FOUND",
                    "Dead-letter incident '$incidentId' does not exist.",
                )
        if (incident.status != "OPEN") {
            throw ConflictException(
                "DEAD_LETTER_INCIDENT_CLOSED",
                "Dead-letter incident '$incidentId' is already ${incident.status}.",
            )
        }

        val event = settlementEventParser.parse(incident.payload)
        if (incident.paymentId != null && incident.paymentId != event.paymentId) {
            throw InvalidEventException(
                "Incident payment '${incident.paymentId}' does not match payload payment '${event.paymentId}'.",
            )
        }

        val applied = settlementService.recover(event)
        val replayedAt = clock.instant()
        check(settlementRepository.markDeadLetterReplayed(incidentId, replayedAt)) {
            "Dead-letter incident '$incidentId' lost its replay lock."
        }

        return DeadLetterReplayResponse(
            incidentId = incidentId,
            paymentId = event.paymentId,
            status = "REPLAYED",
            applied = applied,
            replayedAt = replayedAt,
        )
    }
}

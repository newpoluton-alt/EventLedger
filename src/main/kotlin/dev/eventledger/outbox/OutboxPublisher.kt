package dev.eventledger.outbox

import dev.eventledger.config.EventLedgerProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

@Component
@ConditionalOnProperty(
    prefix = "event-ledger.outbox",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxPublisher(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: EventLedgerProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val owner = "${hostname()}-${UUID.randomUUID()}"
    private val pendingGauge = AtomicLong()
    private val publishedCounter: Counter =
        meterRegistry.counter("eventledger.outbox.events", "result", "published")
    private val failedCounter: Counter =
        meterRegistry.counter("eventledger.outbox.events", "result", "failed")
    private val deadCounter: Counter =
        meterRegistry.counter("eventledger.outbox.events", "result", "dead")

    init {
        Gauge
            .builder("eventledger.outbox.pending", pendingGauge) { it.get().toDouble() }
            .description("Outbox events waiting for successful publication")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${event-ledger.outbox.fixed-delay-ms:500}")
    fun publishAvailable() {
        val outbox = properties.outbox
        var remaining = outbox.batchSize
        while (remaining > 0) {
            val now = clock.instant()
            val event =
                outboxRepository
                    .claimBatch(
                        owner = owner,
                        limit = 1,
                        lockedUntil = now.plus(outbox.lockDuration),
                    ).singleOrNull()
                    ?: break
            publish(event)
            remaining -= 1
        }
        pendingGauge.set(runCatching { outboxRepository.pendingCount() }.getOrDefault(-1))
    }

    private fun publish(event: OutboxEvent) {
        val renewed =
            outboxRepository.renewLease(
                eventId = event.id,
                owner = owner,
                lockedUntil = clock.instant().plus(properties.outbox.lockDuration),
            )
        if (!renewed) {
            logger.info(
                "Skipping outbox event because its lease moved to another publisher id={} aggregateId={}",
                event.id,
                event.aggregateId,
            )
            return
        }

        try {
            kafkaTemplate
                .send(event.topic, event.eventKey, event.payload)
                .get(properties.outbox.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
            val marked = outboxRepository.markPublished(event.id, owner, clock.instant())
            if (marked) {
                publishedCounter.increment()
                logger.info(
                    "Published outbox event id={} type={} aggregateId={} attempt={}",
                    event.id,
                    event.eventType,
                    event.aggregateId,
                    event.attemptCount,
                )
            } else {
                logger.warn(
                    "Kafka accepted event but its outbox lease was lost id={} aggregateId={}",
                    event.id,
                    event.aggregateId,
                )
            }
        } catch (exception: Exception) {
            val dead = event.attemptCount >= properties.outbox.maxAttempts
            val retryAt = clock.instant().plus(backoffFor(event.attemptCount))
            val message = rootMessage(exception)
            val marked =
                outboxRepository.markFailed(
                    event.id,
                    owner,
                    retryAt,
                    message,
                    dead,
                )
            if (!marked) {
                logger.warn(
                    "Outbox publish failed after its lease moved id={} aggregateId={} error={}",
                    event.id,
                    event.aggregateId,
                    message,
                )
            } else if (dead) {
                deadCounter.increment()
                logger.error(
                    "Outbox event exhausted retries id={} type={} aggregateId={} attempts={} error={}",
                    event.id,
                    event.eventType,
                    event.aggregateId,
                    event.attemptCount,
                    message,
                )
            } else {
                failedCounter.increment()
                logger.warn(
                    "Outbox publish failed; event will retry id={} attempt={} retryAt={} error={}",
                    event.id,
                    event.attemptCount,
                    retryAt,
                    message,
                )
            }
        }
    }

    private fun backoffFor(attempt: Int): Duration {
        val boundedExponent = min((attempt - 1).coerceAtLeast(0), 20)
        val multiplier = 1L shl boundedExponent
        val proposed = properties.outbox.initialBackoff.toMillis() * multiplier
        return Duration.ofMillis(min(proposed, properties.outbox.maxBackoff.toMillis()))
    }

    private fun rootMessage(exception: Throwable): String {
        var current = exception
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current.message ?: current.javaClass.simpleName
    }

    private fun hostname(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .getOrDefault("eventledger")
}

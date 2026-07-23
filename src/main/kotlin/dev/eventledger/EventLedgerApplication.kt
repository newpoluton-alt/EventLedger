package dev.eventledger

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableScheduling

@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
class EventLedgerApplication

fun main(args: Array<String>) {
    runApplication<EventLedgerApplication>(*args)
}

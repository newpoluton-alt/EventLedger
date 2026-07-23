package dev.eventledger.config

import com.fasterxml.jackson.databind.ObjectMapper
import dev.eventledger.account.AccountResponse
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import java.time.Clock
import java.time.Duration

@Configuration
class ApplicationConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun redisCacheConfiguration(objectMapper: ObjectMapper): RedisCacheConfiguration =
        RedisCacheConfiguration
            .defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(30))
            .disableCachingNullValues()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    Jackson2JsonRedisSerializer(objectMapper, AccountResponse::class.java),
                ),
            )
}

@Configuration
class ResilientCacheConfig : CachingConfigurer {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun errorHandler(): CacheErrorHandler =
        object : CacheErrorHandler {
            override fun handleCacheGetError(
                exception: RuntimeException,
                cache: Cache,
                key: Any,
            ) {
                logger.warn("Redis cache read failed; falling back to PostgreSQL cache={} key={}", cache.name, key)
            }

            override fun handleCachePutError(
                exception: RuntimeException,
                cache: Cache,
                key: Any,
                value: Any?,
            ) {
                logger.warn("Redis cache write failed; continuing cache={} key={}", cache.name, key)
            }

            override fun handleCacheEvictError(
                exception: RuntimeException,
                cache: Cache,
                key: Any,
            ) {
                logger.warn("Redis cache eviction failed; continuing cache={} key={}", cache.name, key)
            }

            override fun handleCacheClearError(
                exception: RuntimeException,
                cache: Cache,
            ) {
                logger.warn("Redis cache clear failed; continuing cache={}", cache.name)
            }
        }
}

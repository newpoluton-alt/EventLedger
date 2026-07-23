package dev.eventledger.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Configuration
class SecurityConfiguration(
    private val properties: EventLedgerProperties,
) {
    @Bean
    fun apiKeyAuthenticationFilter(): ApiKeyAuthenticationFilter = ApiKeyAuthenticationFilter(properties.security.apiKey)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        apiKeyAuthenticationFilter: ApiKeyAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorization ->
                authorization
                    .requestMatchers(
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/error",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.addFilterBefore(
                apiKeyAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}

class ApiKeyAuthenticationFilter(
    configuredApiKey: String,
) : OncePerRequestFilter() {
    private val expectedApiKey =
        configuredApiKey
            .takeIf { it.length >= MINIMUM_API_KEY_LENGTH }
            ?.toByteArray(StandardCharsets.UTF_8)
            ?: throw IllegalStateException(
                "EVENTLEDGER_API_KEY must be configured with at least $MINIMUM_API_KEY_LENGTH characters.",
            )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == "/error" ||
            request.requestURI == "/actuator/info" ||
            request.requestURI == "/actuator/prometheus" ||
            request.requestURI == "/actuator/health" ||
            request.requestURI.startsWith("/actuator/health/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val suppliedApiKey =
            request
                .getHeader(API_KEY_HEADER)
                ?.toByteArray(StandardCharsets.UTF_8)
        if (suppliedApiKey == null || !MessageDigest.isEqual(expectedApiKey, suppliedApiKey)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.writer.write(
                """
                {
                  "type": "https://eventledger.dev/problems/invalid-api-key",
                  "title": "Authentication required",
                  "status": 401,
                  "detail": "Provide a valid X-API-Key header.",
                  "code": "INVALID_API_KEY"
                }
                """.trimIndent(),
            )
            return
        }

        val authentication =
            UsernamePasswordAuthenticationToken(
                "api-key-client",
                null,
                AuthorityUtils.NO_AUTHORITIES,
            )
        SecurityContextHolder.getContext().authentication = authentication
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val API_KEY_HEADER = "X-API-Key"
        private const val MINIMUM_API_KEY_LENGTH = 16
    }
}

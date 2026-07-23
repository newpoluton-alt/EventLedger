package dev.eventledger.shared

import dev.eventledger.messaging.InvalidEventException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

open class ApiException(
    val status: HttpStatus,
    val errorCode: String,
    title: String,
    detail: String,
) : RuntimeException(detail) {
    val problemTitle: String = title
}

class NotFoundException(
    code: String,
    detail: String,
) : ApiException(HttpStatus.NOT_FOUND, code, "Resource not found", detail)

class ConflictException(
    code: String,
    detail: String,
) : ApiException(HttpStatus.CONFLICT, code, "Conflict", detail)

class UnprocessableException(
    code: String,
    detail: String,
) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Request cannot be processed", detail)

class InvalidRequestException(
    code: String,
    detail: String,
) : ApiException(HttpStatus.BAD_REQUEST, code, "Invalid request", detail)

class DependencyUnavailableException(
    detail: String,
) : ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        "Dependency unavailable",
        detail,
    )

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(
        exception: ApiException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(exception.status, exception.message ?: exception.problemTitle).apply {
            title = exception.problemTitle
            type = URI.create("https://eventledger.dev/problems/${exception.errorCode.lowercase().replace('_', '-')}")
            instance = URI.create(request.requestURI)
            setProperty("code", exception.errorCode)
        }

    @ExceptionHandler(InvalidEventException::class)
    fun handleInvalidSettlementEvent(
        exception: InvalidEventException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.message ?: "Settlement event is invalid.",
            ).apply {
                title = "Settlement event cannot be processed"
                type = URI.create("https://eventledger.dev/problems/invalid-settlement-event")
                instance = URI.create(request.requestURI)
                setProperty("code", "INVALID_SETTLEMENT_EVENT")
            }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more request fields are invalid.").apply {
            title = "Validation failed"
            type = URI.create("https://eventledger.dev/problems/validation-error")
            instance = URI.create(request.requestURI)
            setProperty("code", "VALIDATION_ERROR")
            setProperty(
                "violations",
                exception.bindingResult.allErrors.map { error ->
                    mapOf(
                        "field" to (error as? FieldError)?.field,
                        "message" to (error.defaultMessage ?: "invalid value"),
                    )
                },
            )
        }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(
        exception: MissingRequestHeaderException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required header '${exception.headerName}' is missing.",
            ).apply {
                title = "Missing request header"
                type = URI.create("https://eventledger.dev/problems/missing-header")
                instance = URI.create(request.requestURI)
                setProperty("code", "MISSING_REQUEST_HEADER")
            }

    @ExceptionHandler(
        DataAccessResourceFailureException::class,
        CannotGetJdbcConnectionException::class,
        CannotCreateTransactionException::class,
    )
    fun handleDatabaseUnavailable(
        exception: RuntimeException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The ledger database is temporarily unavailable. Retrying with the same idempotency key is safe.",
            ).apply {
                title = "Dependency unavailable"
                type = URI.create("https://eventledger.dev/problems/dependency-unavailable")
                instance = URI.create(request.requestURI)
                setProperty("code", "DEPENDENCY_UNAVAILABLE")
                setProperty("retryable", true)
            }
}

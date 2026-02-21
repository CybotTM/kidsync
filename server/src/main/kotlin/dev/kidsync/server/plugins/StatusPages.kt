package dev.kidsync.server.plugins

import dev.kidsync.server.models.ErrorResponse
import dev.kidsync.server.services.ApiException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

fun Application.configureStatusPages() {
    val logger = LoggerFactory.getLogger("StatusPages")

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            val status = HttpStatusCode.fromValue(cause.statusCode)
            call.respond(
                status,
                ErrorResponse(
                    error = cause.errorCode,
                    message = cause.message,
                )
            )
        }

        exception<BadRequestException> { call, cause ->
            logger.warn("Bad request: {}", cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "INVALID_REQUEST",
                    message = cause.message ?: "Bad request",
                )
            )
        }

        exception<ContentTransformationException> { call, cause ->
            logger.warn("Content transformation error: {}", cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "INVALID_REQUEST",
                    message = cause.message ?: "Invalid request body",
                )
            )
        }

        exception<SerializationException> { call, cause ->
            logger.warn("Serialization error: {}", cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "INVALID_REQUEST",
                    message = cause.message ?: "Invalid request body",
                )
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            logger.warn("Bad request (IllegalArgument): {}", cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "INVALID_REQUEST",
                    message = cause.message ?: "Bad request",
                )
            )
        }

        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "INTERNAL_ERROR",
                    message = "An unexpected error occurred",
                )
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(
                    error = "NOT_FOUND",
                    message = "Resource not found",
                )
            )
        }
    }
}

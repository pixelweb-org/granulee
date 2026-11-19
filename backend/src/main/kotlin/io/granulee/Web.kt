package io.granulee

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Controller
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Every API error is `{ "message": … }` with a fitting status. */
@RestControllerAdvice
class ApiErrors {
    private val log = LoggerFactory.getLogger(ApiErrors::class.java)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = error(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(BadRequestException::class, HttpMessageNotReadableException::class, MissingServletRequestParameterException::class)
    fun badRequest(e: Exception) =
        error(HttpStatus.BAD_REQUEST, if (e is BadRequestException) e.message else "The request could not be read")

    /** The ClickHouse server refused or could not be reached. */
    @ExceptionHandler(ChException::class)
    fun clickHouse(e: ChException) = error(HttpStatus.BAD_GATEWAY, e.message)

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled error", e)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.message ?: e.javaClass.simpleName)
    }

    private fun error(status: HttpStatus, message: String?) = ResponseEntity.status(status).body(ApiError(message ?: status.reasonPhrase))
}

/** When the jar carries the built frontend, client-side routes fall through to index.html. */
@Controller
class SpaController {
    @GetMapping("/explorer/**", "/tables/**", "/recommendations/**", "/trials/**", "/sources")
    fun spa(): String = "forward:/index.html"
}

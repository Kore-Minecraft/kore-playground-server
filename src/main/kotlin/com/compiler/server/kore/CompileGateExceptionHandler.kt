package com.compiler.server.kore

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Answers a full compile queue with a 429 and a `Retry-After`. */
@RestControllerAdvice
class CompileGateExceptionHandler {
    @ExceptionHandler(CompileBusyException::class)
    fun handleBusy(exception: CompileBusyException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
            .body(mapOf("message" to exception.message))
}

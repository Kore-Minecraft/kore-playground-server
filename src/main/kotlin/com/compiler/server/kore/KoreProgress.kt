package com.compiler.server.kore

import com.compiler.server.model.TranslationResultWithJsCode
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** One NDJSON line of `/translate/js/stream`, named by [event]. */
@JsonPropertyOrder("event")
sealed class CompileEvent(val event: String) {
    data class Busy(val message: String, val retryAfterSeconds: Long) : CompileEvent("busy")
    data class Failed(val message: String) : CompileEvent("error")
    data class Output(val chunks: Int, val reused: Int, val bytes: Int) : CompileEvent("output")

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Phase(val name: String, val previousMs: Long? = null) : CompileEvent("phase")

    data class Queued(val ahead: Int, val lane: String) : CompileEvent("queued")
    data class Result(val result: TranslationResultWithJsCode) : CompileEvent("result")
    data class Started(val lane: String) : CompileEvent("started")
}

/** Thread-local sink for compile progress: a compile runs on one thread, so no upstream signature carries a listener. */
object KoreProgress {
    private val listener = ThreadLocal<(CompileEvent) -> Unit>()

    fun <T> reportingTo(sink: (CompileEvent) -> Unit, block: () -> T): T {
        listener.set(sink)
        return try {
            block()
        } finally {
            listener.remove()
        }
    }

    fun emit(event: CompileEvent) {
        listener.get()?.invoke(event)
    }
}

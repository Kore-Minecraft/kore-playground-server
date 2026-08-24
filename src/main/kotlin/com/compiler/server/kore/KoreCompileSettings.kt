package com.compiler.server.kore

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * Tuning for the Kore playground backend, entirely optional.
 *
 * With no `kore.js.cache-directory` the JS pipeline is byte-for-byte upstream: one DCE'd `playground.js`
 * bundle. With one, the second phase switches to the Kotlin/JS IR build cache, which cuts a repeat compile
 * of Kore's klibs from ~41 s to ~6 s at the cost of per-module (undeadcoded) output.
 */
@Component
class KoreCompileSettings(
    @param:Value("\${kore.js.cache-directory:}") cacheDirectory: String,
    @param:Value("\${kore.js.max-queued-compiles:8}") val maxQueuedCompiles: Int,
    @param:Value("\${kore.js.queue-timeout-seconds:120}") val queueTimeoutSeconds: Long,
) {
    /** Kotlin/JS IR build cache directory, or `null` to keep the upstream single-bundle pipeline. */
    val cacheDirectory: Path? = cacheDirectory
        .ifBlank { null }
        ?.let { Path(it).toAbsolutePath().createDirectories() }

    /** The IR cache only reaches its fast path with `-Xir-per-module`, so the two are one switch. */
    val perModuleOutput get() = cacheDirectory != null

    /**
     * Second-phase arguments for the cached pipeline.
     *
     * `-Xir-dce` is silently ignored once `-Xcache-directory` is set, so it is dropped rather than left in
     * to suggest an optimisation that no longer happens.
     */
    fun applyToSecondPhase(arguments: List<String>): List<String> {
        val cache = cacheDirectory ?: return arguments

        return arguments.filterNot { it == "-Xir-dce" } + listOf(
            "-Xir-per-module",
            "-Xir-build-cache",
            "-Xcache-directory=$cache",
        )
    }
}

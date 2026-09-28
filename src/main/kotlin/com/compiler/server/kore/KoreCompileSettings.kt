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
    @param:Value("\${kore.js.anchor-directory:}") anchorSources: String,
    @param:Value("\${kore.js.max-queued-compiles:8}") val maxQueuedCompiles: Int,
    @param:Value("\${kore.js.queue-timeout-seconds:120}") val queueTimeoutSeconds: Long,
    @param:Value("\${kore.diagnostics.max-concurrent:2}") val maxConcurrentDiagnostics: Int,
) {
    /** Kotlin/JS IR build cache directory, or `null` to keep the upstream single-bundle pipeline. */
    val cacheDirectory: Path? = cacheDirectory
        .ifBlank { null }
        ?.let { Path(it).toAbsolutePath().createDirectories() }

    /** The IR cache only reaches its fast path with `-Xir-per-module`, so the two are one switch. */
    val perModuleOutput get() = cacheDirectory != null

    /**
     * Where every snippet is written, sources in `src/` and klib in `klib/`, the same folders each time.
     *
     * The IR cache keys a module by its klib path and a file by its source path, so per-request temp folders made
     * every compile a brand new module whose files all changed: the cache re-lowered each Kore file the snippet
     * touches and regenerated all of `Kore-kore.js`, even for a byte-identical repeat. At fixed paths a new snippet
     * is an edit of the same file. Safe only because [CompileGate] lets one compile through at a time, exactly like
     * the cache itself.
     */
    val snippetDirectory: Path? = this.cacheDirectory?.let { it.resolveSibling("${it.fileName}-snippet") }

    /** Kotlin snippets compiled into the anchor module, see `KotlinToJSTranslator.anchorKlib`. Only used with the cache. */
    val anchorSources: Path? = anchorSources.ifBlank { null }?.let { Path(it).toAbsolutePath() }?.takeIf { perModuleOutput }

    /** Where the anchor module is built, sources in `src/` and klib in `klib/`. */
    val anchorDirectory: Path? = this.cacheDirectory?.let { it.resolveSibling("${it.fileName}-anchor") }

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

package com.compiler.server.kore

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/** Kore playground settings; with no `kore.js.cache-directory` the JS pipeline is upstream's. */
@Component
class KoreCompileSettings(
    @param:Value("\${kore.js.cache-directory:}") cacheDirectory: String,
    @param:Value("\${kore.js.anchor-directory:}") anchorSources: String,
    @param:Value("\${kore.js.max-queued-compiles:8}") val maxQueuedCompiles: Int,
    @param:Value("\${kore.js.queue-timeout-seconds:120}") val queueTimeoutSeconds: Long,
    @param:Value("\${kore.diagnostics.max-concurrent:2}") val maxConcurrentDiagnostics: Int,
) {
    val cacheDirectory: Path? = cacheDirectory.ifBlank { null }?.let { Path(it).toAbsolutePath().createDirectories() }

    /** The IR cache only reaches its fast path with `-Xir-per-module`. */
    val perModuleOutput get() = cacheDirectory != null

    /** Fixed snippet paths: the IR cache keys modules and files by path, so per-request temp folders made every compile a new module. */
    val snippetDirectory: Path? = this.cacheDirectory?.let { it.resolveSibling("${it.fileName}-snippet") }

    val anchorSources: Path? = anchorSources.ifBlank { null }?.let { Path(it).toAbsolutePath() }?.takeIf { perModuleOutput }

    val anchorDirectory: Path? = this.cacheDirectory?.let { it.resolveSibling("${it.fileName}-anchor") }

    /** Drops `-Xir-dce`, which the compiler silently ignores once `-Xcache-directory` is set. */
    fun applyToSecondPhase(arguments: List<String>): List<String> {
        val cache = cacheDirectory ?: return arguments
        return arguments.filterNot { it == "-Xir-dce" } + listOf("-Xir-per-module", "-Xir-build-cache", "-Xcache-directory=$cache")
    }
}

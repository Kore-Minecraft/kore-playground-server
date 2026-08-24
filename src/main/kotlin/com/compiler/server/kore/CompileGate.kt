package com.compiler.server.kore

import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when the compile queue is full or a caller waited too long for a slot. Answered as a 429. */
class CompileBusyException(message: String, val retryAfterSeconds: Long) : RuntimeException(message)

/**
 * One compile at a time, with a bounded queue.
 *
 * A Kotlin/JS compile is single-threaded and saturates a core, and the IR build cache directory tolerates
 * exactly one writer - two concurrent compiles corrupt it. `KotlinEnvironment.synchronize` already
 * serializes the work, but it does so on a JVM monitor with an unbounded queue, so callers pile up
 * invisibly under load. This gate sits in front of it and turns the surplus into an immediate 429.
 */
@Component
class CompileGate(private val settings: KoreCompileSettings) {
    private val compiles = Lane(permits = 1, queueLimit = settings.maxQueuedCompiles, waitSeconds = settings.queueTimeoutSeconds, what = "compile")

    // A JVM diagnostics pass is ~1 s where a JS compile is 6-20 s, so several may run at once - but not
    // unboundedly, or people typing would starve the compile the page actually needs.
    private val diagnostics = Lane(
        permits = settings.maxConcurrentDiagnostics,
        queueLimit = settings.maxConcurrentDiagnostics * 4,
        waitSeconds = DIAGNOSTICS_WAIT_SECONDS,
        what = "diagnostics",
    )

    /** Number of callers currently waiting for or holding the compile slot, for `/kore/status`. */
    val depth get() = compiles.depth

    fun <T> singleFlight(block: () -> T): T = compiles.enter(block)

    fun <T> diagnostics(block: () -> T): T = diagnostics.enter(block)

    private class Lane(permits: Int, private val queueLimit: Int, private val waitSeconds: Long, private val what: String) {
        private val slot = Semaphore(permits, true)
        private val queued = AtomicInteger()

        val depth get() = queued.get()

        fun <T> enter(block: () -> T): T {
            if (queued.incrementAndGet() > queueLimit) {
                queued.decrementAndGet()
                throw CompileBusyException("The $what queue is full, retry shortly.", RETRY_AFTER_FULL_SECONDS)
            }

            val acquired = try {
                slot.tryAcquire(waitSeconds, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                queued.decrementAndGet()
                throw CompileBusyException("Interrupted while waiting for a $what slot.", RETRY_AFTER_FULL_SECONDS)
            }

            if (!acquired) {
                queued.decrementAndGet()
                throw CompileBusyException("Timed out waiting for a $what slot.", waitSeconds)
            }

            return try {
                block()
            } finally {
                slot.release()
                queued.decrementAndGet()
            }
        }
    }

    private companion object {
        const val RETRY_AFTER_FULL_SECONDS = 10L

        // A diagnostics caller is a keystroke debounce: it is worth dropping rather than queueing for long.
        const val DIAGNOSTICS_WAIT_SECONDS = 10L
    }
}

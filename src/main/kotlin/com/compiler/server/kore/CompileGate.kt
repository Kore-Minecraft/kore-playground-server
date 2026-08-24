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
    private val slot = Semaphore(1, true)
    private val queued = AtomicInteger()

    /** Number of callers currently waiting for or holding the compile slot, for `/kore/status`. */
    val depth get() = queued.get()

    fun <T> singleFlight(block: () -> T): T {
        if (queued.incrementAndGet() > settings.maxQueuedCompiles) {
            queued.decrementAndGet()
            throw CompileBusyException("Compile queue is full, retry shortly.", RETRY_AFTER_FULL_SECONDS)
        }

        val acquired = try {
            slot.tryAcquire(settings.queueTimeoutSeconds, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            queued.decrementAndGet()
            throw CompileBusyException("Interrupted while waiting for a compile slot.", RETRY_AFTER_FULL_SECONDS)
        }

        if (!acquired) {
            queued.decrementAndGet()
            throw CompileBusyException("Timed out waiting for a compile slot.", settings.queueTimeoutSeconds)
        }

        return try {
            block()
        } finally {
            slot.release()
            queued.decrementAndGet()
        }
    }

    private companion object {
        const val RETRY_AFTER_FULL_SECONDS = 10L
    }
}

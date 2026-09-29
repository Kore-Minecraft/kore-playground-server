package com.compiler.server.kore

import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A full queue or a caller that waited too long for a slot, answered as a 429 or a `busy` stream event. */
class CompileBusyException(override val message: String, val retryAfterSeconds: Long) : RuntimeException(message)

/** Bounded queues in front of the compiler: one JS compile at a time, since two corrupt the IR cache, and a few JVM type-checks. */
@Component
class CompileGate(settings: KoreCompileSettings) {
    private val compiles = Lane("compile", 1, settings.maxQueuedCompiles, settings.queueTimeoutSeconds)
    private val diagnostics = Lane(
        "diagnostics",
        settings.maxConcurrentDiagnostics,
        settings.maxConcurrentDiagnostics * 4,
        DIAGNOSTICS_WAIT_SECONDS,
    )

    fun <T> singleFlight(block: () -> T): T = compiles.enter(block)

    fun <T> diagnostics(block: () -> T): T = diagnostics.enter(block)

    private class Lane(private val name: String, permits: Int, private val queueLimit: Int, private val waitSeconds: Long) {
        private val slot = Semaphore(permits, true)
        private val queued = AtomicInteger()

        fun <T> enter(block: () -> T): T {
            val position = queued.incrementAndGet()
            if (position > queueLimit) {
                queued.decrementAndGet()
                throw CompileBusyException("The $name queue is full, retry shortly.", RETRY_AFTER_FULL_SECONDS)
            }

            if (position > 1) KoreProgress.emit(CompileEvent.Queued(position - 1, name))

            val acquired = try {
                slot.tryAcquire(waitSeconds, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

            if (!acquired) {
                queued.decrementAndGet()
                throw CompileBusyException("Timed out waiting for a $name slot.", waitSeconds)
            }

            KoreProgress.emit(CompileEvent.Started(name))

            return try {
                block()
            } finally {
                slot.release()
                queued.decrementAndGet()
            }
        }
    }

    private companion object {
        const val DIAGNOSTICS_WAIT_SECONDS = 10L
        const val RETRY_AFTER_FULL_SECONDS = 10L
    }
}

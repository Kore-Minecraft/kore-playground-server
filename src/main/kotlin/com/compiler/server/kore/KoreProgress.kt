package com.compiler.server.kore

/** One line of progress: an [event] name plus whatever the stage has to say about itself. */
data class CompileProgress(val event: String, val detail: Map<String, Any>)

/**
 * Where the compile pipeline reports what it is currently doing.
 *
 * A JS compile takes 6-35 s and says nothing until it is over, which reads as a hung page. The streaming
 * endpoint subscribes to this sink and forwards every stage as it happens, so the caller can name the wait
 * instead of spinning through it.
 *
 * A thread local rather than a parameter: a compile runs synchronously on the request thread all the way
 * down, and threading a listener through four upstream signatures would widen the patch surface against
 * `JetBrains/kotlin-compiler-server` for nothing. Nobody listening makes [emit] a null check.
 */
object KoreProgress {
    private val listener = ThreadLocal<(CompileProgress) -> Unit>()

    fun <T> reportingTo(sink: (CompileProgress) -> Unit, block: () -> T): T {
        listener.set(sink)

        return try {
            block()
        } finally {
            listener.remove()
        }
    }

    fun emit(event: String, vararg detail: Pair<String, Any>) {
        listener.get()?.invoke(CompileProgress(event, detail.toMap()))
    }
}

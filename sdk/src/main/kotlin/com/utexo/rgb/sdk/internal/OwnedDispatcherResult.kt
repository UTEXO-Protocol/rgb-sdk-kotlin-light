package com.utexo.rgb.sdk.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Transfers a closeable result across a dispatcher boundary exactly once.
 * `withContext` can discard a successfully computed result when the caller is
 * cancelled before dispatch-back. Until delivery succeeds this frame remains
 * responsible for destroying that result. Cleanup never masks the original
 * failure, including cancellation.
 */
internal suspend fun <T> withOwnedDispatcherResult(
    context: CoroutineContext,
    block: suspend (DispatcherResultOwner) -> T,
): T {
    val ownership = DispatcherResultOwner()
    return try {
        withContext(context) {
            ownership.retain(block(ownership))
        }
    } catch (failure: Throwable) {
        ownership.destroy()
        throw failure
    }
}

/** Register before resource/lock cleanup too: closing a file may itself throw. */
internal class DispatcherResultOwner {
    private var acquired: AutoCloseable? = null

    fun <T> retain(result: T): T {
        check(acquired == null || acquired === result) { "dispatcher result ownership changed before delivery" }
        acquired = result as? AutoCloseable
        return result
    }

    fun destroy(): Unit {
        val current = acquired
        acquired = null
        runCatching { current?.close() }
    }
}

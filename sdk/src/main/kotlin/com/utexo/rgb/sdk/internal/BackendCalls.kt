package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.SdkException
import kotlinx.coroutines.CancellationException

internal suspend inline fun <T> backendCall(crossinline block: suspend () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (sdk: SdkException) {
    throw sdk
} catch (_: Exception) {
    throw SdkException.BackendUnavailable()
}

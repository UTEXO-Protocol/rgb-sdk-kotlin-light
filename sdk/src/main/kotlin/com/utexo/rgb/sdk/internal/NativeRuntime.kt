package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.internal.ffi.uniffiEnsureInitialized

/** No key material or RustBuffer may cross FFI before version/checksum acceptance. */
internal object NativeRuntime {
    private val guard: NativeInitializationGuard = NativeInitializationGuard(::uniffiEnsureInitialized)

    internal fun ensureInitialized(): Unit = guard.ensureInitialized()
}

/** Sticky failure, once-only initialization, and no raw linker/checksum diagnostics. */
internal class NativeInitializationGuard(private val initialize: () -> Unit) {
    @Volatile private var state: State = State.NEW

    internal fun ensureInitialized(): Unit {
        if (state == State.READY) return
        synchronized(this) {
            when (state) {
                State.READY -> return
                State.FAILED -> unavailable()
                State.NEW -> {
                    // Failure remains sticky even if initialization terminates abnormally.
                    state = State.FAILED
                    try {
                        initialize()
                        state = State.READY
                    } catch (_: LinkageError) {
                        unavailable()
                    } catch (_: RuntimeException) {
                        unavailable()
                    }
                }
            }
        }
    }

    private fun unavailable(): Nothing = throw SdkException.Unsupported(
        "compatible native utexo-local-signer library",
    )

    private enum class State { NEW, READY, FAILED }
}

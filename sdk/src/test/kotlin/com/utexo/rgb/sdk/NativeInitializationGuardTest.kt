package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.NativeInitializationGuard
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class NativeInitializationGuardTest {
    @Test
    fun `concurrent key and wallet callers initialize once before entering native work`(): Unit {
        val calls = AtomicInteger()
        val guard = NativeInitializationGuard { calls.incrementAndGet() }
        val executor = Executors.newFixedThreadPool(8)
        try {
            executor.invokeAll(List(100) { Callable { guard.ensureInitialized(); assertEquals(1, calls.get()) } })
                .forEach { it.get() }
            assertEquals(1, calls.get())
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `contract checksum and linkage failures reject repeatedly without retrying initialization or leaking errors`(): Unit {
        for (failure in listOf(RuntimeException("secret path"), UnsatisfiedLinkError("secret path"),
            ExceptionInInitializerError(RuntimeException("checksum mismatch")))) {
            var calls = 0
            var nativeCalls = 0
            val guard = NativeInitializationGuard { calls++; throw failure }
            repeat(3) {
                val error = assertFailsWith<SdkException.Unsupported> {
                    guard.ensureInitialized()
                    nativeCalls++
                }
                assertEquals(null, error.cause)
                assertEquals("compatible native utexo-local-signer library", error.details["feature"])
            }
            assertEquals(1, calls)
            assertEquals(0, nativeCalls)
        }
    }
}

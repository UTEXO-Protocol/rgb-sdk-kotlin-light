package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.GatewaySigningContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class GatewaySigningContextTest {
    @Test
    fun `provider swallowing cancellation cannot authorize a cancelled operation`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val context = GatewaySigningContext.supplied(Network.SIGNET, WalletChainContextProvider {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                WalletChainContext(Network.SIGNET, 100, UnixSeconds.of(1_000))
            }
        }, EpochSecondsSource { 1_000 })
        var accepted = false
        val job = launch { context.validated(); accepted = true }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertFalse(accepted)
    }
    @Test
    fun `trusted application context is fresh network bound and independently refreshed`(): Unit = runBlocking {
        var calls = 0
        var value = WalletChainContext(Network.SIGNET, 123, UnixSeconds.of(900))
        val context = GatewaySigningContext.supplied(Network.SIGNET,
            WalletChainContextProvider { network -> assertEquals(Network.SIGNET, network); calls++; value },
            EpochSecondsSource { 1_000 })
        assertTrue(context.canSign)
        assertEquals(123, context.validated().maximumBlockHeight)
        value = value.copy(tipHeight = 124)
        assertEquals(124, context.validated().maximumBlockHeight)
        assertEquals(2, calls)
        value = value.copy(observedAt = UnixSeconds.of(699))
        assertFailsWith<SdkException.PolicyRejected> { context.validated() }
        value = value.copy(observedAt = UnixSeconds.of(1_031))
        assertFailsWith<SdkException.PolicyRejected> { context.validated() }
        value = value.copy(observedAt = UnixSeconds.of(1_030))
        context.validated()
        value = value.copy(network = Network.MAINNET)
        assertFailsWith<SdkException.WrongNetwork> { context.validated() }
        val absent = GatewaySigningContext.supplied(Network.SIGNET, null)
        assertFalse(absent.canSign)
        assertFailsWith<SdkException.Unsupported> { absent.validated() }
        val failed = GatewaySigningContext.supplied(Network.SIGNET,
            WalletChainContextProvider { throw IllegalStateException("secret-provider-token") })
        val error = assertFailsWith<SdkException.PolicyRejected> { failed.validated() }
        assertFalse(error.toString().contains("secret-provider-token"))
        assertEquals(null, error.cause)
    }
    @Test
    fun `height context is explicit bounded and never accepts timestamp locktime domain`(): Unit {
        assertEquals(0, GatewaySigningContext(0).maximumBlockHeight)
        assertEquals(499_999_999, GatewaySigningContext(499_999_999).maximumBlockHeight)
        for (invalid in listOf(-1, 500_000_000, Int.MAX_VALUE)) {
            assertFailsWith<SdkException.InvalidArgument> { GatewaySigningContext(invalid) }
        }
        assertEquals("utexo.psbt.rgb-lib.v1", GatewaySigningContext.PROFILE)
    }

    @Test
    fun `local validation time is read afresh and invalid clock never wraps to unsigned`(): Unit {
        var now = 100L
        val context = GatewaySigningContext(100, EpochSecondsSource { now })
        assertEquals(100, context.maximumBlockHeight)
        assertEquals(100L, context.validationTime())
        now = 101L
        assertEquals(101L, context.validationTime())
        now = -1L
        assertFailsWith<SdkException.InternalInvariantViolation> { context.validationTime() }
        now = 0L
        assertFailsWith<SdkException.InternalInvariantViolation> { context.validationTime() }
    }
}

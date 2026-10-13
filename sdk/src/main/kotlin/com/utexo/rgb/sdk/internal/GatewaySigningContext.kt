package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.WalletChainContextProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Explicitly selected, trusted validation context for the branch-0 RGB-lib
 * builder. The ordinary factory obtains it from the application's trusted
 * chain source; fixed contexts exist only for internal qualification fixtures.
 * Never obtain the height bound from the PSBT being authorized.
 */
internal class GatewaySigningContext private constructor(
    val maximumBlockHeight: Int,
    private val clock: EpochSecondsSource,
    private val network: Network?,
    private val provider: WalletChainContextProvider?,
) {
    constructor(maximumBlockHeight: Int,
        clock: EpochSecondsSource = EpochSecondsSource { System.currentTimeMillis() / 1_000L }) :
        this(maximumBlockHeight, clock, null, null)

    val canSign: Boolean get() = network == null || provider != null

    init {
        if (maximumBlockHeight !in 0..499_999_999) {
            throw SdkException.InvalidArgument("maximumBlockHeight", "must be a Bitcoin block height below 500000000")
        }
    }

    fun validationTime(): Long = clock.now().also {
        if (it !in 1L..9_007_199_254_740_991L) throw SdkException.InternalInvariantViolation("local signing clock is invalid")
    }

    suspend fun validated(): GatewaySigningContext {
        val expected = network ?: return this
        val source = provider ?: throw SdkException.Unsupported("trusted chain context provider required for gateway signing")
        val tip = try { source.loadChainContext(expected) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SdkException.PolicyRejected("trusted chain context is unavailable") }
        currentCoroutineContext().ensureActive()
        if (tip.network != expected) throw SdkException.WrongNetwork(expected, tip.network)
        val now = validationTime()
        if ((tip.observedAt.value > now && tip.observedAt.value - now > 30L) ||
            (tip.observedAt.value <= now && now - tip.observedAt.value > 300L)) {
            throw SdkException.PolicyRejected("trusted chain context is stale or dated in the future")
        }
        return GatewaySigningContext(tip.tipHeight, EpochSecondsSource { now })
    }

    companion object {
        const val PROFILE: String = "utexo.psbt.rgb-lib.v1"
        fun supplied(network: Network, provider: WalletChainContextProvider?,
            clock: EpochSecondsSource = EpochSecondsSource { System.currentTimeMillis() / 1_000L }): GatewaySigningContext =
            GatewaySigningContext(0, clock, network, provider)
    }
}

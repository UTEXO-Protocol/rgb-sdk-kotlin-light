package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.SdkException

/**
 * Explicitly selected, trusted validation context for the branch-0 RGB-lib
 * builder. It is intentionally absent from the app-facing factory while the
 * gateway's profile, recovery and deployment contract remain unqualified.
 * Never obtain the height bound from the PSBT being authorized.
 */
internal class GatewaySigningContext(
    val maximumBlockHeight: Int,
    private val clock: EpochSecondsSource = EpochSecondsSource { System.currentTimeMillis() / 1_000L },
) {
    init {
        if (maximumBlockHeight !in 0..499_999_999) {
            throw SdkException.InvalidArgument("maximumBlockHeight", "must be a Bitcoin block height below 500000000")
        }
    }

    fun validationTime(): Long = clock.now().also {
        if (it <= 0L) throw SdkException.InternalInvariantViolation("local signing clock is invalid")
    }

    companion object {
        const val PROFILE: String = "utexo.psbt.rgb-lib.v1"
    }
}

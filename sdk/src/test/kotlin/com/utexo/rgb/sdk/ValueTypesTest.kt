package com.utexo.rgb.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class ValueTypesTest {
    @Test
    fun `satoshis enforce the Bitcoin supply range`(): Unit {
        assertEquals(0, Satoshis.ZERO.value)
        assertEquals(Satoshis.MAX_VALUE, Satoshis.of(Satoshis.MAX_VALUE).value)
        assertFailsWith<SdkException.InvalidArgument> { Satoshis.of(-1) }
        assertFailsWith<SdkException.InvalidArgument> { Satoshis.of(Satoshis.MAX_VALUE + 1) }
    }

    @Test
    fun `fee rates are exact and reject unsafe doubles`(): Unit {
        assertEquals(1_500, FeeRate.satPerVbyte("1.5").milliSatsPerVbyte)
        assertEquals(1_500, FeeRate.satPerVbyte(1.5).milliSatsPerVbyte)
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte(Double.NaN) }
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte(Double.POSITIVE_INFINITY) }
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte(0.0) }
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte("1.0001") }
    }

    @Test
    fun `asset amounts preserve the unsigned 64 bit domain`(): Unit {
        assertEquals(AssetAmount.MAX_VALUE, AssetAmount.parse(AssetAmount.MAX_VALUE).decimal)
        assertEquals("1", AssetAmount.parse("0001").decimal)
        assertFailsWith<SdkException.InvalidArgument> { AssetAmount.parse("-1") }
        assertFailsWith<SdkException.InvalidArgument> { AssetAmount.parse("18446744073709551616") }
    }

    @Test
    fun `secret wrappers redact and clear their lifecycle`(): Unit {
        val seed = SecretSeed.fromBytes(ByteArray(64) { 7 })
        assertEquals("SecretSeed([REDACTED])", seed.toString())
        seed.close()
        assertFailsWith<SdkException.InvalidState> { seed.copyForNative() }

        val params = WalletUnlockParams.password(charArrayOf('s', 'e', 'c', 'r', 'e', 't'))
        assertEquals("secret", params.usePassword { String(requireNotNull(it)) })
        assertEquals("WalletUnlockParams(password=[REDACTED])", params.toString())
        params.close()
        assertFailsWith<SdkException.InvalidState> { params.usePassword { Unit } }
    }

    @Test
    fun `protocol identifiers use the frozen ASCII grammar`(): Unit {
        assertEquals("wallet_A-1.2:3", WalletId.of("wallet_A-1.2:3").value)
        assertFailsWith<SdkException.InvalidArgument> { WalletId.of("wallét") }
        assertFailsWith<SdkException.InvalidArgument> { IdempotencyKey.of("payment💸") }
    }

    @Test
    fun `local policy cannot widen native hard ceilings`(): Unit {
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(maximumPsbtBytes = 1_000_001)
        }
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(maximumInputs = 129)
        }
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(maximumOutputs = 257)
        }
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(maximumChildIndex = 1_000_001)
        }
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(maximumAbsoluteFee = Satoshis.of(1_000_001))
        }
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(rbf = RbfPolicy.ALLOWED)
        }
        assertFailsWith<SdkException.InvalidArgument> {
            LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte("100.001"))
        }
        assertEquals(10_000_000, FeeRate.satPerVbyte("10000").milliSatsPerVbyte)
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte("10000.001") }
    }
}

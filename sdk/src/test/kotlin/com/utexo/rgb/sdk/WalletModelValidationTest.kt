package com.utexo.rgb.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class WalletModelValidationTest {
    @Test
    fun `prepared create output plan owns an immutable snapshot of SPI lists`(): Unit {
        val output = ExpectedWalletOutput(AccountKind.COLORED, Keychain.EXTERNAL, 3, Satoshis.of(1_000))
        val callerOwned = mutableListOf(output)
        val prepared = PreparedTransaction(TEST_OPERATION_ID, TEST_IDEMPOTENCY_KEY,
            RequestHash.ofLowercaseHex("0".repeat(64)), Network.SIGNET, OperationKind.CREATE_UTXOS,
            "fixture-psbt", UnixSeconds.of(2_000), CreateUtxosOutputPlan(callerOwned))
        callerOwned[0] = output.copy(index = 99)
        assertEquals(listOf(output), prepared.createUtxosOutputPlan!!.outputs)
        @Suppress("UNCHECKED_CAST")
        val unsafeCast = prepared.createUtxosOutputPlan.outputs as MutableList<ExpectedWalletOutput>
        assertFailsWith<UnsupportedOperationException> { unsafeCast.clear() }
        assertEquals(listOf(output), prepared.createUtxosOutputPlan.outputs)
    }

    @Test
    fun `data class copies cannot bypass precision and canonical text validation`(): Unit {
        val asset = AssetNia(
            AssetId.of("asset"), "TEST", "Test", 0, AssetAmount.of(100),
            UnixSeconds.of(0), UnixSeconds.of(0), AssetBalance(),
        )
        assertFailsWith<SdkException.InvalidArgument> { asset.copy(precision = 19) }
        assertEquals(" Test ", asset.copy(name = " Test ").name)
        assertFailsWith<SdkException.InvalidArgument> { asset.copy(ticker = "x".repeat(17)) }
        assertFailsWith<SdkException.InvalidArgument> { asset.copy(details = "x".repeat(65536)) }
        assertFailsWith<SdkException.InvalidArgument> { Media(reference = " ref ") }
    }

    @Test
    fun `IFA reported supplies cannot exceed maximum supply`(): Unit {
        assertFailsWith<SdkException.InvalidArgument> {
            AssetIfa(
                AssetId.of("asset"), "TEST", "Test", 0,
                AssetAmount.of(101), AssetAmount.of(100), AssetAmount.of(101),
                UnixSeconds.of(0), UnixSeconds.of(0), AssetBalance(),
            )
        }
    }

    @Test
    fun `numeric text rejects unicode and bounds hostile allocation`(): Unit {
        assertFailsWith<SdkException.InvalidArgument> { AssetAmount.parse("١") }
        assertFailsWith<SdkException.InvalidArgument> { AssetAmount.parse("1".repeat(1000)) }
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte("1".repeat(1000)) }
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte("1e2147483647") }
        assertFailsWith<SdkException.InvalidArgument> { FeeRate.satPerVbyte("1e-2147483647") }
    }
}

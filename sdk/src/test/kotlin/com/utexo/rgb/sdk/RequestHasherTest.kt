package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.RequestHasher
import kotlin.test.Test
import kotlin.test.assertEquals

internal class RequestHasherTest {
    @Test
    fun `sendBtc canonical hash matches cross-platform golden vector`(): Unit {
        val request = SendBtcRequest(
            address = BitcoinAddress.of(
                "tb1ptestaddress000000000000000000000000000000000000000000000000000",
            ),
            amount = Satoshis.of(10_000),
            feeRate = FeeRate.satPerVbyte("1.5"),
            skipSync = false,
            idempotencyKey = IdempotencyKey.of("not-hashed-by-design"),
        )

        assertEquals(
            "67ed2e4db80d3acd337d567eaf6bdacbcce196d9c9fe280816210751be3c6ba7",
            RequestHasher.sendBtc(Network.SIGNET, request).hex,
        )
    }

    @Test
    fun `create canonical hash matches Swift and independent length-prefixed SHA256 vector`(): Unit {
        val request = CreateUtxosRequest(upTo = false, num = 5, size = Satoshis.of(5_000),
            feeRate = FeeRate.satPerVbyte("2"), skipSync = false)
        assertEquals("86f5dafa3faaacf0e084c64afde25b1fc907cd8933f96e1e9adcb28706424379",
            RequestHasher.createUtxos(Network.REGTEST, request).hex)
    }

    @Test
    fun `RGB canonical hash binds all selected options with cross-platform common-domain vector`(): Unit {
        val request = OnchainSendRequest(
            invoice = RgbInvoice.of("rgb:hash-fixture"), amount = AssetAmount.of(40),
            assetId = AssetId.of("rgb:asset"), witnessData = WitnessData(Satoshis.of(1_000), 17),
            donation = true, feeRate = FeeRate.satPerVbyte("2"),
            minConfirmations = BlockCount.of(2), skipSync = true,
        )
        assertEquals("d5ddecdf034f493b7e8d95b0167cd3ea34654c73bb298f36787e4d56bc9fabcc",
            RequestHasher.onchainSend(Network.REGTEST, request).hex)
    }
}

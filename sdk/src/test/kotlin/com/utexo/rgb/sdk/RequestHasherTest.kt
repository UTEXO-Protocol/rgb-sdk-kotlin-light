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
}

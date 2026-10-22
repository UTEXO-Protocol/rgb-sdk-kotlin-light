package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `listUnspents` against a live gateway.
 *
 * Every field of the SDK's `Unspent` model is backend state, so this asserts
 * the shape is populated rather than defaulted. `exists`, which says whether
 * the creating transaction was broadcast, is reported by the gateway or null —
 * never inferred from the other fields.
 *
 * Opt-in only: no-ops unless `gatewayBaseUrl` is supplied.
 */
@RunWith(AndroidJUnit4::class)
public class ListUnspentsE2eTest {
    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private fun required(name: String): String =
        arg(name) ?: error("missing instrumentation argument: $name")

    @Test
    public fun listsUnspentsWithAuthoritativeState(): Unit = runBlocking {
        if (arg("gatewayBaseUrl") == null) return@runBlocking
        val walletId = WalletId.of("unspents-e2e")
        val network = Network.valueOf(required("network"))
        val backend = RgbLightningNodeGatewayBackend(
            RgbLightningNodeGatewayConfiguration(
                baseUrl = required("gatewayBaseUrl"),
                expectedNetwork = network,
                bearerToken = required("bearerToken"),
                allowInsecureLocalDevelopment = arg("allowInsecure") == "true",
                callTimeoutMillis = 180_000L,
                readTimeoutMillis = 180_000L,
            ),
        )
        val session = backend.initialize(BackendInitializeRequest(walletId, network, 1, 1))
        val context = BackendContext(session, walletId, network)
        backend.syncWallet(context)

        val unspents = backend.listUnspents(context)
        assertTrue("expected at least one unspent; fund the wallet first", unspents.isNotEmpty())

        var totalSat = 0L
        for (u in unspents) {
            // Nothing here may be a default: a 64-hex txid and a real amount;
            // `exists` is whatever the wallet reported, possibly null.
            assertEquals(64, u.utxo.outpoint.txid.hex.length)
            assertTrue(u.utxo.outpoint.vout >= 0)
            assertTrue("amount must be positive", u.utxo.btcAmount.value > 0)
            assertTrue("pendingBlinded must not be negative", u.pendingBlinded >= 0)
            totalSat += u.utxo.btcAmount.value
            for (a in u.rgbAllocations) {
                // The variant name survives the round trip, including variants
                // this SDK predates.
                assertTrue(a.assignment.type.wireValue.isNotEmpty())
            }
            report(
                "UNSPENT",
                "outpoint=${u.utxo.outpoint.txid.hex.take(16)}…:${u.utxo.outpoint.vout}",
                "amountSat=${u.utxo.btcAmount.value}",
                "colorable=${u.utxo.colorable}",
                "exists=${u.utxo.exists}",
                "pendingBlinded=${u.pendingBlinded}",
                "allocations=${u.rgbAllocations.size}",
                u.rgbAllocations.joinToString(",") {
                    "${it.assignment.type.wireValue}:${it.assignment.amount?.decimal ?: "-"}" +
                        "/${it.assetId?.value?.take(14) ?: "none"}/settled=${it.settled}"
                },
            )
        }
        report("UNSPENTS OK", "count=${unspents.size}", "totalSat=$totalSat")
    }

    private fun report(vararg fields: String) {
        val line = fields.filter { it.isNotBlank() }.joinToString(" ")
        android.util.Log.i(TAG, line)
        println("$TAG $line")
    }

    private companion object {
        const val TAG: String = "UTEXO_E2E"
    }
}

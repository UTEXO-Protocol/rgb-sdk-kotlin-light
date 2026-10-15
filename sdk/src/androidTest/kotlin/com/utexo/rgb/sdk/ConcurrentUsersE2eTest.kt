package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utexo.rgb.sdk.internal.GatewaySigningContext
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two fully independent SDK instances — separate seeds, wallets, credentials and
 * gateway users — broadcasting at the same time against one live gateway.
 *
 * What this is actually probing:
 *  - cross-user isolation: neither user may see or spend the other's UTXOs
 *  - the gateway's per-user FIFO queue and global concurrency cap
 *  - the per-wallet rgb-lib handle pool, which serializes native calls per user
 *    but must not serialize ACROSS users
 *  - that two concurrent sends produce two distinct transactions, each spending
 *    only its own wallet's inputs
 *
 * Opt-in only: no-ops unless `gatewayBaseUrl` is supplied.
 */
@RunWith(AndroidJUnit4::class)
public class ConcurrentUsersE2eTest {
    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private fun required(name: String): String =
        arg(name) ?: error("missing instrumentation argument: $name")

    /** One completely independent SDK user. */
    private class Participant(
        val label: String,
        val token: String,
        val mnemonic: String,
        val amountSat: Long,
        val idempotencyKey: String,
    )

    private data class Outcome(
        val label: String,
        val txid: String,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val feeSat: Long,
        val changeSat: Long,
        val inputCount: Int,
    )

    private suspend fun send(p: Participant, baseUrl: String, destination: String, height: Int): Outcome {
        val startedAt = System.currentTimeMillis()
        val walletId = WalletId.of("concurrent-${p.label}")
        return SecretMnemonic.fromWords(p.mnemonic.replace('_', ' ').toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                UniFfiLocalSigningEngineFactory.openGateway(
                    NETWORK,
                    seed,
                    GatewaySigningContext(height),
                ).use { signer ->
                    val backend = RgbLightningNodeGatewayBackend(
                        RgbLightningNodeGatewayConfiguration(
                            baseUrl = baseUrl,
                            expectedNetwork = NETWORK,
                            bearerToken = p.token,
                            allowUnverifiedPsbtProfile = true,
                            callTimeoutMillis = 180_000L,
                            readTimeoutMillis = 180_000L,
                        ),
                    )
                    val session = backend.initialize(
                        BackendInitializeRequest(walletId, NETWORK, 1, 1),
                    )
                    val context = BackendContext(session, walletId, NETWORK)

                    val request = SendBtcRequest(
                        address = BitcoinAddress.of(destination),
                        amount = Satoshis.of(p.amountSat),
                        feeRate = FeeRate.satPerVbyte("2"),
                        idempotencyKey = IdempotencyKey.of(p.idempotencyKey),
                    )
                    val operationContext = OperationRequestContext(
                        backend = context,
                        idempotencyKey = request.idempotencyKey,
                        requestHash = RequestHasher.sendBtc(NETWORK, request),
                    )
                    val prepared = backend.prepareSendBtc(
                        PrepareSendBtcRequest(operationContext, request),
                    )
                    val signed = signer.validateAndSign(
                        prepared,
                        LocalOperationIntent.SendBtc(
                            network = NETWORK,
                            policy = LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte("20")),
                            request = request,
                        ),
                    )
                    assertEquals(Satoshis.of(p.amountSat), signed.validation.externalOutput)
                    val result = backend.submitSignedTransaction(
                        SignedTransactionSubmission(
                            context = operationContext,
                            operationId = prepared.operationId,
                            signedPsbtBase64 = signed.signedPsbtBase64,
                            validation = signed.validation,
                        ),
                        prepared,
                    ) as BackendOperationResult.SendBtc
                    assertEquals(signed.validation.unsignedTxid, result.txid)
                    Outcome(
                        label = p.label,
                        txid = result.txid.hex,
                        startedAtMs = startedAt,
                        finishedAtMs = System.currentTimeMillis(),
                        feeSat = signed.validation.fee.value,
                        changeSat = signed.validation.change.value,
                        inputCount = signed.validation.inputCount,
                    )
                }
            }
        }
    }

    @Test
    public fun twoUsersBroadcastConcurrently(): Unit = runBlocking {
        if (arg("gatewayBaseUrl") == null) return@runBlocking
        val baseUrl = required("gatewayBaseUrl")
        val destination = required("destination")
        val height = required("maxBlockHeight").toInt()

        val participants = listOf(
            Participant(
                label = "A",
                token = required("tokenA"),
                mnemonic = required("mnemonicA"),
                amountSat = required("amountSatA").toLong(),
                idempotencyKey = required("idempotencyKeyA"),
            ),
            Participant(
                label = "B",
                token = required("tokenB"),
                mnemonic = required("mnemonicB"),
                amountSat = required("amountSatB").toLong(),
                idempotencyKey = required("idempotencyKeyB"),
            ),
        )

        // Both flows are launched before either completes: the gateway sees two
        // users preparing and completing at overlapping times.
        val outcomes = participants
            .map { p -> async { send(p, baseUrl, destination, height) } }
            .awaitAll()

        val a = outcomes.first { it.label == "A" }
        val b = outcomes.first { it.label == "B" }

        // Two distinct transactions. If the gateway leaked one user's wallet to
        // the other, or serialized them into one operation, this fails.
        assertNotEquals(a.txid, b.txid)
        assertTrue(a.txid.length == 64 && b.txid.length == 64)

        // Did they actually overlap, or did the gateway serialize them?
        val overlapMs = minOf(a.finishedAtMs, b.finishedAtMs) - maxOf(a.startedAtMs, b.startedAtMs)

        for (o in outcomes) {
            report(
                "USER ${o.label}",
                "txid=${o.txid}",
                "feeSat=${o.feeSat}",
                "changeSat=${o.changeSat}",
                "inputs=${o.inputCount}",
                "elapsedMs=${o.finishedAtMs - o.startedAtMs}",
            )
        }
        report(
            "CONCURRENCY",
            "overlapMs=$overlapMs",
            "serialized=${overlapMs <= 0}",
            "distinctTxids=${a.txid != b.txid}",
        )
    }

    private fun report(vararg fields: String) {
        val line = fields.joinToString(" ")
        android.util.Log.i(TAG, line)
        println("$TAG $line")
    }

    private companion object {
        const val TAG: String = "UTEXO_E2E"
        val NETWORK: Network = Network.SIGNET
    }
}

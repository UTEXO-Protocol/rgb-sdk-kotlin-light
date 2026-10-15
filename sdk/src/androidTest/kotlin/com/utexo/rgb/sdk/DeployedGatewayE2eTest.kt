package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utexo.rgb.sdk.internal.GatewaySigningContext
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.LocalSigningEngine
import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end against a LIVE gateway, on a signet with throwaway keys.
 *
 * Opt-in only: every parameter arrives as an instrumentation argument and the
 * tests no-op when `gatewayBaseUrl` is absent, so an ordinary
 * `connectedAndroidTest` run is unaffected. Nothing here is a unit test — it
 * performs real HTTP and, in step 2, broadcasts a real transaction.
 *
 * Run as two steps because the wallet must be funded in between:
 *
 *   step1_registerAndReportAddress  -> prints the gateway-assigned address
 *   (fund that address out of band, wait for a confirmation)
 *   step2_prepareSignAndSend        -> signs locally and completes the send
 *
 * This deliberately drives `RgbLightningNodeGatewayBackend` directly rather
 * than `UTEXOWallet`: the app-facing factory hardcodes
 * `allowUnverifiedPsbtProfile = false` (WalletServiceConfiguration), so a BTC
 * send cannot be reached through it by design.
 */
@RunWith(AndroidJUnit4::class)
public class DeployedGatewayE2eTest {
    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private fun required(name: String): String =
        arg(name) ?: error("missing instrumentation argument: $name")

    /**
     * Instrumentation arguments cannot carry spaces through the Gradle/`am
     * instrument` chain, so the mnemonic is passed underscore-separated.
     */
    private fun requiredWords(name: String): String = required(name).replace('_', ' ')

    /** Every step needs the same wallet identity, so the mnemonic is supplied, not generated. */
    private suspend fun <T> withSigner(block: suspend (LocalSigningEngine, WalletIdentity) -> T): T =
        SecretMnemonic.fromWords(requiredWords("mnemonic").toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                UniFfiLocalSigningEngineFactory.openGateway(
                    NETWORK,
                    seed,
                    GatewaySigningContext(required("maxBlockHeight").toInt()),
                ).use { signer -> block(signer, signer.identity()) }
            }
        }

    private fun backend(): RgbLightningNodeGatewayBackend =
        RgbLightningNodeGatewayBackend(
            RgbLightningNodeGatewayConfiguration(
                baseUrl = required("gatewayBaseUrl"),
                expectedNetwork = NETWORK,
                bearerToken = required("bearerToken"),
                // The whole point of the exercise: the send path is gated behind
                // this flag and is unreachable without it.
                allowUnverifiedPsbtProfile = true,
                callTimeoutMillis = 120_000L,
                readTimeoutMillis = 120_000L,
            ),
        )

    private suspend fun RgbLightningNodeGatewayBackend.context(): BackendContext {
        val session = initialize(
            BackendInitializeRequest(
                walletId = WALLET_ID,
                network = NETWORK,
                minimumApiVersion = 1,
                maximumApiVersion = 1,
            ),
        )
        return BackendContext(session = session, walletId = WALLET_ID, network = NETWORK)
    }

    @Test
    public fun step1_registerAndReportAddress(): Unit = runBlocking {
        if (arg("gatewayBaseUrl") == null) return@runBlocking
        withSigner { _, identity ->
            val backend = backend()
            val context = backend.context()
            // Identity is derived from the seed on-device; only xpubs leave it.
            assertEquals(NETWORK, identity.network)
            assertEquals(WalletCapabilities.DERIVATION_PROFILE_V1, identity.derivationProfile)
            backend.registerWallet(RegisterWalletRequest(context, identity))

            val balance = backend.getBtcBalance(context)
            report(
                "STEP1 OK",
                "fingerprint=${identity.masterFingerprint}",
                "vanillaXpub=${identity.accountXpubVanilla}",
                "coloredXpub=${identity.accountXpubColored}",
                "vanillaSettled=${balance.vanilla.settled.value}",
                "vanillaSpendable=${balance.vanilla.spendable.value}",
            )
        }
    }

    /** Read-only probe so funding progress can be watched without re-registering. */
    @Test
    public fun stepX_reportBalance(): Unit = runBlocking {
        if (arg("gatewayBaseUrl") == null) return@runBlocking
        val backend = backend()
        val context = backend.context()
        backend.syncWallet(context)
        val balance = backend.getBtcBalance(context)
        report(
            "BALANCE",
            "vanillaSettled=${balance.vanilla.settled.value}",
            "vanillaSpendable=${balance.vanilla.spendable.value}",
            "coloredSettled=${balance.colored.settled.value}",
        )
    }

    @Test
    public fun step2_prepareSignAndSend(): Unit = runBlocking {
        if (arg("gatewayBaseUrl") == null) return@runBlocking
        val destination = BitcoinAddress.of(required("destination"))
        val amount = Satoshis.of(required("amountSat").toLong())
        withSigner { signer, _ ->
            val backend = backend()
            val context = backend.context()
            backend.syncWallet(context)

            val request = SendBtcRequest(
                address = destination,
                amount = amount,
                feeRate = FeeRate.satPerVbyte("2"),
                idempotencyKey = IdempotencyKey.of(required("idempotencyKey")),
            )
            val operationContext = OperationRequestContext(
                backend = context,
                idempotencyKey = request.idempotencyKey,
                requestHash = RequestHasher.sendBtc(NETWORK, request),
            )

            // 1. The gateway prepares. The adapter checks the returned intent
            //    (kind, recipient, amount, fee rate), the opId, canonical Base64
            //    and the expiry before the PSBT is allowed near the signer.
            val prepared = backend.prepareSendBtc(PrepareSendBtcRequest(operationContext, request))
            assertEquals(OperationKind.SEND_BTC, prepared.operation)

            // 2. Verify-before-sign, locally, against the ORIGINAL request.
            val intent = LocalOperationIntent.SendBtc(
                network = NETWORK,
                policy = LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte("20")),
                request = request,
            )
            val signed = signer.validateAndSign(prepared, intent)
            assertNotEquals(prepared.psbtBase64, signed.signedPsbtBase64)
            // Signatures never change the txid, so the server cannot have swapped
            // the transaction between verification and submission.
            assertEquals(
                signed.validation.unsignedTxid,
                signer.validateRetainedSigning(prepared, intent).unsignedTxid,
            )
            assertEquals(amount, signed.validation.externalOutput)

            // 3. Submit. The adapter binds the submission to the retained preparation.
            val result = backend.submitSignedTransaction(
                SignedTransactionSubmission(
                    context = operationContext,
                    operationId = prepared.operationId,
                    signedPsbtBase64 = signed.signedPsbtBase64,
                    validation = signed.validation,
                ),
                prepared,
            )
            val sendBtc = result as BackendOperationResult.SendBtc
            assertEquals(signed.validation.unsignedTxid, sendBtc.txid)
            assertTrue(sendBtc.txid.hex.length == 64)

            report(
                "STEP2 OK",
                "opId=${prepared.operationId.value}",
                "txid=${sendBtc.txid.hex}",
                "sentSat=${amount.value}",
                "feeSat=${signed.validation.fee.value}",
                "feeRate=${signed.validation.feeRate}",
                "inputs=${signed.validation.inputCount}",
                "outputs=${signed.validation.outputCount}",
                "changeSat=${signed.validation.change.value}",
            )
        }
    }

    /** Instrumentation status output, so the harness can scrape results from logcat. */
    private fun report(vararg fields: String) {
        val line = fields.joinToString(" ")
        android.util.Log.i(TAG, line)
        println("$TAG $line")
    }

    private companion object {
        const val TAG: String = "UTEXO_E2E"
        val NETWORK: Network = Network.SIGNET
        val WALLET_ID: WalletId = WalletId.of("deployed-e2e")
    }
}

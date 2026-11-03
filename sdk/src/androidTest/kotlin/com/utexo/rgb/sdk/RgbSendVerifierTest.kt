package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.GatewaySigningContext
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.LocalSigningEngine
import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.RgbSendVerification
import com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory
import com.utexo.rgb.sdk.internal.ffi.verifier.WalletAccounts
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeHex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline qualification of the shipped `libutexo_rgb_verifier.so` through its Kotlin binding.
 * `rgb-verifier-smoke.json` is a byte-for-byte copy of utexo-rgb-verifier's
 * `tests/fixtures/ffi-smoke.json`: a synthetic blinded send for the public
 * "abandon ... about" wallet on regtest. Never fund those keys.
 */
@RunWith(AndroidJUnit4::class)
public class RgbSendVerifierTest {
    private val fixture: JSONObject = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("rgb-verifier-smoke.json").bufferedReader().use { it.readText() })
    private val evidence: RgbSendEvidence = RgbSendEvidence(
        fixture.getString("fasciaHex").decodeHex().base64(), fixture.getString("mpcEntropy").toULong(),
    )
    private val policy: LocalSigningPolicy = LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte("3"))
    private val request: OnchainSendRequest = OnchainSendRequest(RgbInvoice.of(fixture.getString("invoice")),
        AssetAmount.of(fixture.getLong("amount")), assetId = AssetId.of(fixture.getString("assetId")),
        feeRate = FeeRate.satPerVbyte("2"), idempotencyKey = IdempotencyKey.of("rgb-verifier-smoke"))

    @Test
    public fun shippedVerifierAcceptsTheFixtureAndRejectsACommitmentToOtherBundles(): Unit {
        val accounts = WalletAccounts(fixture.getString("masterFingerprint"),
            fixture.getString("accountXpubVanilla"), fixture.getString("accountXpubColored"))
        val txid = RgbSendVerification.verify(Network.REGTEST, accounts, prepared(evidence), evidence, request,
            fixture.getString("assetId"), policy)
        assertEquals(fixture.getJSONObject("expected").getString("txid"), txid.hex)

        val wrongEntropy = RgbSendEvidence(evidence.fasciaBase64, evidence.mpcEntropy xor 1uL)
        val rejected = expectPolicyRejected {
            RgbSendVerification.verify(Network.REGTEST, accounts, prepared(wrongEntropy), wrongEntropy, request,
                fixture.getString("assetId"), policy)
        }
        check(rejected.contains("does not commit")) { rejected }
    }

    @Test
    public fun gatewaySignerRunsRgbVerificationBeforeSigning(): Unit = runBlocking {
        val words = (List(11) { "abandon" } + "about").joinToString(" ")
        SecretMnemonic.fromWords(words.toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                val context = GatewaySigningContext(110, EpochSecondsSource { 1_789_737_000L })
                UniFfiLocalSigningEngineFactory.openGateway(Network.REGTEST, seed, context).use { signer ->
                    assertEquals(fixture.getString("masterFingerprint"), signer.identity().masterFingerprint)
                    val intent = LocalOperationIntent.OnchainSend(Network.REGTEST, policy, request)

                    val blind = expectPolicyRejected { signer.validateAndSign(prepared(null), intent) }
                    check(blind.contains("no RGB send evidence")) { blind }
                    val wrongEntropy = RgbSendEvidence(evidence.fasciaBase64, evidence.mpcEntropy xor 1uL)
                    val tampered = expectPolicyRejected { signer.validateAndSign(prepared(wrongEntropy), intent) }
                    check(tampered.contains("RGB send verification failed")) { tampered }
                    val retained = expectPolicyRejected { signer.validateRetainedSigning(prepared(wrongEntropy), intent) }
                    check(retained.contains("RGB send verification failed")) { retained }

                    // With the right evidence the RGB check passes and the Bitcoin signer decides.
                    // The synthetic fixture carries no Bitcoin input metadata, so it refuses.
                    val bitcoin = expectPolicyRejected { signer.validateAndSign(prepared(evidence), intent) }
                    check(bitcoin.endsWith("transaction violates local policy")) { bitcoin }
                }
            }
        }
    }

    private fun prepared(evidence: RgbSendEvidence?): PreparedTransaction = PreparedTransaction(
        OperationId.of("rgb-verifier-smoke"), request.idempotencyKey, RequestHasher.onchainSend(Network.REGTEST, request),
        Network.REGTEST, OperationKind.ONCHAIN_SEND, fixture.getString("psbtBase64"), UnixSeconds.of(1_789_740_000L),
        rgbSendEvidence = evidence,
    )

    private inline fun expectPolicyRejected(block: () -> Unit): String {
        try {
            block()
        } catch (rejected: SdkException.PolicyRejected) {
            return rejected.message.orEmpty()
        }
        error("RGB send was not rejected")
    }
}

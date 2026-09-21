package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.GatewaySigningContext
import com.utexo.rgb.sdk.internal.GatewayCreateUtxosPlanExtractor
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.LocalSigningEngine
import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Offline qualification only. Uses public unfunded test keys; never contacts a gateway. */
@RunWith(AndroidJUnit4::class)
public class GatewayNativeProfileTest {
    @Test
    public fun untouchedBtcAndCreateFixturesUseExplicitAdditiveProfileAndIndependentFeeCaps(): Unit = runBlocking {
        val fixtures = fixtures()
        SecretMnemonic.fromWords(fixtures[0].getString("mnemonic").toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                UniFfiLocalSigningEngineFactory.openGateway(Network.REGTEST, seed, context(109)).use { signer ->
                    assertEquals(GatewaySigningContext.PROFILE, signer.capabilities.signingProfile)
                    check(!signer.capabilities.localRgbValidation)
                    for (fixture in fixtures.take(2)) {
                        val (prepared, intent) = operation(fixture)
                        val validated = signer.validateRetainedSigning(prepared, intent)
                        assertEquals(fixture.getString("psbtSha256"), validated.psbtSha256)
                        assertEquals(fixture.getString("txid"), validated.unsignedTxid.hex)
                        val signed = signer.validateAndSign(prepared, intent)
                        assertEquals(validated, signed.validation)
                        assertNotEquals(prepared.psbtBase64, signed.signedPsbtBase64)
                        assertEquals(if (fixture.getString("name") == "send-btc") 309L else 653L, validated.fee.value)
                        // Target 2 is accepted with bounded weight rounding; hard cap 2 is not.
                        expectRejected(signer, prepared, withPolicy(intent,
                            intent.policy.copy(maximumFeeRate = FeeRate.satPerVbyte("2"))))
                        expectRejected(signer, prepared, withPolicy(intent,
                            intent.policy.copy(maximumAbsoluteFee = Satoshis.of(validated.fee.value - 1))))
                    }
                    val (prepared, intent) = operation(fixtures[1])
                    val create = intent as LocalOperationIntent.CreateUtxos
                    assertEquals(create.exactOutputPlan, GatewayCreateUtxosPlanExtractor.extract(
                        prepared.psbtBase64, Network.REGTEST,
                    ))
                    val partialRequest = create.request.copy(upTo = false, num = 6)
                    val partial = signer.validateAndSign(
                        rebound(prepared, hash = RequestHasher.createUtxos(Network.REGTEST, partialRequest)),
                        create.copy(request = partialRequest),
                    )
                    assertEquals(fixtures[1].getString("txid"), partial.validation.unsignedTxid.hex)
                    val alteredOutputs = create.exactOutputPlan.outputs.toMutableList()
                    alteredOutputs[0] = alteredOutputs[0].copy(index = 999)
                    expectRejected(signer, prepared, create.copy(exactOutputPlan = CreateUtxosOutputPlan(alteredOutputs)))
                    expectRejected(signer, prepared, create.copy(request = create.request.copy(size = Satoshis.of(5_001))))
                    expectRejected(signer, prepared, create.copy(request = create.request.copy(num = 4)))
                    expectRejected(signer, prepared, create.copy(policy = create.policy.copy(maximumOutputs = 5)))
                }
                UniFfiLocalSigningEngineFactory.openGateway(Network.REGTEST, seed, context(103)).use { signer ->
                    val (prepared, intent) = operation(fixtures[0])
                    expectRejected(signer, prepared, intent)
                }
                UniFfiLocalSigningEngineFactory.open(Network.REGTEST, seed).use { legacy ->
                    assertEquals(WalletCapabilities.SIGNING_PROFILE_V1, legacy.capabilities.signingProfile)
                    val (prepared, intent) = operation(fixtures[0])
                    expectRejected(legacy, prepared, intent)
                }
            }
        }
    }

    @Test
    public fun untouchedRgbFixtureAuthorizesBitcoinOnlyAndBindsCallerIdentity(): Unit = runBlocking {
        val fixture = fixtures()[2]
        SecretMnemonic.fromWords(fixture.getString("mnemonic").toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                UniFfiLocalSigningEngineFactory.openGateway(Network.REGTEST, seed, context(110)).use { signer ->
                    val (prepared, intent) = operation(fixture)
                    val validation = signer.validateRetainedSigning(prepared, intent)
                    assertEquals(fixture.getString("psbtSha256"), validation.psbtSha256)
                    assertEquals(fixture.getString("txid"), validation.unsignedTxid.hex)
                    assertEquals(309L, validation.fee.value)
                    assertEquals(1, validation.opReturnCount)
                    val signed = signer.validateAndSign(prepared, intent)
                    assertEquals(validation, signed.validation)
                    assertNotEquals(prepared.psbtBase64, signed.signedPsbtBase64)
                    check(!signer.capabilities.localRgbValidation)
                    expectRejected(signer, rebound(prepared, key = IdempotencyKey.of("other-request")), intent)
                    expectRejected(signer, rebound(prepared, hash = RequestHash("0".repeat(64))), intent)
                    expectRejected(signer, prepared, withPolicy(intent,
                        intent.policy.copy(maximumFeeRate = FeeRate.satPerVbyte("2"))))
                    val rgb = intent as LocalOperationIntent.OnchainSend
                    val witnessOnBlind = rgb.request.copy(witnessData = WitnessData(Satoshis.of(1_000)))
                    expectRejected(signer,
                        rebound(prepared, hash = RequestHasher.onchainSend(Network.REGTEST, witnessOnBlind)),
                        rgb.copy(request = witnessOnBlind))
                }
            }
        }
    }

    @Test
    public fun retainedRgbSigningRechecksCurrentTimeWithoutReplacingTheSignature(): Unit = runBlocking {
        val fixture = fixtures()[2]
        var now = 1_789_737_000L
        val context = GatewaySigningContext(110, EpochSecondsSource { now })
        SecretMnemonic.fromWords(fixture.getString("mnemonic").toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                UniFfiLocalSigningEngineFactory.openGateway(Network.REGTEST, seed, context).use { signer ->
                    val (original, originalIntent) = operation(fixture)
                    val rgb = originalIntent as LocalOperationIntent.OnchainSend
                    val request = rgb.request.copy(invoice = RgbInvoice.of("${rgb.request.invoice.value}?expiry=${now + 100}"))
                    val prepared = rebound(original, hash = RequestHasher.onchainSend(Network.REGTEST, request))
                    val intent = rgb.copy(request = request)
                    val signed = signer.validateAndSign(prepared, intent)
                    now++
                    val rechecked = signer.validateRetainedSigning(prepared, intent)
                    assertEquals(signed.validation, rechecked.copy(intentSha256 = signed.validation.intentSha256))
                    // Revalidation uses the original preparation and creates no replacement signature.
                    assertNotEquals(signed.validation.intentSha256, rechecked.intentSha256)
                    now += 99
                    var rejected = false
                    try { signer.validateRetainedSigning(prepared, intent) }
                    catch (_: SdkException.PolicyRejected) { rejected = true }
                    check(rejected) { "Expired retained RGB authorization was accepted" }
                }
            }
        }
    }

    private fun fixtures(): List<JSONObject> {
        val root = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("gateway-pr174-untouched.json").bufferedReader().use { it.readText() })
        val fixtures = root.getJSONArray("fixtures")
        return (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
    }

    private fun operation(fixture: JSONObject): Pair<PreparedTransaction, LocalOperationIntent> {
        val wire = fixture.getJSONObject("prepared")
        val request = fixture.getJSONObject("request")
        val key = IdempotencyKey.of("gateway-native-${fixture.getString("name")}")
        val policy = LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte("3"))
        val intent: LocalOperationIntent
        val hash: RequestHash
        val kind: OperationKind
        var plan: CreateUtxosOutputPlan? = null
        when (fixture.getString("name")) {
            "send-btc" -> {
                val send = SendBtcRequest(
                    BitcoinAddress.of(request.getString("address")), Satoshis.of(request.getLong("amountSat")),
                    FeeRate.satPerVbyte("2"), idempotencyKey = key,
                )
                intent = LocalOperationIntent.SendBtc(Network.REGTEST, policy, send)
                hash = RequestHasher.sendBtc(Network.REGTEST, send)
                kind = OperationKind.SEND_BTC
            }
            "create-utxos" -> {
                val create = CreateUtxosRequest(upTo = false, num = request.getInt("num"),
                    size = Satoshis.of(request.getLong("size")), feeRate = FeeRate.satPerVbyte("2"), idempotencyKey = key)
                val indices = fixture.getJSONArray("coloredOutputIndices")
                plan = CreateUtxosOutputPlan((0 until indices.length()).map {
                    ExpectedWalletOutput(AccountKind.COLORED, Keychain.EXTERNAL, indices.getInt(it), create.size!!)
                })
                intent = LocalOperationIntent.CreateUtxos(Network.REGTEST, policy, create, plan)
                hash = RequestHasher.createUtxos(Network.REGTEST, create)
                kind = OperationKind.CREATE_UTXOS
            }
            "send-asset" -> {
                // Canonical serialization of this public fixture's exact asset and
                // blind recipient; original PSBT bytes remain untouched.
                val invoice = RgbInvoice.of("${request.getString("assetId")}/~/~/${request.getString("recipientId")}")
                val send = OnchainSendRequest(invoice, AssetAmount.of(request.getLong("amount")),
                    assetId = AssetId.of(request.getString("assetId")),
                    feeRate = FeeRate.satPerVbyte("2"), idempotencyKey = key)
                intent = LocalOperationIntent.OnchainSend(Network.REGTEST, policy, send)
                hash = RequestHasher.onchainSend(Network.REGTEST, send)
                kind = OperationKind.ONCHAIN_SEND
            }
            else -> error("unsupported test operation")
        }
        return PreparedTransaction(OperationId.of(wire.getString("opId")), key, hash, Network.REGTEST,
            kind, wire.getString("psbt"), UnixSeconds.of(wire.getLong("expiresAt") / 1_000),
            createUtxosOutputPlan = plan) to intent
    }

    private fun withPolicy(intent: LocalOperationIntent, policy: LocalSigningPolicy): LocalOperationIntent = when (intent) {
        is LocalOperationIntent.SendBtc -> intent.copy(policy = policy)
        is LocalOperationIntent.CreateUtxos -> intent.copy(policy = policy)
        is LocalOperationIntent.OnchainSend -> intent.copy(policy = policy)
    }

    private fun rebound(
        prepared: PreparedTransaction,
        key: IdempotencyKey = prepared.idempotencyKey,
        hash: RequestHash = prepared.requestHash,
    ): PreparedTransaction = PreparedTransaction(
        prepared.operationId, key, hash, prepared.network, prepared.operation,
        prepared.psbtBase64, prepared.expiresAt,
        createUtxosOutputPlan = prepared.createUtxosOutputPlan, correlationId = prepared.correlationId,
    )

    private fun context(height: Int): GatewaySigningContext =
        GatewaySigningContext(height, EpochSecondsSource { 1_789_737_000L })

    private suspend fun expectRejected(signer: LocalSigningEngine, prepared: PreparedTransaction, intent: LocalOperationIntent): Unit {
        var rejected = false
        try {
            signer.validateAndSign(prepared, intent)
        } catch (_: SdkException.PolicyRejected) {
            rejected = true
        }
        check(rejected) { "Unsafe or incompatible prepared transaction was not rejected" }
    }
}

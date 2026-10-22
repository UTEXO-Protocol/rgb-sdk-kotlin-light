package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.OperationCoordinator
import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.LocalSigningEngine
import com.utexo.rgb.sdk.internal.LocalSigningEngineFactory
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.LocalSigningResult
import com.utexo.rgb.sdk.internal.GatewayRgbInvoice
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class RgbLightningNodeGatewayBackendTest {
    @Test
    fun `RGB gateway coordinator routes exact request and recovers original operation without signing again`(): Unit = runBlocking {
        val request = rgbSendRequest()
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, preparedRgbJson()),
            queuedResponse(503, "{\"error\":{\"code\":\"UNAVAILABLE\"}}"),
            queuedResponse(200, rgbStatusJson("pending")), queuedResponse(200, rgbStatusJson("completed")))
        val backend = testBackend(transport, invoiceDecoder = { rgbDecodedInvoice() })
        val context = initializedContext(backend)
        val journal = InMemoryOperationJournal()
        val report = testValidation().copy(psbtSha256 = TEST_PSBT_BASE64.decodeBase64()!!.sha256().hex())
        var signatures = 0
        var validations = 0
        var invoiceChecks = 0
        var chainAvailable = true
        val signer = object : LocalSigningEngine by FakeSigningEngine(testCapabilities(setOf(OperationKind.ONCHAIN_SEND))) {
            override suspend fun prepareSigningContext() { check(chainAvailable) }
            override suspend fun validateAndSign(prepared: PreparedTransaction, intent: LocalOperationIntent): LocalSigningResult {
                signatures++; return LocalSigningResult(TEST_PSBT_BASE64, report)
            }
            override suspend fun validateRetainedSigning(prepared: PreparedTransaction, intent: LocalOperationIntent): LocalValidationReport {
                validations++; return report
            }
        }
        val coordinator = OperationCoordinator(gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW })
        assertFailsWith<SdkException.OperationAmbiguous> {
            coordinator.onchainSend(context, signer, request) { invoiceChecks++ }
        }
        assertEquals(OperationPhase.SUBMITTING, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        chainAvailable = false
        val checks = invoiceChecks
        val result = coordinator.onchainSend(context, signer, request) { error("recovery must not revalidate expired invoice") }
        assertEquals(OnchainSendResponse(TEST_TXID, null), result)
        assertEquals(result, coordinator.onchainSend(context, signer, request) { error("terminal replay cannot require invoice or chain") })
        assertEquals(checks, invoiceChecks)
        assertEquals(1, signatures)
        assertEquals(1, validations)
        assertEquals(listOf("/v1/me", "/v1/onchain/send-asset/prepare", "/v1/onchain/send-asset/complete",
            "/v1/onchain/operations/$TEST_OPERATION_ID", "/v1/onchain/operations/$TEST_OPERATION_ID"), transport.requests.map { it.url.encodedPath })
        val body = Json.parseToJsonElement(transport.requests[1].bodyUtf8()).jsonObject
        assertEquals(JsonPrimitive("fixture-asset"), body["assetId"])
        assertEquals(JsonPrimitive(40), body["amount"])
        assertEquals(JsonPrimitive(false), body["donation"])
        assertEquals(JsonPrimitive(1), body["minConfirmations"])
        assertEquals(null, body["witnessAmountSat"])
        assertEquals(Json.parseToJsonElement("[\"rpc://proxy.example/json-rpc\"]"), body["transportEndpoints"])
        assertNotEquals(transport.requests[1].header("Idempotency-Key"), transport.requests[2].header("Idempotency-Key"))
    }

    @Test
    fun `RGB prepare rejects altered intent before signing and unsupported native options before journal mutation`(): Unit = runBlocking {
        for (body in listOf(preparedRgbJson().replace("send_asset", "send_btc"),
            preparedRgbJson().replace("\"amount\":40", "\"amount\":41"),
            preparedRgbJson().replace("fixture-asset", "other-asset"),
            preparedRgbJson().replace("recipient-fixture", "other-recipient"),
            preparedRgbJson().replace("\"witnessAmountSat\":null", "\"witnessAmountSat\":500"),
            preparedRgbJson().replace("proxy.example", "evil.example"),
            preparedRgbJson().replace("\"utxos\":null", "\"utxos\":{}"))) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, body))
            val backend = testBackend(transport, invoiceDecoder = { rgbDecodedInvoice() })
            val context = initializedContext(backend)
            val request = rgbSendRequest()
            assertFailsWith<SdkException.BackendProtocolMismatch> {
                backend.prepareOnchainSend(PrepareOnchainSendRequest(OperationRequestContext(context,
                    request.idempotencyKey, RequestHasher.onchainSend(Network.SIGNET, request)), request))
            }
        }
        val backend = testBackend(QueueInterceptor(), invoiceDecoder = { rgbDecodedInvoice() })
        for (request in listOf(rgbSendRequest().copy(skipSync = true),
            rgbSendRequest().copy(witnessData = WitnessData(Satoshis.of(500), 1)),
            rgbSendRequest().copy(amount = AssetAmount.parse("9007199254740992")),
            rgbSendRequest().copy(minConfirmations = BlockCount.of(101)))) {
            val journal = InMemoryOperationJournal()
            assertFailsWith<SdkException.Unsupported> {
                OperationCoordinator(gatewayWalletConfiguration(), backend, journal).onchainSend(inactiveContext(),
                    FakeSigningEngine(testCapabilities(setOf(OperationKind.ONCHAIN_SEND))), request) { }
            }
            assertEquals(null, journal.load(TEST_WALLET_ID, request.idempotencyKey))
        }
    }

    @Test
    fun `RGB prepare refuses witness data that contradicts the invoice recipient mode`(): Unit = runBlocking {
        // rgb-lib rejects a witness beneficiary without sats and a blind one with
        // them; the mismatch is caught locally rather than at the gateway.
        for (witnessInvoice in listOf(true, false)) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON))
            val backend = testBackend(transport, invoiceDecoder = { rgbDecodedInvoice().copy(witness = witnessInvoice) })
            val context = initializedContext(backend)
            // Deliberately the WRONG pairing for this invoice kind.
            val request = rgbSendRequest().copy(witnessData = if (witnessInvoice) null else WitnessData(Satoshis.of(1_000)))
            assertFailsWith<SdkException.InvalidArgument> {
                backend.prepareOnchainSend(PrepareOnchainSendRequest(OperationRequestContext(context,
                    request.idempotencyKey, RequestHasher.onchainSend(Network.SIGNET, request)), request))
            }
            assertEquals(1, transport.requests.size)
        }
    }

    private fun rgbSendRequest(): OnchainSendRequest = OnchainSendRequest(RgbInvoice.of("rgb:fixture"),
        AssetAmount.of(40), AssetId.of("fixture-asset"), idempotencyKey = TEST_IDEMPOTENCY_KEY)

    private fun rgbDecodedInvoice(): GatewayRgbInvoice = GatewayRgbInvoice(Network.SIGNET, "fixture-asset",
        "recipient-fixture", false, listOf("rpc://proxy.example/json-rpc"))

    private fun preparedRgbJson(): String = """{"opId":"$TEST_OPERATION_ID","psbt":"$TEST_PSBT_BASE64","expiresAt":2000123,"intent":{"kind":"send_asset","feeRateSatPerVb":2,"recipients":[],"utxos":null,"asset":{"assetId":"fixture-asset","amount":40,"recipientId":"recipient-fixture","witnessAmountSat":null,"transportEndpoints":["rpc://proxy.example/json-rpc"]}}}"""

    private fun rgbStatusJson(state: String): String {
        val intent = Json.parseToJsonElement(preparedRgbJson()).jsonObject.getValue("intent")
        return """{"opId":"$TEST_OPERATION_ID","kind":"send_asset","state":"$state","txid":${if (state == "completed") "\"${TEST_TXID.hex}\"" else "null"},"mayHaveBroadcast":false,"intent":$intent,"createdAt":1000000,"expiresAt":2000123}"""
    }
    @Test
    fun `gateway newly supported public facade methods preserve missing metadata and never sync implicitly`(): Unit = runBlocking {
        val address = """{"address":"${TEST_GATEWAY_ADDRESS.value}","derivation":{"account":"vanilla","keychain":0,"index":7,"derivationPath":"m/86'/1'/0'/0/7","scriptHex":"5120${"a".repeat(64)}"}}"""
        val unspents = unspentsJson()
        val transfers = transfersJson()
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON),
            queuedResponse(201, "{\"fingerprint\":\"deadbeef\",\"address\":\"fixture-address\"}"),
            queuedResponse(200, address), queuedResponse(200, address), queuedResponse(200, unspents),
            queuedResponse(200, "{\"blocks\":6,\"sourceBlocks\":3,\"feeRateSatPerVb\":2}"),
            queuedResponse(200, assetsJson()), queuedResponse(200, transfers), queuedResponse(200, transfers),
            queuedResponse(200, transfers), queuedResponse(201, receiveJson("blind")), queuedResponse(201, receiveJson("witness")))
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val signer = FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), InvoiceData(
            RgbInvoice.of("rgb:fixture"), "recipient-fixture", network = Network.SIGNET,
            assignment = Assignment(AssignmentType.ANY), expirationTimestamp = UnixSeconds.of(87_900)))
        val engine = object : LocalSigningEngine by signer {
            override suspend fun identity(): WalletIdentity = gatewayIdentity()
            override suspend fun verifyReservedAddress(reservation: ReservedAddress) {
                assertEquals(7, reservation.index)
                assertEquals("5120${"a".repeat(64)}", reservation.scriptPubkeyHex)
                signer.verifyReservedAddress(reservation)
            }
        }
        val wallet = UTEXOWallet(gatewayWalletConfiguration(), backend,
            WalletKeyProvider { SecretSeed.fromBytes(ByteArray(64) { 1 }) }, InMemoryOperationJournal(),
            LocalSigningEngineFactory { _, _ -> engine }, EpochSecondsSource { 1_500L })
        wallet.initialize(); wallet.unlock()
        try {
            assertEquals(TEST_GATEWAY_ADDRESS, wallet.getAddress())
            assertEquals(TEST_GATEWAY_ADDRESS, wallet.rotateVanillaAddress())
            assertEquals(2, signer.addressChecks)
            val unspent = wallet.listUnspents().single()
            assertEquals(null, unspent.utxo.exists)
            assertEquals(2, unspent.pendingBlinded)
            assertEquals(Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(7)), unspent.rgbAllocations.single().assignment)
            assertEquals(FeeRate.satPerVbyte("2"), wallet.estimateFeeRate(BlockCount.of(6)).feeRate)
            val assets = wallet.listAssets()
            assertEquals(AssetAmount.of(100), assets.nia.single().issuedSupply)
            assertEquals(null, assets.ifa.single().initialSupply)
            assertEquals(null, assets.ifa.single().maxSupply)
            assertEquals(null, assets.ifa.single().knownCirculatingSupply)
            val listed = wallet.listTransfers(AssetId.of("fixture-asset")).single()
            assertEquals(null, listed.batchTransferIdx)
            assertEquals(null, listed.requestedAssignment)
            assertEquals(null, listed.transportEndpoints)
            assertEquals(null, listed.expiration)
            assertEquals(AssetAmount.of(7), listed.assignments.single().amount)
            assertEquals(listOf(listed), wallet.listTransfersByTxid(TEST_TXID))
            assertEquals(listOf(listed), wallet.listOnchainTransfers())
            signer.decodedWitness = false
            assertEquals(42L, wallet.blindReceive(InvoiceRequest()).batchTransferIdx)
            signer.decodedWitness = true
            assertEquals(42L, wallet.witnessReceive(InvoiceRequest()).batchTransferIdx)
            assertEquals(12, transport.requests.size)
            assertFalse(transport.requests.any { it.url.encodedPath.endsWith("/sync") })
            assertEquals("fixture-asset", transport.requests[7].url.queryParameter("assetId"))
            assertEquals(null, transport.requests[8].url.queryParameter("assetId"))
            assertEquals(0, signer.signCalls)
        } finally { wallet.destroy() }
    }

    @Test
    fun `address derivation malformed or unattributed cannot become a deposit address`(): Unit = runBlocking {
        val valid = """{"address":"${TEST_GATEWAY_ADDRESS.value}","derivation":{"account":"vanilla","keychain":0,"index":7,"derivationPath":"m/86'/1'/0'/0/7","scriptHex":"5120${"a".repeat(64)}"}}"""
        for (body in listOf("{\"address\":\"bad\",\"derivation\":null}", valid.replace("m/86'/1'", "m/86'/0'"),
            valid.replace("\"keychain\":0", "\"keychain\":1"), valid.replace("\"index\":7", "\"index\":2147483648"),
            valid.replace("5120", "0014"), valid.replace("vanilla", "colored"))) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch> { backend.reserveAddress(ReserveAddressRequest(context, rotate = false)) }
        }
    }

    @Test
    fun `fee source targets and backend cap never silently weaken requested estimate`(): Unit = runBlocking {
        for ((body, unsupported) in listOf(
            "{\"blocks\":1,\"sourceBlocks\":6,\"feeRateSatPerVb\":2}" to true,
            "{\"blocks\":1,\"sourceBlocks\":1,\"feeRateSatPerVb\":1000}" to true,
            "{\"blocks\":2,\"sourceBlocks\":1,\"feeRateSatPerVb\":2}" to false,
            "{\"blocks\":1,\"sourceBlocks\":1,\"feeRateSatPerVb\":1.5}" to false,
            "{\"blocks\":1,\"sourceBlocks\":0,\"feeRateSatPerVb\":2}" to false)) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            if (unsupported) assertFailsWith<SdkException.Unsupported> { backend.estimateFeeRate(context, BlockCount.of(1)) }
            else assertFailsWith<SdkException.BackendProtocolMismatch> { backend.estimateFeeRate(context, BlockCount.of(1)) }
            assertEquals("1", transport.requests.last().url.queryParameter("blocks"))
        }
    }

    @Test
    fun `unspents require exact typed assignments and reject malformed states and duplicate outpoints`(): Unit = runBlocking {
        val valid = unspentsJson()
        val entry = Json.parseToJsonElement(valid).jsonObject["unspents"] as kotlinx.serialization.json.JsonArray
        val bad = listOf(valid.replace("\"pendingBlinded\":2", "\"pendingBlinded\":-1"),
            valid.replace("\"colorable\":true", "\"colorable\":\"true\""),
            valid.replace("\"amountSat\":1000", "\"amountSat\":2100000000000001"),
            valid.replace("\"kind\":\"Fungible\",\"amount\":7", "\"kind\":\"Fungible\",\"amount\":null"),
            valid.replace("\"amount\":7,\"assignment\"", "\"amount\":null,\"assignment\""),
            valid.replace("Fungible", "NonFungible"), valid.replace("\"exists\":null", "\"exists\":1"),
            "{\"unspents\":[${entry.single()},${entry.single()}]}")
        for (body in bad) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch>(body) { backend.listUnspents(context) }
        }
    }

    @Test
    fun `unspents map every wire field including reported existence and unit assignments`(): Unit = runBlocking {
        val body = """{"unspents":[{"txid":"${"ab".repeat(32)}","vout":2,"amountSat":9999,
            "colorable":true,"exists":true,"pendingBlinded":3,
            "allocations":[{"assetId":"rgb:a1","amount":42,
              "assignment":{"kind":"Fungible","amount":42},"settled":true},
             {"assetId":null,"amount":null,
              "assignment":{"kind":"Any","amount":null},"settled":false}]}]}"""
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
        val backend = testBackend(transport)
        val only = backend.listUnspents(initializedContext(backend)).single()
        assertEquals(Outpoint(TransactionId.of("ab".repeat(32)), 2), only.utxo.outpoint)
        assertEquals(9999L, only.utxo.btcAmount.value)
        assertTrue(only.utxo.colorable)
        assertEquals(true, only.utxo.exists)
        assertEquals(3, only.pendingBlinded)
        assertEquals(RgbAllocation(AssetId.of("rgb:a1"), Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(42)), true),
            only.rgbAllocations[0])
        // A unit variant keeps its name and carries no amount.
        assertEquals(RgbAllocation(null, Assignment(AssignmentType.ANY), false), only.rgbAllocations[1])
    }

    @Test
    fun `asset decoder rejects malformed supplies and unknown schemas without hiding assets`(): Unit = runBlocking {
        for (body in listOf(assetsJson().replace("\"schema\":\"nia\"", "\"schema\":\"future\""),
            assetsJson().replace("\"issuedSupply\":100", "\"issuedSupply\":null"),
            assetsJson().replace("\"issuedSupply\":100", "\"issuedSupply\":9007199254740992"),
            assetsJson().replace("\"precision\":0", "\"precision\":19"),
            assetsJson().replace("\"timestamp\":1", "\"timestamp\":-1"),
            assetsJson().replace("ifa-asset", "nia-asset"))) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch>(body) { backend.listAssets(context) }
        }
    }

    @Test
    fun `asset display data preserves whitespace and Unicode while protocol identifiers remain strict`(): Unit = runBlocking {
        val body = assetsJson().replace("\"name\":\"Fixture\"", "\"name\":\" Fixture \"")
            .replace("\"details\":null", "\"details\":\"  Caf\\u00e9\\nnotes  \"")
        val backend = testBackend(QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body)))
        val result = backend.listAssets(initializedContext(backend)).nia.single()
        assertEquals(" Fixture ", result.name)
        assertEquals("  Café\nnotes  ", result.details)
    }

    @Test
    fun `display metadata bounds are UTF8 byte limits with exact boundary accepted`(): Unit = runBlocking {
        val name = "é".repeat(128)
        val details = "é".repeat(32_767) + "a"
        fun body(nameValue: String, detailsValue: String): String = assetsJson()
            .replace("\"name\":\"Fixture\"", "\"name\":\"$nameValue\"")
            .replace("\"details\":null", "\"details\":\"$detailsValue\"")
        val backend = testBackend(QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body(name, details))))
        val result = backend.listAssets(initializedContext(backend)).nia.single()
        assertEquals(name, result.name)
        assertEquals(details, result.details)
        for (bad in listOf(body(name + "a", details), body(name, details + "a"), body("", details), body(name, ""))) {
            val invalid = testBackend(QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, bad)))
            val context = initializedContext(invalid)
            assertFailsWith<SdkException.BackendProtocolMismatch> { invalid.listAssets(context) }
        }
    }

    @Test
    fun `additive known IFA supplies and existence state are retained rather than discarded`(): Unit = runBlocking {
        val body = assetsJson().replace("\"schema\":\"ifa\"", "\"schema\":\"ifa\",\"initialSupply\":100,\"maxSupply\":200,\"knownCirculatingSupply\":150")
        val backend = testBackend(QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body),
            queuedResponse(200, unspentsJson().replace("\"exists\":null", "\"exists\":false"))))
        val context = initializedContext(backend)
        val result = backend.listAssets(context).ifa.single()
        assertEquals(AssetAmount.of(100), result.initialSupply)
        assertEquals(AssetAmount.of(200), result.maxSupply)
        assertEquals(AssetAmount.of(150), result.knownCirculatingSupply)
        assertEquals(false, backend.listUnspents(context).single().utxo.exists)
        for (bad in listOf(body.replace("\"maxSupply\":200", "\"maxSupply\":99"),
            body.replace("\"initialSupply\":100", "\"initialSupply\":\"100\""))) {
            val invalid = testBackend(QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, bad)))
            val invalidContext = initializedContext(invalid)
            assertFailsWith<SdkException.BackendProtocolMismatch> { invalid.listAssets(invalidContext) }
        }
    }

    @Test
    fun `transfers preserve actual assignments and reject malformed history rather than inventing results`(): Unit = runBlocking {
        for (body in listOf(transfersJson().replace("\"updatedAt\":2", "\"updatedAt\":0"),
            transfersJson().replace("\"batchTransferIdx\":null", "\"batchTransferIdx\":-1"),
            transfersJson().replace("\"amount\":7}", "\"amount\":null}"),
            transfersJson().replace("\"assignments\":[", "\"assignments\":null,\"other\":["),
            transfersJson().replace("\"expiration\":null", "\"expiration\":1.5"))) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch>(body) { backend.listTransfers(context) }
        }
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, transfersJson()))
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        assertFailsWith<SdkException.BackendProtocolMismatch> { backend.listTransfers(context, AssetId.of("other & asset")) }
        assertEquals("other & asset", transport.requests.last().url.queryParameter("assetId"))
    }

    @Test
    fun `strict receives require actual batch identity but unified receive remains compatible`(): Unit = runBlocking {
        for (mode in listOf("blind", "witness")) for (batch in listOf("null", "-1", "9007199254740992", "\"42\"")) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201,
                receiveJson(mode).replace("\"batchTransferIdx\":42", "\"batchTransferIdx\":$batch")))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch> {
                if (mode == "blind") backend.blindReceive(context, InvoiceRequest()) else backend.witnessReceive(context, InvoiceRequest())
            }
            assertEquals(2, transport.requests.size)
        }
    }

    private fun unspentsJson(): String = """{"unspents":[{"txid":"${TEST_TXID.hex}","vout":0,"amountSat":1000,"colorable":true,"exists":null,"pendingBlinded":2,"allocations":[{"assetId":null,"amount":7,"assignment":{"kind":"Fungible","amount":7},"settled":false}]}]}"""

    private fun assetsJson(): String {
        fun asset(schema: String): String = """{"assetId":"$schema-asset","schema":"$schema","ticker":"TEST","name":"Fixture","details":null,"precision":0,"issuedSupply":${if (schema == "nia") "100" else "null"},"timestamp":1,"addedAt":2,"balance":{"settled":0,"future":7,"spendable":0}}"""
        return """{"btc":{"vanilla":{"settled":0,"future":0,"spendable":0},"colored":{"settled":0,"future":0,"spendable":0}},"assets":[${asset("nia")},${asset("ifa")}]}"""
    }

    private fun transfersJson(): String = """{"transfers":[{"idx":1,"batchTransferIdx":null,"assetId":"fixture-asset","amount":99,"assignments":[{"kind":"Fungible","amount":7}],"kind":"Send","status":"Settled","txid":"${TEST_TXID.hex}","recipientId":null,"expiration":null,"createdAt":1,"updatedAt":2}]}"""

    private fun receiveJson(mode: String): String = """{"invoice":"rgb:fixture","recipientId":"recipient-fixture","mode":"$mode","expirationTimestamp":87900,"batchTransferIdx":42}"""

    @Test
    fun `additive transfer metadata is preserved with absent and explicit empty distinguished`(): Unit = runBlocking {
        val extra = """"expirationTimestamp":50,"receiveUtxo":{"txid":"${TEST_TXID.hex}","vout":2},"changeUtxo":null,"transportEndpoints":[{"endpoint":"https://proxy.example","transportType":"JsonRpc","used":true}],"expiration":null"""
        val body = transfersJson().replace("\"expiration\":null", extra)
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body),
            queuedResponse(200, transfersJson().replace("\"expiration\":null", "\"expiration\":null,\"transportEndpoints\":[]")))
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val result = backend.listTransfers(context).single()
        assertEquals(UnixSeconds.of(50), result.expiration)
        assertEquals(Outpoint(TEST_TXID, 2), result.receiveUtxo)
        assertEquals(listOf(TransferTransportEndpoint("https://proxy.example", "JsonRpc", true)), result.transportEndpoints)
        assertEquals(emptyList(), backend.listTransfers(context).single().transportEndpoints)
        for (bad in listOf(body.replace("\"expiration\":null", "\"expiration\":51"),
            body.replace("\"vout\":2", "\"vout\":-1"), body.replace("\"used\":true", "\"used\":1"))) {
            val badBackend = testBackend(QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, bad)))
            val badContext = initializedContext(badBackend)
            assertFailsWith<SdkException.BackendProtocolMismatch> { badBackend.listTransfers(badContext) }
        }
    }

    @Test
    fun `read adapters propagate server and stale session failures without automatic retry`(): Unit = runBlocking {
        val operations: List<suspend (RgbLightningNodeGatewayBackend, BackendContext) -> Unit> = listOf(
            { backend, context -> backend.reserveAddress(ReserveAddressRequest(context, rotate = false)) },
            { backend, context -> backend.listUnspents(context) },
            { backend, context -> backend.listAssets(context) },
            { backend, context -> backend.listTransfers(context) },
            { backend, context -> backend.estimateFeeRate(context, BlockCount.of(6)) },
        )
        for (operation in operations) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(503, "{\"error\":{\"code\":\"UNAVAILABLE\"}}"))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendUnavailable> { operation(backend, context) }
            assertEquals(2, transport.requests.size)
            backend.shutdown(context.session)
            assertFailsWith<SdkException.InvalidState> { operation(backend, context) }
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun `gateway request-domain failures do not create a journal entry or acquire credentials`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport)
        var credentials = 0
        backend.configureCredentials(WalletAPIKeyProvider { credentials++; WalletAPIKey.fromCharacters("credential".toCharArray()) })
        val journal = InMemoryOperationJournal()
        val coordinator = OperationCoordinator(gatewayWalletConfiguration(), backend, journal)
        val signer = FakeSigningEngine(testCapabilities(setOf(OperationKind.SEND_BTC, OperationKind.CREATE_UTXOS)))
        for (request in listOf(sendBtcRequest().copy(feeRate = FeeRate.satPerVbyte("1.5")),
            sendBtcRequest().copy(amount = Satoshis.of(293)))) {
            assertFailsWith<SdkException.InvalidArgument> { coordinator.sendBtc(inactiveContext(), signer, request) }
        }
        assertFailsWith<SdkException.Unsupported> { coordinator.sendBtc(inactiveContext(), signer, sendBtcRequest(true)) }
        val create = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        for (request in listOf(create.copy(feeRate = FeeRate.satPerVbyte("1.5")), create.copy(num = 51),
            create.copy(size = Satoshis.of(329)))) {
            assertFailsWith<SdkException.InvalidArgument> { coordinator.createUtxos(inactiveContext(), signer, request) }
        }
        assertFailsWith<SdkException.Unsupported> { coordinator.createUtxos(inactiveContext(), signer, create.copy(skipSync = true)) }
        assertEquals(null, journal.load(TEST_WALLET_ID, TEST_IDEMPOTENCY_KEY))
        assertEquals(0, credentials)
        assertEquals(0, signer.signCalls)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `create prepare explicitly maps defaults extracts hints and completion returns actual partial count`(): Unit = runBlocking {
        for (upTo in listOf(true, false)) {
            val request = CreateUtxosRequest(upTo = upTo, idempotencyKey = TEST_IDEMPOTENCY_KEY)
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON),
                queuedResponse(201, preparedCreateJson(request)),
                queuedResponse(200, "{\"txid\":\"${TEST_TXID.hex}\",\"utxosCreated\":2}"))
            var extracts = 0
            val backend = testBackend(transport, createPlanExtractor = { psbt, network ->
                assertEquals(TEST_PSBT_BASE64, psbt); assertEquals(Network.SIGNET, network)
                extracts++; createPlan()
            })
            val context = initializedContext(backend)
            val opContext = OperationRequestContext(context, request.idempotencyKey, RequestHasher.createUtxos(Network.SIGNET, request))
            val prepared = backend.prepareCreateUtxos(PrepareCreateUtxosRequest(opContext, request))
            assertEquals(createPlan(), prepared.createUtxosOutputPlan)
            assertEquals(UnixSeconds.of(2_000L), prepared.expiresAt)
            val submitted = SignedTransactionSubmission(opContext, prepared.operationId, TEST_PSBT_BASE64,
                testValidation().copy(psbtSha256 = TEST_PSBT_BASE64.decodeBase64()!!.sha256().hex()))
            val result = backend.submitSignedTransaction(submitted, prepared) as BackendOperationResult.CreateUtxos
            assertEquals(CreateUtxosResult(TEST_TXID, 2), result.result)
            assertEquals(1, extracts)
            assertEquals("/v1/onchain/create-utxos/prepare", transport.requests[1].url.encodedPath)
            assertEquals("/v1/onchain/create-utxos/complete", transport.requests[2].url.encodedPath)
            assertEquals(Json.parseToJsonElement("{\"num\":5,\"size\":1000,\"upTo\":$upTo,\"feeRateSatPerVb\":2}"),
                Json.parseToJsonElement(transport.requests[1].bodyUtf8()))
            assertNotEquals(transport.requests[1].header("Idempotency-Key"), transport.requests[2].header("Idempotency-Key"))
        }
    }

    @Test
    fun `qualified create coordinator validates extracted plan before submit and ordinary gate stays closed`(): Unit = runBlocking {
        val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, preparedCreateJson(request)),
            queuedResponse(200, "{\"txid\":\"${TEST_TXID.hex}\",\"utxosCreated\":2}"))
        val backend = testBackend(transport, createPlanExtractor = { _, _ -> createPlan() })
        val context = initializedContext(backend)
        val journal = InMemoryOperationJournal()
        var signed = 0
        val signer = object : LocalSigningEngine by FakeSigningEngine(testCapabilities(setOf(OperationKind.CREATE_UTXOS))) {
            override suspend fun validateRetainedSigning(prepared: PreparedTransaction, intent: LocalOperationIntent): LocalValidationReport =
                testValidation().copy(psbtSha256 = TEST_PSBT_BASE64.decodeBase64()!!.sha256().hex())
            override suspend fun validateAndSign(prepared: PreparedTransaction, intent: LocalOperationIntent): LocalSigningResult {
                signed++
                assertEquals(OperationPhase.PREPARED, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
                assertEquals(createPlan(), prepared.createUtxosOutputPlan)
                assertEquals(createPlan(), (intent as LocalOperationIntent.CreateUtxos).exactOutputPlan)
                return LocalSigningResult(TEST_PSBT_BASE64,
                    testValidation().copy(psbtSha256 = TEST_PSBT_BASE64.decodeBase64()!!.sha256().hex()))
            }
        }
        val coordinator = OperationCoordinator(gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW })
        assertEquals(CreateUtxosResult(TEST_TXID, 2), coordinator.createUtxos(context, signer, request))
        assertEquals(CreateUtxosResult(TEST_TXID, 2), coordinator.createUtxos(context, signer, request))
        assertEquals(1, signed)
        assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        assertEquals(listOf("/v1/me", "/v1/onchain/create-utxos/prepare", "/v1/onchain/create-utxos/complete"),
            transport.requests.map { it.url.encodedPath })

        val blockedTransport = QueueInterceptor()
        val blocked = testBackend(blockedTransport, allowUnverifiedPsbtProfile = false)
        val blockedJournal = InMemoryOperationJournal()
        assertFailsWith<SdkException.Unsupported> {
            OperationCoordinator(gatewayWalletConfiguration(), blocked, blockedJournal)
                .createUtxos(inactiveContext(), signer, request)
        }
        assertEquals(null, blockedJournal.load(TEST_WALLET_ID, request.idempotencyKey))
        assertTrue(blockedTransport.requests.isEmpty())
        assertEquals(1, signed)
    }

    @Test
    fun `create preparation binds every intent field and rejects malformed shapes before extracting`(): Unit = runBlocking {
        val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val valid = preparedCreateJson(request)
        val variants = listOf(valid.replace("create_utxos", "send_btc"),
            valid.replace("\"feeRateSatPerVb\":2", "\"feeRateSatPerVb\":3"),
            valid.replace("\"recipients\":[]", "\"recipients\":[{}]"),
            valid.replace("\"asset\":null", "\"asset\":{}"),
            valid.replace("\"upTo\":true", "\"upTo\":false"),
            valid.replace("\"upTo\":true", "\"upTo\":\"true\""),
            valid.replace("\"num\":5", "\"num\":4"),
            valid.replace("\"size\":1000", "\"size\":1001"),
            valid.replace("\"utxos\":{", "\"utxos\":null,\"unused\": {"),
            valid.replace(TEST_OPERATION_ID, "INVALID-UUID"),
            valid.replace(TEST_PSBT_BASE64, "$TEST_PSBT_BASE64 "),
            valid.replace("2000123", "1500000"))
        for (body in variants) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch>(body) {
                backend.prepareCreateUtxos(PrepareCreateUtxosRequest(operationContext(context), request))
            }
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun `create output hints must match count size account branch and unique paths`(): Unit = runBlocking {
        val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val one = createPlan().outputs.first()
        val invalid = listOf(CreateUtxosOutputPlan(List(6) { one.copy(index = it) }),
            CreateUtxosOutputPlan(listOf(one.copy(amount = Satoshis.of(999)))),
            CreateUtxosOutputPlan(listOf(one.copy(account = AccountKind.VANILLA))),
            CreateUtxosOutputPlan(listOf(one.copy(keychain = Keychain.CHANGE))),
            CreateUtxosOutputPlan(listOf(one, one)))
        for (plan in invalid) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, preparedCreateJson(request)))
            val backend = testBackend(transport, createPlanExtractor = { _, _ -> plan })
            assertFailsWith<SdkException.BackendProtocolMismatch> {
                backend.prepareCreateUtxos(PrepareCreateUtxosRequest(operationContext(initializedContext(backend)), request))
            }
        }
    }

    @Test
    fun `create submitted recovery works with gate disabled and past TTL without signing or post`(): Unit = runBlocking {
        val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val checkpoint = createCheckpoint(request)
        val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, createStatusJson(request)))
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false, clock = { 9_000_000L })
        val context = initializedContext(backend)
        val signer = FakeSigningEngine()
        val coordinator = OperationCoordinator(gatewayWalletConfiguration(maximumFeeRate = FeeRate.satPerVbyte("1")),
            backend, journal, EpochSecondsSource { 9_000L })
        assertEquals(CreateUtxosResult(TEST_TXID, 2), coordinator.createUtxos(context, signer, request))
        assertEquals(CreateUtxosResult(TEST_TXID, 2), coordinator.createUtxos(context, signer, request))
        assertEquals(listOf("GET", "GET"), transport.requests.map { it.method })
        assertEquals(0, signer.signCalls)
        assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
    }

    @Test
    fun `create malformed or unresolved status remains submitting with original evidence`(): Unit = runBlocking {
        val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val valid = createStatusJson(request)
        for (body in listOf(createStatusJson(request, "pending", null), createStatusJson(request, "expired", null),
            valid.replace(TEST_TXID.hex, "f".repeat(64)), valid.replace("create_utxos", "send_btc"),
            valid.replace("\"size\":1000", "\"size\":1001"),
            valid.replace("\"mayHaveBroadcast\":false", "\"mayHaveBroadcast\":true"))) {
            val checkpoint = createCheckpoint(request)
            val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.OperationAmbiguous> {
                recoveryCoordinator(backend, journal).createUtxos(context, FakeSigningEngine(), request)
            }
            assertEquals(checkpoint, journal.load(TEST_WALLET_ID, request.idempotencyKey))
            assertTrue(transport.requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `create completion ambiguous response reconciles once then fresh disabled adapter recovers`(): Unit = runBlocking {
        val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val signed = createCheckpoint(request, OperationPhase.SIGNED)
        val failures = listOf(queuedResponse(200, "{\"txid\":null,\"utxosCreated\":2}"),
            queuedResponse(200, "{\"txid\":\"${TEST_TXID.hex.uppercase()}\",\"utxosCreated\":2}"),
            queuedResponse(200, "{\"txid\":\"${TEST_TXID.hex}\",\"utxosCreated\":5}"),
            queuedResponse(410, "{\"error\":{\"code\":\"OP_EXPIRED\"}}"),
            queuedResponse(502, "{\"error\":{\"code\":\"COMPLETE_AMBIGUOUS\"}}"))
        for (failure in failures) {
            val journal = InMemoryOperationJournal().also { it.save(signed) }
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), failure,
                queuedResponse(200, createStatusJson(request, "expired", null)))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            val signer = FakeSigningEngine(testCapabilities(setOf(OperationKind.CREATE_UTXOS))).apply {
                retainedValidationReport = signed.signedPayload!!.validation
            }
            val coordinator = OperationCoordinator(gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW })
            assertFailsWith<SdkException.OperationAmbiguous> { coordinator.createUtxos(context, signer, request) }
            assertEquals(signed.copy(phase = OperationPhase.SUBMITTING), journal.load(TEST_WALLET_ID, request.idempotencyKey))
            assertEquals(listOf("GET", "POST", "GET"), transport.requests.map { it.method })
            assertEquals("/v1/onchain/create-utxos/complete", transport.requests[1].url.encodedPath)
            val restartedTransport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, createStatusJson(request)))
            val restarted = testBackend(restartedTransport, allowUnverifiedPsbtProfile = false)
            assertEquals(CreateUtxosResult(TEST_TXID, 2), recoveryCoordinator(restarted, journal).createUtxos(
                initializedContext(restarted), FakeSigningEngine(), request))
            assertEquals(0, signer.signCalls)
            assertTrue(restartedTransport.requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `invalid preparation clocks fail before HTTP and a backwards response clock fails closed`(): Unit = runBlocking {
        for (clock in listOf(0L, -1L, Long.MAX_VALUE)) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON))
            val backend = testBackend(transport, clock = { clock })
            val context = initializedContext(backend)
            assertFailsWith<SdkException.InternalInvariantViolation> {
                backend.prepareSendBtc(PrepareSendBtcRequest(operationContext(context), sendBtcRequest()))
            }
            assertFailsWith<SdkException.InternalInvariantViolation> {
                backend.prepareCreateUtxos(PrepareCreateUtxosRequest(operationContext(context), CreateUtxosRequest()))
            }
            assertEquals(1, transport.requests.size)
        }
        var calls = 0
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, preparedSendBtcJson(TEST_OPERATION_ID, 2_000_123)))
        val backend = testBackend(transport, clock = { if (calls++ == 0) 1_500_000L else 1_499_000L })
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.prepareSendBtc(PrepareSendBtcRequest(operationContext(initializedContext(backend)), sendBtcRequest()))
        }
    }

    @Test
    fun `session shutdown while credential retrieval suspends prevents business HTTP`(): Unit = runBlocking {
        val suspended = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var suspendCredentials = false
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON))
        val backend = testBackend(transport)
        backend.configureCredentials(WalletAPIKeyProvider {
            if (suspendCredentials) { suspended.complete(Unit); release.await() }
            WalletAPIKey.fromCharacters("credential".toCharArray())
        })
        val context = initializedContext(backend)
        suspendCredentials = true
        val pending = launch {
            assertFailsWith<SdkException.InvalidState> { backend.getBtcBalance(context) }
        }
        suspended.await()
        backend.shutdown(context.session)
        release.complete(Unit)
        pending.join()
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `session shutdown or cancellation during plan extraction prevents a usable preparation`(): Unit = runBlocking {
        for (cancel in listOf(false, true)) {
            val extracting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = CreateUtxosRequest(idempotencyKey = TEST_IDEMPOTENCY_KEY)
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, preparedCreateJson(request)))
            val backend = testBackend(transport, createPlanExtractor = { _, _ ->
                extracting.complete(Unit); release.await(); createPlan()
            })
            val context = initializedContext(backend)
            var returned = false
            val pending = launch {
                if (cancel) {
                    backend.prepareCreateUtxos(PrepareCreateUtxosRequest(operationContext(context), request))
                } else {
                    assertFailsWith<SdkException.InvalidState> {
                        backend.prepareCreateUtxos(PrepareCreateUtxosRequest(operationContext(context), request))
                    }
                }
                returned = cancel
            }
            extracting.await()
            if (cancel) pending.cancelAndJoin() else backend.shutdown(context.session)
            release.complete(Unit)
            pending.join()
            assertFalse(returned)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun `remaining unsupported business methods and ordinary send gate refuse without business HTTP`(): Unit = runBlocking {
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON),
            queuedResponse(201, "{\"fingerprint\":\"deadbeef\",\"address\":\"fixture-address\"}"))
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val signer = FakeSigningEngine(capabilities = testCapabilities(rgbInvoiceParsing = true))
        val engine = object : LocalSigningEngine by signer {
            override suspend fun identity(): WalletIdentity = gatewayIdentity()
        }
        val journal = InMemoryOperationJournal()
        val wallet = UTEXOWallet(gatewayWalletConfiguration(), backend,
            WalletKeyProvider { SecretSeed.fromBytes(ByteArray(64) { 1 }) }, journal,
            LocalSigningEngineFactory { _, _ -> engine }, EpochSecondsSource { TEST_NOW })
        wallet.init(); wallet.unlock()
        fun method(name: String, action: suspend () -> Unit) = name to action
        val asset = AssetId.of("fixture-asset")
        val unavailable = listOf(
            method("createUtxos") { wallet.createUtxos() },
            method("issueAssetNia") { wallet.issueAssetNia(IssueAssetNiaRequest("T", "Fixture", listOf(AssetAmount.of(1)), 0)) },
            method("issueAssetIfa") { wallet.issueAssetIfa(IssueAssetIfaRequest("T", "Fixture", 0, listOf(AssetAmount.of(1)), listOf(AssetAmount.of(1)))) },
            method("onchainSend") { wallet.onchainSend(OnchainSendRequest(RgbInvoice.of("rgb:fixture"), AssetAmount.of(1))) },
            method("inflate") { wallet.inflate(InflateRequest(asset, listOf(AssetAmount.of(1)))) },
            method("listTransactions") { wallet.listTransactions() },
            method("listTransactionsByTxid") { wallet.listTransactionsByTxid(TEST_TXID) },
            method("failTransfers") { wallet.failTransfers(FailTransfersRequest()) },
        )
        try {
            assertEquals(8, unavailable.size)
            for ((name, call) in unavailable) {
                assertFailsWith<SdkException.Unsupported>(name) { call() }
                assertEquals(2, transport.requests.size, "$name must not issue business HTTP")
            }
            assertFailsWith<SdkException.Unsupported> { wallet.sendBtc(sendBtcRequest()) }
            assertEquals(null, journal.load(TEST_WALLET_ID, TEST_IDEMPOTENCY_KEY))
            assertEquals(2, transport.requests.size)
            assertEquals(0, signer.signCalls)
            assertEquals(0, signer.addressChecks)
        } finally { wallet.destroy() }
    }

    @Test
    fun `receive maps exact caller intent optional metadata and explicit default duration`(): Unit = runBlocking {
        for (witness in listOf(true, false)) {
            val mode = if (witness) "witness" else "blind"
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201,
                "{\"invoice\":\"rgb:fixture\",\"recipientId\":\"recipient-fixture\",\"mode\":\"$mode\",\"expirationTimestamp\":87900}"))
            val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
            val context = initializedContext(backend)
            val received = backend.onchainReceive(context, OnchainReceiveRequest(witness = witness))
            assertEquals(null, received.batchTransferIdx)
            assertEquals(UnixSeconds.of(87_900), received.expirationTimestamp)
            val sent = transport.requests.last()
            assertEquals("/v1/wallet/receive", sent.url.encodedPath)
            assertEquals(null, sent.header("Idempotency-Key"))
            val fields = Json.parseToJsonElement(Buffer().also { sent.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals(Json.parseToJsonElement("{\"mode\":\"$mode\",\"durationSeconds\":86400,\"minConfirmations\":0}"), fields)
        }
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201,
            "{\"invoice\":\"rgb:fixture\",\"recipientId\":\"recipient-fixture\",\"mode\":\"witness\",\"expirationTimestamp\":5100,\"batchTransferIdx\":17}"))
        val backend = testBackend(transport)
        val received = backend.onchainReceive(initializedContext(backend), OnchainReceiveRequest(
            amount = AssetAmount.parse("9007199254740991"), assetId = AssetId.of("asset-id"),
            duration = DurationSeconds.of(3600), minConfirmations = BlockCount.of(100),
        ))
        assertEquals(17, received.batchTransferIdx)
        val fields = Json.parseToJsonElement(Buffer().also { transport.requests.last().body!!.writeTo(it) }.readUtf8())
        assertEquals(Json.parseToJsonElement("{\"mode\":\"witness\",\"amount\":9007199254740991,\"assetId\":\"asset-id\",\"durationSeconds\":3600,\"minConfirmations\":100}"), fields)
    }

    @Test
    fun `receive rejects unrepresentable parameters before creating remote receive state`(): Unit = runBlocking {
        val invalid = listOf(
            OnchainReceiveRequest(amount = AssetAmount.parse("9007199254740992")),
            OnchainReceiveRequest(amount = AssetAmount.parse(AssetAmount.MAX_VALUE)),
            OnchainReceiveRequest(duration = DurationSeconds.of(59)),
            OnchainReceiveRequest(duration = DurationSeconds.of(2_592_001)),
            OnchainReceiveRequest(minConfirmations = BlockCount.of(101)),
        )
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON))
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        invalid.forEach { assertFailsWith<SdkException.Unsupported> { backend.onchainReceive(context, it) } }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `receive rejects mode expiry numeric and missing-field inconsistencies without retrying`(): Unit = runBlocking {
        val valid = "{\"invoice\":\"rgb:fixture\",\"recipientId\":\"recipient-fixture\",\"mode\":\"witness\",\"expirationTimestamp\":87900}"
        val invalid = listOf(
            // Expected expiry is 87900; the clock-skew allowance is 300 seconds either way.
            valid.replace("witness", "blind"), valid.replace("87900", "88201"), valid.replace("87900", "87599"),
            valid.replace("87900", "87900.0"), valid.replace("87900", "\"87900\""),
            valid.replace(",\"expirationTimestamp\":87900", ""),
            valid.replace("\"recipientId\":\"recipient-fixture\",", ""),
            valid.dropLast(1) + ",\"batchTransferIdx\":-1}",
            valid.dropLast(1) + ",\"batchTransferIdx\":9007199254740992}",
        )
        for (body in invalid) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, body))
            val backend = testBackend(transport)
            assertFailsWith<SdkException.BackendProtocolMismatch> {
                backend.onchainReceive(initializedContext(backend), OnchainReceiveRequest())
            }
            assertEquals(2, transport.requests.size)
        }
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201, valid.replace("87900", "null")))
        val backend = testBackend(transport)
        assertEquals(null, backend.onchainReceive(initializedContext(backend), OnchainReceiveRequest()).expirationTimestamp)
    }

    @Test
    fun `receive tolerates bounded gateway clock skew in either direction`(): Unit = runBlocking {
        // A gateway clock 2s ahead (as observed on an emulator) and the edges of the allowance.
        for (expiry in listOf(87_902L, 87_600L, 88_200L)) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201,
                "{\"invoice\":\"rgb:fixture\",\"recipientId\":\"recipient-fixture\",\"mode\":\"witness\",\"expirationTimestamp\":$expiry}"))
            val backend = testBackend(transport)
            assertEquals(UnixSeconds.of(expiry),
                backend.onchainReceive(initializedContext(backend), OnchainReceiveRequest()).expirationTimestamp)
        }
    }

    @Test
    fun `receive accepts only bounded completion-time expiry and rejects clocks before HTTP or after backwards jump`(): Unit = runBlocking {
        for (finish in listOf(1_502_000L, 1_499_000L)) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(201,
                "{\"invoice\":\"rgb:fixture\",\"recipientId\":\"recipient-fixture\",\"mode\":\"witness\",\"expirationTimestamp\":87901}"))
            var calls = 0
            val backend = testBackend(transport, clock = { if (calls++ == 0) 1_500_000L else finish })
            val context = initializedContext(backend)
            if (finish > 1_500_000L) assertEquals(UnixSeconds.of(87_901), backend.onchainReceive(context, OnchainReceiveRequest()).expirationTimestamp)
            else assertFailsWith<SdkException.BackendProtocolMismatch> { backend.onchainReceive(context, OnchainReceiveRequest()) }
        }
        for (invalid in listOf(-1L, Long.MAX_VALUE)) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON))
            val backend = testBackend(transport, clock = { invalid })
            val context = initializedContext(backend)
            assertFailsWith<SdkException.InternalInvariantViolation> { backend.onchainReceive(context, OnchainReceiveRequest()) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `hostile JSON is bounded on both success and error paths including additive fields`(): Unit = runBlocking {
        val hostile = "{\"userId\":\"audit-user\",\"createdAt\":1,\"extra\":" +
            "[".repeat(100_000) + "0" + "]".repeat(100_000) + "}"
        assertTrue(hostile.length < 1_500_000)
        for (status in listOf(200, 400, 401, 503)) {
            val transport = QueueInterceptor(queuedResponse(status, hostile))
            val backend = testBackend(transport)
            assertFailsWith<SdkException> { backend.initialize(initializeRequest()) }
            assertEquals(1, transport.requests.size)
        }
        for (depth in listOf(32, 63, 64)) {
            val body = TEST_ME_JSON.dropLast(1) + ",\"extra\":" +
                "[".repeat(depth) + "0" + "]".repeat(depth) + "}"
            val transport = QueueInterceptor(queuedResponse(200, body))
            val backend = testBackend(transport)
            if (depth < 64) initializedContext(backend)
            else assertFailsWith<SdkException.BackendProtocolMismatch> { initializedContext(backend) }
        }
    }

    @Test
    fun `sync and refresh await exact successful response with client completion time and no tip claim`(): Unit = runBlocking {
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, "{\"status\":\"ok\"}"), queuedResponse(200, "{\"status\":\"ok\"}"))
        var now = 1_500_999L
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false, clock = { now })
        val context = initializedContext(backend)
        assertEquals(SyncResult(UnixSeconds.of(1_500)), backend.syncWallet(context))
        now = 1_501_001L
        assertEquals(SyncResult(UnixSeconds.of(1_501)), backend.refreshWallet(context))
        assertEquals(listOf("GET", "POST", "POST"), transport.requests.map { it.method })
        transport.requests.drop(1).forEach {
            assertEquals("/v1/wallet/sync", it.url.encodedPath)
            assertEquals("Bearer $TEST_TOKEN", it.header("Authorization"))
            assertEquals(null, it.header("Idempotency-Key"))
        }
    }

    @Test
    fun `sync fails closed for wrong session response and invalid clock without retrying`(): Unit = runBlocking {
        for (body in listOf("{}", "{\"status\":\"pending\"}", "{\"status\":true}", "{\"status\":null}")) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            assertFailsWith<SdkException.BackendProtocolMismatch> { backend.syncWallet(context) }
            assertEquals(2, transport.requests.size)
        }
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, "{\"status\":\"ok\"}"))
        val backend = testBackend(transport, clock = { -1L })
        val context = initializedContext(backend)
        assertFailsWith<SdkException.InvalidState> { backend.syncWallet(inactiveContext()) }
        assertEquals(1, transport.requests.size)
        assertFailsWith<SdkException.InternalInvariantViolation> { backend.syncWallet(context) }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `sync transport error is propagated without automatic retry`(): Unit = runBlocking {
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(503, "{\"error\":{\"code\":\"UNAVAILABLE\"}}"))
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        assertFailsWith<SdkException.BackendUnavailable> { backend.refreshWallet(context) }
        assertEquals(2, transport.requests.size)
    }
    @Test
    fun `rotating provider loads request scoped credentials and verifies unchanged account`(): Unit = runBlocking {
        val me = "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"
        val other = "{\"userId\":\"u_other\",\"createdAt\":1234}"
        val transport = QueueInterceptor(
            queuedResponse(200, me), queuedResponse(200, me), queuedResponse(200, other), queuedResponse(200, other),
        )
        val backend = testBackend(transport)
        var token = "credential-one"
        val issued = mutableListOf<WalletAPIKey>()
        backend.configureCredentials(WalletAPIKeyProvider {
            WalletAPIKey.fromCharacters(token.toCharArray()).also(issued::add)
        })
        val context = initializedContext(backend)
        // The first guarded request binds its exact credential. A deliberately malformed payload
        // stops the balance path after identity verification and one read-only balance request.
        assertFailsWith<SdkException.BackendProtocolMismatch> { backend.getBtcBalance(context) }
        assertEquals("Bearer credential-one", transport.requests.last().header("Authorization"))
        token = "credential-two"
        assertFailsWith<SdkException.BackendProtocolMismatch> { backend.getBtcBalance(context) }
        assertEquals("/v1/me", transport.requests.last().url.encodedPath)
        assertEquals("Bearer credential-two", transport.requests.last().header("Authorization"))
        issued.forEach { key -> assertFailsWith<SdkException.InvalidState> { key.authorizationValue() } }
    }

    @Test
    fun `provider failures are redacted and fail before HTTP`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport)
        backend.configureCredentials(WalletAPIKeyProvider { throw IllegalStateException("secret-token") })
        val error = assertFailsWith<SdkException.KeyAccessFailed> { backend.initialize(initializeRequest()) }
        assertFalse(error.message.orEmpty().contains("secret-token"))
        assertEquals(null, error.cause)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `configuration requires TLS except explicit local development and redacts token`(): Unit {
        assertFailsWith<SdkException.InvalidArgument> {
            gatewayConfiguration(baseUrl = "http://gateway.example")
        }
        val local = gatewayConfiguration(
            baseUrl = "http://127.0.0.1:8480",
            allowInsecureLocalDevelopment = true,
        )
        assertEquals("http://127.0.0.1:8480", local.baseUrl)
        assertFalse(local.allowUnverifiedPsbtProfile)
        assertFalse(local.toString().contains(TEST_TOKEN))
        assertTrue(local.toString().contains("[REDACTED]"))
        assertFailsWith<SdkException.Unsupported> {
            RgbLightningNodeGatewayConfiguration(
                baseUrl = "https://gateway.example",
                expectedNetwork = Network.UTEXO,
                bearerToken = TEST_TOKEN,
            )
        }
    }

    @Test
    fun `unverified PSBT profile is default off before journal and transport`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(
            interceptor = transport,
            allowUnverifiedPsbtProfile = false,
        )
        val journal = InMemoryOperationJournal()
        val context = BackendContext(
            session = BackendSession("inactive-session", 1, Network.SIGNET),
            walletId = TEST_WALLET_ID,
            network = Network.SIGNET,
        )
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        assertFailsWith<SdkException.PolicyRejected> {
            coordinator.sendBtc(
                context,
                FakeSigningEngine(),
                sendBtcRequest().copy(feeRate = FeeRate.satPerVbyte(101.0)),
            )
        }
        val error = assertFailsWith<SdkException.Unsupported> {
            coordinator.sendBtc(context, FakeSigningEngine(), sendBtcRequest())
        }
        assertEquals(
            "sendBtc requires an explicitly enabled, unverified gateway PSBT profile",
            error.details["feature"],
        )
        assertEquals(null, journal.load(TEST_WALLET_ID, TEST_IDEMPOTENCY_KEY))
        assertTrue(transport.requests.isEmpty())

        assertFailsWith<SdkException.Unsupported> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(operationContext(context), sendBtcRequest()),
            )
        }
        assertFailsWith<SdkException.Unsupported> {
            backend.submitSignedTransaction(
                signedSubmission(
                    context,
                    OperationId.of("123e4567-e89b-42d3-a456-426614174000"),
                    TEST_PSBT_BASE64,
                ),
            )
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `disabled profile leaves recovered signed checkpoint unchanged`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest().copy(
            idempotencyKey = IdempotencyKey.of("signed-profile-off"),
        )
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SIGNED)
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        assertFailsWith<SdkException.Unsupported> {
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request)
        }
        assertEquals(
            OperationPhase.SIGNED,
            journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `disabled profile still replays a terminal send checkpoint`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest()
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.TERMINAL)
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(
                maximumFeeRate = FeeRate.satPerVbyte(1.0),
            ),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        assertEquals(
            TEST_TXID,
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request),
        )
        assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `expired signed checkpoint terminalizes before disabled-profile gate`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest().copy(
            idempotencyKey = IdempotencyKey.of("expired-signed-profile-off"),
        )
        val checkpoint = persistedSendCheckpoint(
            request = request,
            phase = OperationPhase.SIGNED,
            expiresAt = UnixSeconds.of(TEST_NOW),
        )
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(
                maximumFeeRate = FeeRate.satPerVbyte(1.0),
            ),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        val error = assertFailsWith<SdkException.OperationExpired> {
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request)
        }
        assertEquals(checkpoint.operationId, error.operationId)
        assertEquals(
            OperationPhase.TERMINAL,
            journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `disabled profile reconciles submitting without another write`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest().copy(
            idempotencyKey = IdempotencyKey.of("submitting-profile-off"),
        )
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(
                maximumFeeRate = FeeRate.satPerVbyte(1.0),
            ),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        val error = assertFailsWith<SdkException.OperationAmbiguous> {
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request)
        }
        assertEquals(checkpoint.operationId, error.operationId)
        assertEquals(
            OperationPhase.SUBMITTING,
            journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `completed BTC recovery checks durable local evidence with profile disabled and replays terminally`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, TEST_ME_JSON),
            queuedResponse(200, operationStatusJson()),
        )
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val context = initializedContext(backend)
        val request = sendBtcRequest()
        val journal = InMemoryOperationJournal()
        journal.save(persistedSendCheckpoint(request, OperationPhase.SUBMITTING))
        val coordinator = recoveryCoordinator(backend, journal)
        // No current signing preflight: old submissions remain recoverable after stricter policy.
        assertEquals(TEST_TXID, coordinator.sendBtc(context, FakeSigningEngine(), request))
        assertEquals(TEST_TXID, coordinator.sendBtc(context, FakeSigningEngine(), request))
        assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        assertEquals(listOf("/v1/me", "/v1/onchain/operations/$TEST_OPERATION_ID"), transport.requests.map { it.url.encodedPath })
        assertTrue(transport.requests.all { it.method == "GET" && it.header("Idempotency-Key") == null && it.body == null })
    }

    @Test
    fun `pending and expired status never prove no broadcast even without txid`(): Unit = runBlocking {
        for (state in listOf("pending", "expired")) {
            for (txid in listOf(null, TEST_TXID.hex)) {
                val response = operationStatusJson(state = state, txid = txid)
                val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, response))
                val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
                val context = initializedContext(backend)
                val request = sendBtcRequest()
                val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
                val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
                assertFailsWith<SdkException.OperationAmbiguous> {
                    recoveryCoordinator(backend, journal).sendBtc(context, FakeSigningEngine(), request)
                }
                assertEquals(checkpoint, journal.load(TEST_WALLET_ID, request.idempotencyKey))
                assertEquals(2, transport.requests.size)
                assertTrue(transport.requests.all { it.method == "GET" })
            }
        }
    }

    @Test
    fun `forged malformed and contradictory recovery status retains submitting checkpoint`(): Unit = runBlocking {
        val original = Json.parseToJsonElement(operationStatusJson()).jsonObject
        fun changed(field: String, value: kotlinx.serialization.json.JsonElement): String =
            JsonObject(original + (field to value)).toString()
        fun changedIntent(field: String, value: kotlinx.serialization.json.JsonElement): String =
            changed("intent", JsonObject(original.getValue("intent").jsonObject + (field to value)))
        val invalid = listOf(
            changed("opId", JsonPrimitive("223e4567-e89b-42d3-a456-426614174000")),
            changed("opId", JsonPrimitive(TEST_OPERATION_ID.uppercase())),
            changed("kind", JsonPrimitive("send_asset")),
            changed("kind", JsonPrimitive("create_utxos")),
            changed("state", JsonPrimitive("failed")),
            changed("state", JsonPrimitive(" completed")),
            changed("txid", JsonNull),
            changed("txid", JsonPrimitive("f".repeat(64))),
            changed("txid", JsonPrimitive(TEST_TXID.hex.uppercase())),
            changed("txid", JsonPrimitive("not-a-txid")),
            changed("mayHaveBroadcast", JsonPrimitive(true)),
            changed("mayHaveBroadcast", JsonPrimitive("false")),
            changed("mayHaveBroadcast", JsonPrimitive(0)),
            changed("createdAt", JsonPrimitive(-1)),
            changed("createdAt", JsonPrimitive(0)),
            changed("createdAt", JsonPrimitive(2_000_999)),
            changed("createdAt", JsonPrimitive("1000000")),
            changed("expiresAt", JsonPrimitive(2_001_000)),
            changed("expiresAt", JsonPrimitive(9_007_199_254_740_992L)),
            changed("expiresAt", JsonPrimitive(2_000.5)),
            changedIntent("kind", JsonPrimitive("send_asset")),
            changedIntent("feeRateSatPerVb", JsonPrimitive(3)),
            changedIntent("asset", JsonObject(emptyMap())),
            changedIntent("utxos", JsonObject(emptyMap())),
            operationStatusJson().replace("\"amountSat\":25000", "\"amountSat\":25001"),
            operationStatusJson().replace(TEST_GATEWAY_ADDRESS.value, "tb1potheraddress"),
            operationStatusJson().replace("512000", "51200G"),
            operationStatusJson().replace("512000", "51200"),
            operationStatusJson().replace("512000", ""),
            JsonObject(original - "mayHaveBroadcast").toString(),
            operationStatusJson("pending", TEST_TXID.hex).replace("\"mayHaveBroadcast\":true", "\"mayHaveBroadcast\":false"),
            operationStatusJson("expired", null).replace("\"mayHaveBroadcast\":false", "\"mayHaveBroadcast\":true"),
        )
        for (body in invalid) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, body))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            val request = sendBtcRequest()
            val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
            val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
            assertFailsWith<SdkException.OperationAmbiguous>(body) {
                recoveryCoordinator(backend, journal).sendBtc(context, FakeSigningEngine(), request)
            }
            assertEquals(checkpoint, journal.load(TEST_WALLET_ID, request.idempotencyKey))
            assertTrue(transport.requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `status transport failures never trigger another prepare sign or complete`(): Unit = runBlocking {
        val responses = listOf(
            queuedResponse(404, "{\"error\":{\"code\":\"OP_NOT_FOUND\",\"message\":\"secret\"}}"),
            queuedResponse(401, "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"secret\"}}"),
            queuedResponse(502, "{\"error\":{\"code\":\"COMPLETE_AMBIGUOUS\"}}"),
            queuedResponse(200, operationStatusJson(), mapOf("Content-Type" to "text/plain")),
            queuedResponse(201, operationStatusJson()),
            queuedResponse(302, operationStatusJson()),
            queuedResponse(200, "{}"),
            queuedResponse(200, "{malformed"),
        )
        for (response in responses) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), response)
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            val request = sendBtcRequest()
            val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
            val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
            val error = assertFailsWith<SdkException.OperationAmbiguous> {
                recoveryCoordinator(backend, journal).sendBtc(context, FakeSigningEngine(), request)
            }
            assertFalse(error.message.orEmpty().contains("secret"))
            assertEquals(checkpoint, journal.load(TEST_WALLET_ID, request.idempotencyKey))
            assertEquals(2, transport.requests.size)
            assertTrue(transport.requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `recovery rejects inconsistent local evidence before HTTP`(): Unit = runBlocking {
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON))
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val request = sendBtcRequest()
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.getOperationForRecovery(context, checkpoint, request.copy(amount = Satoshis.of(25_001)))
        }
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.getOperationForRecovery(context, checkpoint.copy(phase = OperationPhase.SIGNED), request)
        }
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.getOperationForRecovery(context, checkpoint.copy(walletId = WalletId.of("other-wallet")), request)
        }
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.getOperationForRecovery(context, checkpoint.copy(signedPayload = SignedOperationPayload(
                TEST_PSBT_BASE64, testValidation().copy(psbtSha256 = "f".repeat(64)),
            )), request)
        }
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.getOperationForRecovery(context, checkpoint.copy(signedPayload = SignedOperationPayload(
                "$TEST_PSBT_BASE64 ", checkpoint.signedPayload!!.validation,
            )), request)
        }
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.getOperationForRecovery(context, checkpoint.copy(
                preparedTransaction = checkpoint.preparedTransaction!!.let { prepared -> PreparedTransaction(
                    prepared.operationId, prepared.idempotencyKey, prepared.requestHash,
                    prepared.network, prepared.operation, "$TEST_PSBT_BASE64 ", prepared.expiresAt,
                ) },
            ), request)
        }
        assertFailsWith<SdkException.Unsupported> { backend.getOperation(context, checkpoint.operationId!!) }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `recovery rejects status lifetime exceeding local maximum even when expiry matches`(): Unit = runBlocking {
        val original = Json.parseToJsonElement(operationStatusJson()).jsonObject
        val status = JsonObject(original + mapOf(
            "createdAt" to JsonPrimitive(1L),
            "expiresAt" to JsonPrimitive(5_000_123L),
        ))
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, status.toString()))
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val request = sendBtcRequest()
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING, UnixSeconds.of(5_000L))
        val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
        assertFailsWith<SdkException.OperationAmbiguous> {
            recoveryCoordinator(backend, journal).sendBtc(context, FakeSigningEngine(), request)
        }
        assertEquals(checkpoint, journal.load(TEST_WALLET_ID, request.idempotencyKey))
    }

    @Test
    fun `canceled in flight recovery leaves submitting and fresh disabled adapter can recover`(): Unit = runBlocking {
        val statusStarted = CompletableDeferred<Unit>()
        val releaseStatus = CountDownLatch(1)
        val statusFinished = CountDownLatch(1)
        val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, operationStatusJson()))
        val backend = testBackend(Interceptor { chain ->
            if (chain.request().url.encodedPath.startsWith("/v1/onchain/operations/")) {
                statusStarted.complete(Unit)
                try {
                    check(releaseStatus.await(5, TimeUnit.SECONDS))
                    transport.intercept(chain)
                } finally {
                    statusFinished.countDown()
                }
            } else {
                transport.intercept(chain)
            }
        }, allowUnverifiedPsbtProfile = false)
        val context = initializedContext(backend)
        val request = sendBtcRequest()
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
        val journal = InMemoryOperationJournal().also { it.save(checkpoint) }
        val recovery = launch { recoveryCoordinator(backend, journal).sendBtc(context, FakeSigningEngine(), request) }
        try {
            statusStarted.await()
            recovery.cancelAndJoin()
            assertEquals(checkpoint, journal.load(TEST_WALLET_ID, request.idempotencyKey))
        } finally {
            releaseStatus.countDown()
            withContext(Dispatchers.IO) { assertTrue(statusFinished.await(5, TimeUnit.SECONDS)) }
        }
        val restartedTransport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, operationStatusJson()))
        val restarted = testBackend(restartedTransport, allowUnverifiedPsbtProfile = false)
        assertEquals(TEST_TXID, recoveryCoordinator(restarted, journal).sendBtc(
            initializedContext(restarted), FakeSigningEngine(), request,
        ))
        assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        assertTrue(restartedTransport.requests.all { it.method == "GET" })
    }

    @Test
    fun `ambiguous completion including expiry reconciles by GET only`(): Unit = runBlocking {
        val responses = listOf(
            queuedResponse(400, "not json"),
            queuedResponse(400, "{\"error\":{\"code\":\"UNREVIEWED_ERROR\"}}"),
            queuedResponse(401, "{\"error\":{\"code\":\"UNAUTHORIZED\"}}"),
            queuedResponse(403, "<html>intermediary rejected request</html>"),
            queuedResponse(404, "{\"error\":{\"code\":\"OP_NOT_FOUND\"}}"),
            queuedResponse(413, "[".repeat(100_000) + "0" + "]".repeat(100_000)),
            queuedResponse(410, "{\"error\":{\"code\":\"OP_EXPIRED\"}}"),
            queuedResponse(409, "{\"error\":{\"code\":\"OP_ALREADY_COMPLETED\"}}"),
            queuedResponse(409, "{\"error\":{\"code\":\"OP_KIND_MISMATCH\"}}"),
            queuedResponse(409, "{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}}"),
            queuedResponse(502, "{\"error\":{\"code\":\"COMPLETE_AMBIGUOUS\"}}"),
            queuedResponse(200, "{\"txid\":\"invalid\"}"),
        )
        for (response in responses) {
            val transport = QueueInterceptor(
                queuedResponse(200, TEST_ME_JSON), response, queuedResponse(200, operationStatusJson()),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            val request = sendBtcRequest()
            val journal = InMemoryOperationJournal().also {
                it.save(persistedSendCheckpoint(request, OperationPhase.SIGNED))
            }
            val coordinator = OperationCoordinator(
                gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW },
            )
            val signer = FakeSigningEngine().apply {
                retainedValidationReport = persistedSendCheckpoint(request, OperationPhase.SIGNED).signedPayload!!.validation
            }
            assertEquals(TEST_TXID, coordinator.sendBtc(context, signer, request))
            assertEquals(listOf("GET", "POST", "GET"), transport.requests.map { it.method })
            assertEquals("/v1/onchain/send-btc/complete", transport.requests[1].url.encodedPath)
            assertEquals("/v1/onchain/operations/$TEST_OPERATION_ID", transport.requests[2].url.encodedPath)
            assertEquals(null, transport.requests[2].header("Idempotency-Key"))
        }
    }

    @Test
    fun `completion conflicts reconcile once retain exact evidence and recover with fresh gated backend`(): Unit = runBlocking {
        for (code in listOf("OP_KIND_MISMATCH", "IDEMPOTENCY_KEY_REUSED")) {
            for (status in listOf(
                queuedResponse(200, operationStatusJson("pending", null)),
                queuedResponse(503, "{\"error\":{\"code\":\"UNAVAILABLE\"}}"),
                queuedResponse(200, operationStatusJson(txid = "f".repeat(64))),
            )) {
                val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON),
                    queuedResponse(409, "{\"error\":{\"code\":\"$code\",\"message\":\"secret-marker\"}}"), status)
                val backend = testBackend(transport)
                val context = initializedContext(backend)
                val request = sendBtcRequest()
                val signed = persistedSendCheckpoint(request, OperationPhase.SIGNED)
                val journal = InMemoryOperationJournal().also { it.save(signed) }
                val signer = FakeSigningEngine().apply { retainedValidationReport = signed.signedPayload!!.validation }
                val error = assertFailsWith<SdkException.OperationAmbiguous> {
                    OperationCoordinator(gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW })
                        .sendBtc(context, signer, request)
                }
                assertEquals(signed.operationId, error.operationId)
                assertTrue(error.retryable)
                assertFalse(error.message.orEmpty().contains("secret-marker"))
                assertEquals(signed.copy(phase = OperationPhase.SUBMITTING), journal.load(TEST_WALLET_ID, request.idempotencyKey))
                assertEquals(listOf("GET", "POST", "GET"), transport.requests.map { it.method })
                assertEquals("/v1/onchain/send-btc/complete", transport.requests[1].url.encodedPath)
                assertEquals("/v1/onchain/operations/$TEST_OPERATION_ID", transport.requests[2].url.encodedPath)
                assertEquals(0, signer.signCalls)

                val restartedTransport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, operationStatusJson()))
                val restarted = testBackend(restartedTransport, allowUnverifiedPsbtProfile = false)
                val restartedContext = initializedContext(restarted)
                val restartedSigner = FakeSigningEngine()
                val coordinator = recoveryCoordinator(restarted, journal)
                assertEquals(TEST_TXID, coordinator.sendBtc(restartedContext, restartedSigner, request))
                assertEquals(TEST_TXID, coordinator.sendBtc(restartedContext, restartedSigner, request))
                assertEquals(listOf("GET", "GET"), restartedTransport.requests.map { it.method })
                assertEquals(0, restartedSigner.signCalls)
                assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
            }
        }
    }

    @Test
    fun `expiry completion followed by expired null status stays ambiguous durably`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, TEST_ME_JSON),
            queuedResponse(410, "{\"error\":{\"code\":\"OP_EXPIRED\"}}"),
            queuedResponse(200, operationStatusJson("expired", null)),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val request = sendBtcRequest()
        val journal = InMemoryOperationJournal().also { it.save(persistedSendCheckpoint(request, OperationPhase.SIGNED)) }
        assertFailsWith<SdkException.OperationAmbiguous> {
            OperationCoordinator(gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW })
                .sendBtc(context, FakeSigningEngine().apply {
                    retainedValidationReport = persistedSendCheckpoint(request, OperationPhase.SIGNED).signedPayload!!.validation
                }, request)
        }
        assertEquals(OperationPhase.SUBMITTING, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        assertEquals(listOf("GET", "POST", "GET"), transport.requests.map { it.method })
    }

    @Test
    fun `unknown completion 4xx retains submitting across restart and recovers without a second POST`(): Unit = runBlocking {
        for (response in listOf(queuedResponse(400, "<html>bad request</html>"),
            queuedResponse(401, "{\"error\":{\"code\":\"UNAUTHORIZED\"}}"),
            queuedResponse(422, "{\"error\":{\"code\":\"UNKNOWN_CODE\"}}"))) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), response,
                queuedResponse(200, operationStatusJson("pending", null)))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            val request = sendBtcRequest()
            val journal = InMemoryOperationJournal().also { it.save(persistedSendCheckpoint(request, OperationPhase.SIGNED)) }
            val signer = FakeSigningEngine().apply {
                retainedValidationReport = persistedSendCheckpoint(request, OperationPhase.SIGNED).signedPayload!!.validation
            }
            assertFailsWith<SdkException.OperationAmbiguous> {
                OperationCoordinator(gatewayWalletConfiguration(), backend, journal, EpochSecondsSource { TEST_NOW })
                    .sendBtc(context, signer, request)
            }
            assertEquals(OperationPhase.SUBMITTING, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
            assertEquals(null, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.terminalOutcome)
            assertEquals(listOf("GET", "POST", "GET"), transport.requests.map { it.method })
            val restartedTransport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON), queuedResponse(200, operationStatusJson()))
            val restarted = testBackend(restartedTransport, allowUnverifiedPsbtProfile = false)
            assertEquals(TEST_TXID, recoveryCoordinator(restarted, journal).sendBtc(
                initializedContext(restarted), FakeSigningEngine(), request))
            assertTrue(restartedTransport.requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `production backend exposes only its policy-enforcing constructor`(): Unit {
        val constructors = RgbLightningNodeGatewayBackend::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) }
        assertEquals(1, constructors.size)
        assertEquals(
            listOf(RgbLightningNodeGatewayConfiguration::class.java),
            constructors.single().parameterTypes.toList(),
        )
    }

    @Test
    fun `initialization authenticates and registration sends watch-only keys`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(
                200,
                "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234," +
                    "\"futureMeField\":{\"ignored\":true}}",
            ),
            queuedResponse(
                201,
                "{\"fingerprint\":\"deadbeef\",\"address\":\"tb1pregisteredaddress\"," +
                    "\"futureRegistrationField\":42}",
            ),
        )
        val backend = testBackend(transport)
        val session = backend.initialize(initializeRequest())
        val context = BackendContext(session, TEST_WALLET_ID, Network.SIGNET)
        backend.registerWallet(RegisterWalletRequest(context, gatewayIdentity()))

        assertEquals(2, transport.requests.size)
        assertEquals("/v1/me", transport.requests[0].url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", transport.requests[0].header("Authorization"))
        val registration = transport.requests[1]
        assertEquals("/v1/wallet/xpubs", registration.url.encodedPath)
        val body = registration.bodyUtf8()
        assertTrue(body.contains("\"vanilla\":\"${"t".repeat(111)}\""))
        assertTrue(body.contains("\"colored\":\"${"u".repeat(111)}\""))
        assertFalse(body.contains("mnemonic", ignoreCase = true))
        assertFalse(body.contains("xpriv", ignoreCase = true))
    }

    @Test
    fun `balances map exact SDK semantics and tolerate additive gateway fields`(): Unit = runBlocking {
        val balanceBody = """
            {
              "btc": {
                "vanilla": {"settled":1000,"future":1200,"spendable":900,"reserved":5},
                "colored": {"settled":2000,"future":2200,"spendable":1800,"reserved":7},
                "futureBtcField": true
              },
              "assets": [{
                "assetId":"rgb:asset-1",
                "schema":"NIA",
                "ticker":"TOK",
                "name":"Token",
                "precision":2,
                "balance":{"settled":10,"future":12,"spendable":8,"reserved":1},
                "futureAssetField":"ignored"
              }],
              "futureRootField":{"ignored":true}
            }
        """.trimIndent()
        val transport = QueueInterceptor(
            queuedResponse(
                200,
                "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234," +
                    "\"futureMeField\":true}",
            ),
            queuedResponse(200, balanceBody),
            queuedResponse(200, balanceBody),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)

        val btc = backend.getBtcBalance(context)
        assertEquals(900L, btc.vanilla.spendable.value)
        assertEquals(1_800L, btc.colored.spendable.value)
        val asset = backend.getAssetBalance(context, AssetId.of("rgb:asset-1"))
        assertEquals("10", asset.settled?.decimal)
        assertEquals("8", asset.spendable?.decimal)
    }

    @Test
    fun `send BTC validates intent and uses distinct deterministic stage keys`(): Unit = runBlocking {
        val operationId = "123e4567-e89b-42d3-a456-426614174000"
        val preparedPsbt = "cHNidP8BAHECAAAAAAAAAAAA"
        val scriptHex = "5120${"00".repeat(32)}"
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                201,
                """
                    {
                      "opId":"$operationId",
                      "psbt":"$preparedPsbt",
                      "intent":{
                        "kind":"send_btc",
                        "feeRateSatPerVb":2,
                        "recipients":[{
                          "address":"${TEST_GATEWAY_ADDRESS.value}",
                          "scriptHex":"$scriptHex",
                          "amountSat":25000
                        }],
                        "asset":null,
                        "utxos":null
                      },
                      "expiresAt":2000123
                    }
                """.trimIndent(),
            ),
            queuedResponse(
                200,
                "{\"txid\":\"${TEST_TXID.hex}\",\"futureCompleteField\":true}",
            ),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )
        val send = SendBtcRequest(
            address = TEST_GATEWAY_ADDRESS,
            amount = Satoshis.of(25_000),
            feeRate = FeeRate.satPerVbyte(2.0),
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
        )

        val prepared = backend.prepareSendBtc(PrepareSendBtcRequest(operationContext, send))
        assertEquals(OperationKind.SEND_BTC, prepared.operation)
        assertEquals(2_000L, prepared.expiresAt.value)
        assertEquals(operationContext.requestHash, prepared.requestHash)

        val result = backend.submitSignedTransaction(
            SignedTransactionSubmission(
                context = operationContext,
                operationId = prepared.operationId,
                signedPsbtBase64 = preparedPsbt,
                validation = testValidation(),
            ),
        )
        assertEquals(TEST_TXID, (result as BackendOperationResult.SendBtc).txid)

        val prepareRequest = transport.requests[1]
        val completeRequest = transport.requests[2]
        val prepareKey = prepareRequest.header("Idempotency-Key")
        val completeKey = completeRequest.header("Idempotency-Key")
        assertEquals(
            "4958bc464e91725845ab2c0403a94809b6628f2fcec1cba0ee8ac8ad96088223",
            prepareKey,
        )
        assertEquals(
            "438d6a09d58b7f501b78ffca3f93dfd701a1b54a0e70038fddc923e984d96ff9",
            completeKey,
        )
        assertNotEquals(prepareKey, completeKey)
        assertEquals("/v1/onchain/send-btc/prepare", prepareRequest.url.encodedPath)
        assertEquals("/v1/onchain/send-btc/complete", completeRequest.url.encodedPath)
    }

    @Test
    fun `fractional gateway fee fails before any prepare request`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )

        assertFailsWith<SdkException.InvalidArgument> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(
                    operationContext,
                    SendBtcRequest(
                        TEST_GATEWAY_ADDRESS,
                        Satoshis.of(25_000),
                        feeRate = FeeRate.satPerVbyte("1.5"),
                        idempotencyKey = TEST_IDEMPOTENCY_KEY,
                    ),
                ),
            )
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `skip sync send fails before any prepare request`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = operationContext(context)

        val error = assertFailsWith<SdkException.Unsupported> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(
                    operationContext,
                    sendBtcRequest(skipSync = true),
                ),
            )
        }

        assertEquals("PR #174 gateway sendBtc skipSync", error.details["feature"])
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `missing authoritative gateway contracts remain unsupported without HTTP fallback`(): Unit =
        runBlocking {
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            assertFailsWith<SdkException.Unsupported> {
                backend.getOperation(
                    context,
                    OperationId.of("123e4567-e89b-42d3-a456-426614174000"),
                )
            }
            assertFailsWith<SdkException.Unsupported> { backend.listTransactions(context) }
            assertFailsWith<SdkException.Unsupported> { backend.failTransfers(context, FailTransfersRequest()) }
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `gateway idempotency reuse reports conflict for the public logical key`(): Unit =
        runBlocking {
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
                queuedResponse(
                    409,
                    "{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REUSED\"," +
                        "\"message\":\"do-not-expose\"}}",
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            val error = assertFailsWith<SdkException.OperationConflict> {
                backend.prepareSendBtc(
                    PrepareSendBtcRequest(operationContext(context), sendBtcRequest()),
                )
            }

            assertEquals(TEST_IDEMPOTENCY_KEY.value, error.details["idempotencyKey"])
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun `transient HTTP statuses are retryable backend unavailable errors`(): Unit = runBlocking {
        listOf(
            408 to "TRANSIENT_FAILURE",
            425 to "TRANSIENT_FAILURE",
            429 to "TRANSIENT_FAILURE",
            500 to "TRANSIENT_FAILURE",
            503 to "TRANSIENT_FAILURE",
            409 to "IDEMPOTENCY_IN_FLIGHT",
        ).forEach { (statusCode, backendCode) ->
            val correlationId = "request-$statusCode"
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
                queuedResponse(
                    statusCode,
                    "{\"error\":{\"code\":\"$backendCode\"," +
                        "\"message\":\"do-not-expose\"}}",
                    headers = mapOf("x-request-id" to correlationId),
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            val error = assertFailsWith<SdkException.BackendUnavailable> {
                backend.getBtcBalance(context)
            }

            assertTrue(error.retryable)
            assertEquals(correlationId, error.correlationId)
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
        }
    }

    @Test
    fun `ambiguous completion response stays unavailable for coordinator reconciliation`(): Unit =
        runBlocking {
            val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
                queuedResponse(
                    502,
                    "{\"error\":{\"code\":\"COMPLETE_AMBIGUOUS\"," +
                        "\"message\":\"do-not-expose\"}}",
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            val error = assertFailsWith<SdkException.BackendUnavailable> {
                backend.submitSignedTransaction(
                    signedSubmission(context, operationId, TEST_PSBT_BASE64),
                )
            }

            assertTrue(error.retryable)
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
        }

    @Test
    fun `prepare operation kind mismatch remains a protocol failure without submitted identity`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                409,
                "{\"error\":{\"code\":\"OP_KIND_MISMATCH\",\"message\":\"do-not-expose\"}}",
                headers = mapOf("x-request-id" to "request-kind-mismatch"),
            ),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)

        val error = assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.prepareSendBtc(PrepareSendBtcRequest(operationContext(context), sendBtcRequest()))
        }

        assertEquals(null, error.operationId)
        assertEquals("request-kind-mismatch", error.correlationId)
        assertFalse(error.message.orEmpty().contains("do-not-expose"))
        assertEquals(listOf("GET", "POST"), transport.requests.map { it.method })
    }

    @Test
    fun `direct completion conflicts carry operation identity and never report prepare conflict`(): Unit = runBlocking {
        val operationId = OperationId.of(TEST_OPERATION_ID)
        for (code in listOf("OP_KIND_MISMATCH", "IDEMPOTENCY_KEY_REUSED")) {
            val transport = QueueInterceptor(queuedResponse(200, TEST_ME_JSON),
                queuedResponse(409, "{\"error\":{\"code\":\"$code\",\"message\":\"do-not-expose\"}}"))
            val backend = testBackend(transport)
            val context = initializedContext(backend)
            val error = assertFailsWith<SdkException.OperationAmbiguous> {
                backend.submitSignedTransaction(signedSubmission(context, operationId, TEST_PSBT_BASE64))
            }
            assertEquals(operationId, error.operationId)
            assertTrue(error.retryable)
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun `prepared and submitted PSBTs must use canonical Base64`(): Unit = runBlocking {
        val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
        val unpaddedBase64 = "AAAAAAAAAAAAAAAAAAAAAA"
        val prepareTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                201,
                preparedSendBtcJson(
                    operationId = operationId.value,
                    expiresAtMillis = 2_000_123L,
                    psbtBase64 = unpaddedBase64,
                ),
            ),
        )
        val prepareBackend = testBackend(prepareTransport)
        val prepareContext = initializedContext(prepareBackend)

        assertFailsWith<SdkException.BackendProtocolMismatch> {
            prepareBackend.prepareSendBtc(
                PrepareSendBtcRequest(operationContext(prepareContext), sendBtcRequest()),
            )
        }
        assertEquals(2, prepareTransport.requests.size)

        val submitTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val submitBackend = testBackend(submitTransport)
        val submitContext = initializedContext(submitBackend)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            submitBackend.submitSignedTransaction(
                signedSubmission(submitContext, operationId, unpaddedBase64),
            )
        }
        assertEquals(1, submitTransport.requests.size)
    }

    @Test
    fun `backend metadata allowlists reject unsafe codes and correlation IDs`(): Unit = runBlocking {
        val unsafeTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                400,
                "{\"error\":{\"code\":\"lowercase_code\",\"message\":\"ignored\"}}",
                headers = mapOf("x-request-id" to "request/id"),
            ),
        )
        val unsafeBackend = testBackend(unsafeTransport)
        val unsafeContext = initializedContext(unsafeBackend)
        val unsafe = assertFailsWith<SdkException.BackendRejected> {
            unsafeBackend.getBtcBalance(unsafeContext)
        }
        assertTrue(unsafe.details.isEmpty())
        assertEquals(null, unsafe.correlationId)

        val safeTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                400,
                "{\"error\":{\"code\":\"INVALID_ADDRESS_2\",\"message\":\"ignored\"}}",
                headers = mapOf("x-request-id" to "request-2:retry"),
            ),
        )
        val safeBackend = testBackend(safeTransport)
        val safeContext = initializedContext(safeBackend)
        val safe = assertFailsWith<SdkException.BackendRejected> {
            safeBackend.getBtcBalance(safeContext)
        }
        assertEquals("INVALID_ADDRESS_2", safe.details["backendCode"])
        assertEquals("request-2:retry", safe.correlationId)
    }

    @Test
    fun `gateway token remains bound to one logical wallet ID`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val initial = backend.initialize(initializeRequest())
        assertEquals(initial, backend.initialize(initializeRequest()))
        assertEquals(1, transport.requests.size)
        backend.shutdown(initial)
        val restarted = backend.initialize(initializeRequest())
        assertNotEquals(initial.id, restarted.id)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.shutdown(initial)
        }
        assertEquals(restarted, backend.initialize(initializeRequest()))

        assertFailsWith<SdkException.InvalidState> {
            backend.initialize(
                BackendInitializeRequest(
                    walletId = WalletId.of("another-wallet"),
                    network = Network.SIGNET,
                    minimumApiVersion = 1,
                    maximumApiVersion = 1,
                ),
            )
        }
        backend.shutdown(restarted)
        backend.shutdown(restarted)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `gateway identity remains bound across shutdown and reinitialization`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
            queuedResponse(200, "{\"userId\":\"u_second\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val initial = backend.initialize(initializeRequest())
        backend.shutdown(initial)

        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.initialize(initializeRequest())
        }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `malformed gateway user identity is a backend protocol failure`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(
                200,
                "{\"userId\":\"unsafe/user\",\"createdAt\":1234}",
                headers = mapOf("x-request-id" to "request-user"),
            ),
        )
        val error = assertFailsWith<SdkException.BackendProtocolMismatch> {
            testBackend(transport).initialize(initializeRequest())
        }
        assertEquals("request-user", error.correlationId)
    }

    @Test
    fun `gateway routes require their exact success status`(): Unit = runBlocking {
        val initializeTransport = QueueInterceptor(
            queuedResponse(201, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
        )
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            testBackend(initializeTransport).initialize(initializeRequest())
        }

        val prepareTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
            queuedResponse(
                200,
                preparedSendBtcJson(
                    operationId = "123e4567-e89b-42d3-a456-426614174000",
                    expiresAtMillis = 2_000_123L,
                ),
            ),
        )
        val backend = testBackend(prepareTransport)
        val context = initializedContext(backend)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(operationContext(context), sendBtcRequest()),
            )
        }
    }

    @Test
    fun `malformed contradictory or noncanonical completion success is ambiguous`(): Unit =
        runBlocking {
            val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
            val responses = listOf(
                queuedResponse(200, "{\"txid\":\"not-a-txid\"}"),
                queuedResponse(200, "{\"txid\":\"${"f".repeat(64)}\"}"),
                queuedResponse(
                    200,
                    "{\"txid\":\"${TEST_TXID.hex}\"}",
                    headers = mapOf("Content-Type" to "text/plain"),
                ),
                queuedResponse(201, "{\"txid\":\"${TEST_TXID.hex}\"}"),
                queuedResponse(302, "{\"txid\":\"${TEST_TXID.hex}\"}"),
                queuedResponse(409, "{\"error\":{\"message\":\"corrupted\"}}"),
                queuedResponse(200, "x".repeat(1_500_001)),
                queuedResponse(502, "x".repeat(1_500_001)),
            )
            responses.forEach { completion ->
                val transport = QueueInterceptor(
                    queuedResponse(200, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
                    completion,
                )
                val backend = testBackend(transport)
                val context = initializedContext(backend)
                val error = assertFailsWith<SdkException.OperationAmbiguous> {
                    backend.submitSignedTransaction(
                        signedSubmission(context, operationId, TEST_PSBT_BASE64),
                    )
                }
                assertEquals(operationId, error.operationId)
            }
        }

    @Test
    fun `stale or unbounded preparation expiry is rejected`(): Unit = runBlocking {
        val operationId = "123e4567-e89b-42d3-a456-426614174000"
        listOf(1_500_000L, 5_100_001L).forEach { expiresAtMillis ->
            val transport = QueueInterceptor(
                queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
                queuedResponse(
                    201,
                    preparedSendBtcJson(operationId, expiresAtMillis),
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            assertFailsWith<SdkException.BackendProtocolMismatch> {
                backend.prepareSendBtc(
                    PrepareSendBtcRequest(
                        operationContext(context),
                        sendBtcRequest(),
                    ),
                )
            }
        }
    }

    @Test
    fun `tampered returned intent fails closed and response message never leaks`(): Unit = runBlocking {
        val operationId = "123e4567-e89b-42d3-a456-426614174000"
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                201,
                """
                    {
                      "opId":"$operationId",
                      "psbt":"cHNidP8BAHECAAAAAAAAAAAA",
                      "intent":{
                        "kind":"send_btc",
                        "feeRateSatPerVb":2,
                        "recipients":[{
                          "address":"${TEST_GATEWAY_ADDRESS.value}",
                          "scriptHex":"512000",
                          "amountSat":25001
                        }],
                        "asset":null,
                        "utxos":null
                      },
                      "expiresAt":2000123
                    }
                """.trimIndent(),
            ),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )

        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(
                    operationContext,
                    SendBtcRequest(
                        TEST_GATEWAY_ADDRESS,
                        Satoshis.of(25_000),
                        FeeRate.satPerVbyte(2.0),
                        idempotencyKey = TEST_IDEMPOTENCY_KEY,
                    ),
                ),
            )
        }

        val rejectedTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                400,
                "{\"error\":{\"code\":\"WALLET_REQUEST_REJECTED\",\"message\":\"secret-body-marker\"}}",
            ),
        )
        val rejectedBackend = testBackend(rejectedTransport)
        val rejectedContext = initializedContext(rejectedBackend)
        val error = assertFailsWith<SdkException.BackendRejected> {
            rejectedBackend.getBtcBalance(rejectedContext)
        }
        assertFalse(error.message.orEmpty().contains("secret-body-marker"))
    }

    private suspend fun initializedContext(
        backend: RgbLightningNodeGatewayBackend,
    ): BackendContext {
        val session = backend.initialize(initializeRequest())
        return BackendContext(session, TEST_WALLET_ID, Network.SIGNET)
    }

    private fun createPlan(): CreateUtxosOutputPlan = CreateUtxosOutputPlan(listOf(3, 4).map {
        ExpectedWalletOutput(AccountKind.COLORED, Keychain.EXTERNAL, it, Satoshis.of(1_000))
    })

    private fun preparedCreateJson(request: CreateUtxosRequest): String = """
        {"opId":"$TEST_OPERATION_ID","psbt":"$TEST_PSBT_BASE64","intent":{
        "kind":"create_utxos","feeRateSatPerVb":2,"recipients":[],"asset":null,
        "utxos":{"num":${request.num ?: 5},"size":${request.size?.value ?: 1000},"upTo":${request.upTo}}},"expiresAt":2000123}
    """.trimIndent()

    private fun createStatusJson(request: CreateUtxosRequest, state: String = "completed", txid: String? = TEST_TXID.hex): String {
        val preparation = Json.parseToJsonElement(preparedCreateJson(request)).jsonObject
        return JsonObject(mapOf(
            "opId" to JsonPrimitive(TEST_OPERATION_ID), "kind" to JsonPrimitive("create_utxos"),
            "state" to JsonPrimitive(state), "txid" to (txid?.let(::JsonPrimitive) ?: JsonNull),
            "mayHaveBroadcast" to JsonPrimitive(state != "completed" && txid != null),
            "intent" to preparation.getValue("intent"), "createdAt" to JsonPrimitive(1_000_000L),
            "expiresAt" to JsonPrimitive(2_000_123L),
        )).toString()
    }

    private fun createCheckpoint(request: CreateUtxosRequest, phase: OperationPhase = OperationPhase.SUBMITTING): OperationCheckpoint {
        val hash = RequestHasher.createUtxos(Network.SIGNET, request)
        val opId = OperationId.of(TEST_OPERATION_ID)
        val prepared = PreparedTransaction(opId, request.idempotencyKey, hash, Network.SIGNET,
            OperationKind.CREATE_UTXOS, TEST_PSBT_BASE64, UnixSeconds.of(2_000), createPlan())
        return OperationCheckpoint(TEST_WALLET_ID, request.idempotencyKey, hash, OperationKind.CREATE_UTXOS,
            phase, opId, prepared,
            SignedOperationPayload(TEST_PSBT_BASE64, testValidation().copy(
                psbtSha256 = TEST_PSBT_BASE64.decodeBase64()!!.sha256().hex())))
    }

    private fun recoveryCoordinator(
        backend: RgbLightningNodeGatewayBackend,
        journal: OperationJournal,
    ): OperationCoordinator = OperationCoordinator(
        gatewayWalletConfiguration(maximumFeeRate = FeeRate.satPerVbyte(1.0)),
        backend,
        journal,
        EpochSecondsSource { TEST_NOW },
    )

    private fun operationStatusJson(state: String = "completed", txid: String? = TEST_TXID.hex): String {
        val preparation = Json.parseToJsonElement(preparedSendBtcJson(TEST_OPERATION_ID, 2_000_123L)).jsonObject
        return JsonObject(mapOf(
            "opId" to JsonPrimitive(TEST_OPERATION_ID),
            "kind" to JsonPrimitive("send_btc"),
            "state" to JsonPrimitive(state),
            "txid" to (txid?.let(::JsonPrimitive) ?: JsonNull),
            "mayHaveBroadcast" to JsonPrimitive(state != "completed" && txid != null),
            "intent" to preparation.getValue("intent"),
            "createdAt" to JsonPrimitive(1_000_000L),
            "expiresAt" to JsonPrimitive(2_000_123L),
        )).toString()
    }

    private fun initializeRequest(): BackendInitializeRequest = BackendInitializeRequest(
        walletId = TEST_WALLET_ID,
        network = Network.SIGNET,
        minimumApiVersion = 1,
        maximumApiVersion = 1,
    )

    private fun operationContext(context: BackendContext): OperationRequestContext =
        OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )

    private fun inactiveContext(): BackendContext = BackendContext(
        session = BackendSession("inactive-session", 1, Network.SIGNET),
        walletId = TEST_WALLET_ID,
        network = Network.SIGNET,
    )

    private fun gatewayWalletConfiguration(
        maximumFeeRate: FeeRate = FeeRate.satPerVbyte(100.0),
    ): WalletConfiguration = WalletConfiguration(
        walletId = TEST_WALLET_ID,
        network = Network.SIGNET,
        signingPolicy = LocalSigningPolicy(maximumFeeRate = maximumFeeRate),
    )

    private fun persistedSendCheckpoint(
        request: SendBtcRequest,
        phase: OperationPhase,
        expiresAt: UnixSeconds = UnixSeconds.of(TEST_NOW + 1_000L),
    ): OperationCheckpoint {
        require(
            phase == OperationPhase.SIGNED ||
                phase == OperationPhase.SUBMITTING ||
                phase == OperationPhase.TERMINAL,
        )
        val requestHash = RequestHasher.sendBtc(Network.SIGNET, request)
        val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
        val prepared = PreparedTransaction(
            operationId = operationId,
            idempotencyKey = request.idempotencyKey,
            requestHash = requestHash,
            network = Network.SIGNET,
            operation = OperationKind.SEND_BTC,
            psbtBase64 = TEST_PSBT_BASE64,
            expiresAt = expiresAt,
        )
        val signed = SignedOperationPayload(
            TEST_PSBT_BASE64,
            testValidation().copy(psbtSha256 = TEST_PSBT_BASE64.decodeBase64()!!.sha256().hex()),
        )
        return OperationCheckpoint(
            walletId = TEST_WALLET_ID,
            idempotencyKey = request.idempotencyKey,
            requestHash = requestHash,
            operationKind = OperationKind.SEND_BTC,
            phase = phase,
            operationId = operationId,
            preparedTransaction = prepared,
            signedPayload = signed,
            terminalOutcome = if (phase == OperationPhase.TERMINAL) {
                OperationTerminalOutcome.Succeeded(
                    BackendOperationResult.SendBtc(operationId, TEST_TXID),
                )
            } else {
                null
            },
        )
    }

    private fun sendBtcRequest(skipSync: Boolean = false): SendBtcRequest = SendBtcRequest(
        address = TEST_GATEWAY_ADDRESS,
        amount = Satoshis.of(25_000),
        feeRate = FeeRate.satPerVbyte(2.0),
        skipSync = skipSync,
        idempotencyKey = TEST_IDEMPOTENCY_KEY,
    )

    private fun signedSubmission(
        context: BackendContext,
        operationId: OperationId,
        psbtBase64: String,
    ): SignedTransactionSubmission = SignedTransactionSubmission(
        context = operationContext(context),
        operationId = operationId,
        signedPsbtBase64 = psbtBase64,
        validation = testValidation(),
    )

    private fun testBackend(
        interceptor: Interceptor,
        allowUnverifiedPsbtProfile: Boolean = true,
        clock: () -> Long = { 1_500_000L },
        createPlanExtractor: suspend (String, Network) -> CreateUtxosOutputPlan = { _, _ ->
            throw AssertionError("unexpected native create-plan extraction in JVM transport test")
        },
        invoiceDecoder: suspend (RgbInvoice) -> GatewayRgbInvoice = {
            throw AssertionError("unexpected native invoice decoding in JVM transport test")
        },
    ): RgbLightningNodeGatewayBackend {
        val configuration = gatewayConfiguration(
            allowUnverifiedPsbtProfile = allowUnverifiedPsbtProfile,
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        val constructor = RgbLightningNodeGatewayBackend::class.java.getDeclaredConstructor(
            RgbLightningNodeGatewayConfiguration::class.java,
            OkHttpClient::class.java,
            Function0::class.java,
            Function3::class.java,
            Function2::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(configuration, client, clock, createPlanExtractor, invoiceDecoder)
            as RgbLightningNodeGatewayBackend
    }

    private fun gatewayConfiguration(
        baseUrl: String = "https://gateway.example",
        allowInsecureLocalDevelopment: Boolean = false,
        allowUnverifiedPsbtProfile: Boolean = false,
    ): RgbLightningNodeGatewayConfiguration = RgbLightningNodeGatewayConfiguration(
        baseUrl = baseUrl,
        expectedNetwork = Network.SIGNET,
        bearerToken = TEST_TOKEN,
        allowInsecureLocalDevelopment = allowInsecureLocalDevelopment,
        allowUnverifiedPsbtProfile = allowUnverifiedPsbtProfile,
    )

    private fun gatewayIdentity(): WalletIdentity = WalletIdentity(
        network = Network.SIGNET,
        chainNetwork = "signet",
        derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
        masterFingerprint = "deadbeef",
        xpub = "v".repeat(111),
        accountXpubVanilla = "t".repeat(111),
        accountXpubColored = "u".repeat(111),
    )

    private fun preparedSendBtcJson(
        operationId: String,
        expiresAtMillis: Long,
        psbtBase64: String = TEST_PSBT_BASE64,
    ): String = """
        {
          "opId":"$operationId",
          "psbt":"$psbtBase64",
          "intent":{
            "kind":"send_btc",
            "feeRateSatPerVb":2,
            "recipients":[{
              "address":"${TEST_GATEWAY_ADDRESS.value}",
              "scriptHex":"512000",
              "amountSat":25000
            }],
            "asset":null,
            "utxos":null
          },
          "expiresAt":$expiresAtMillis
        }
    """.trimIndent()

    private companion object {
        const val TEST_OPERATION_ID: String = "123e4567-e89b-42d3-a456-426614174000"
        const val TEST_ME_JSON: String = "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"
        const val TEST_TOKEN: String = "utxg_test_token_0123456789"
        const val TEST_PSBT_BASE64: String = "cHNidP8BAHECAAAAAAAAAAAA"
        val TEST_WALLET_ID: WalletId = WalletId.of("gateway-wallet")
        val TEST_GATEWAY_ADDRESS: BitcoinAddress = BitcoinAddress.of(
            "tb1ptestaddress000000000000000000000000000000000000000000000",
        )
    }
}

private class QueueInterceptor(vararg responses: QueuedResponse) : Interceptor {
    private val responses: ArrayDeque<QueuedResponse> = ArrayDeque(responses.toList())
    val requests: MutableList<Request> = mutableListOf()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val response = checkNotNull(responses.pollFirst()) { "no queued response" }
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(response.statusCode)
            .message("test")
            .header("Content-Type", "application/json")
            .body(response.body.toResponseBody("application/json".toMediaType()))
        response.headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}

private data class QueuedResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
)

private fun queuedResponse(
    statusCode: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
): QueuedResponse = QueuedResponse(statusCode, body, headers)

private fun Request.bodyUtf8(): String {
    val buffer = Buffer()
    checkNotNull(body).writeTo(buffer)
    return buffer.readUtf8()
}

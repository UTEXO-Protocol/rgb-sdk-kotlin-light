package com.utexo.rgb.sdk.releasesmoke

import android.content.Context
import com.utexo.rgb.sdk.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Ordinary published factory + real native signer + bounded loopback fixture; never broadcasts. */
public object ReleaseGatewaySpendingSmoke {
    public fun verifiesAllSpendingAndDurableReplay(context: Context): Boolean = runBlocking {
        val fixtures = JSONObject(context.assets.open("gateway-pr174-untouched.json").bufferedReader().use { it.readText() })
            .getJSONArray("fixtures")
        for (index in 0 until fixtures.length()) {
            verifyFixture(context, fixtures.getJSONObject(index))
        }
        true
    }

    private suspend fun verifyFixture(context: Context, fixture: JSONObject) {
        val operation = fixture.getString("name")
        val uncertain = operation == "send-asset"
        val source = fixture.getJSONObject("request")
        val prepared = JSONObject(fixture.getJSONObject("prepared").toString())
        val createdAt = System.currentTimeMillis()
        prepared.put("expiresAt", createdAt + 600_000)
        val resultTxid = fixture.getString("txid")
        val expectedCalls = if (uncertain) 10 else 8
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 20_000
            val finished = CompletableFuture<Unit>()
            val responder = Thread {
                try {
                    var statusCalls = 0
                    var preparations = 0
                    var submissions = 0
                    repeat(expectedCalls) {
                        server.accept().use { socket ->
                            socket.soTimeout = 20_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = reader.readLine()
                            var length = 0
                            var headers = 0
                            while (true) {
                                val line = reader.readLine() ?: error("missing header")
                                if (line.isEmpty()) break
                                check(++headers <= 100)
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            check(length in 0..65_536)
                            val chars = CharArray(length)
                            var offset = 0
                            while (offset < length) {
                                val read = reader.read(chars, offset, length - offset)
                                check(read > 0); offset += read
                            }
                            val request = if (length == 0) JSONObject() else JSONObject(chars.concatToString())
                            var code = 200
                            val response = when {
                                path.startsWith("GET /v1/me ") -> JSONObject().put("userId", "native-spend-fixture").put("createdAt", 1)
                                path.startsWith("POST /v1/wallet/xpubs ") -> {
                                    code = 201
                                    check(request.getString("vanilla").startsWith("tpub"))
                                    JSONObject().put("fingerprint", request.getString("fingerprint")).put("address", "unused-fixture-address")
                                }
                                path.startsWith("POST /v1/onchain/$operation/prepare ") -> {
                                    check(++preparations == 1)
                                    code = 201
                                    check(request.getInt("feeRateSatPerVb") == 2)
                                    when (operation) {
                                        "send-btc" -> check(request.getString("address") == source.getString("address") && request.getLong("amountSat") == source.getLong("amountSat"))
                                        "create-utxos" -> check(!request.getBoolean("upTo") && request.getInt("num") == source.getInt("num") && request.getLong("size") == source.getLong("size"))
                                        "send-asset" -> {
                                            check(request.getString("assetId") == source.getString("assetId") && request.getLong("amount") == source.getLong("amount"))
                                            check(request.getString("recipientId") == source.getString("recipientId"))
                                            check(request.getJSONArray("transportEndpoints").toString() == source.getJSONArray("transportEndpoints").toString())
                                            check(!request.getBoolean("donation") && request.getInt("minConfirmations") == 1)
                                        }
                                    }
                                    prepared
                                }
                                path.startsWith("POST /v1/onchain/$operation/complete ") -> {
                                    check(++submissions == 1)
                                    check(request.getString("opId") == prepared.getString("opId"))
                                    check(request.getString("signedPsbt").startsWith("cHNidP") && request.getString("signedPsbt") != prepared.getString("psbt"))
                                    if (uncertain) {
                                        code = 503
                                        JSONObject().put("error", JSONObject().put("code", "UNAVAILABLE"))
                                    } else JSONObject().put("txid", resultTxid).apply {
                                        if (operation == "create-utxos") put("utxosCreated", fixture.getJSONArray("coloredOutputIndices").length())
                                    }
                                }
                                path.startsWith("GET /v1/onchain/operations/${prepared.getString("opId")} ") -> {
                                    check(uncertain && ++statusCalls <= 2)
                                    JSONObject().put("opId", prepared.getString("opId")).put("kind", "send_asset")
                                        .put("state", if (statusCalls == 1) "pending" else "completed")
                                        .put("txid", if (statusCalls == 1) JSONObject.NULL else resultTxid)
                                        .put("mayHaveBroadcast", false).put("intent", prepared.getJSONObject("intent"))
                                        .put("createdAt", createdAt).put("expiresAt", prepared.getLong("expiresAt"))
                                }
                                else -> error("unexpected fixture route: $path")
                            }.toString().toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(response); flush()
                            }
                        }
                    }
                    check(preparations == 1 && submissions == 1)
                    finished.complete(Unit)
                } catch (failure: Throwable) { finished.completeExceptionally(failure) }
            }.apply { isDaemon = true; start() }
            val configuration = WalletConfiguration(WalletId.of("spend-probe-${UUID.randomUUID()}"), Network.REGTEST,
                LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte("3")))
            val seedStore = AndroidKeystoreSeedStore(context, configuration.walletId, configuration.network)
            SecretMnemonic.fromWords(fixture.getString("mnemonic").toCharArray()).use { mnemonic ->
                deriveSeedFromMnemonic(mnemonic).use { seedStore.provision(it) }
            }
            var tipMode = "unavailable"
            val tipProvider = WalletChainContextProvider {
                if (tipMode == "unavailable") error("secret-source-detail")
                WalletChainContext(if (tipMode == "wrong-network") Network.MAINNET else Network.REGTEST,
                    if (tipMode == "low") 0 else 109,
                    UnixSeconds.of(System.currentTimeMillis() / 1_000 - if (tipMode == "stale") 301 else 0))
            }
            fun service(provider: WalletChainContextProvider?): WalletServiceConfiguration = WalletServiceConfiguration(
                "http://127.0.0.1:${server.localPort}", WalletAPIKeyProvider { WalletAPIKey.fromCharacters("test-only-key".toCharArray()) },
                allowInsecureLocalDevelopment = true, chainContextProvider = provider)
            val key = IdempotencyKey.of("public-$operation")
            val btc = if (operation == "send-btc") SendBtcRequest(BitcoinAddress.of(source.getString("address")),
                Satoshis.of(source.getLong("amountSat")), FeeRate.satPerVbyte("2"), idempotencyKey = key) else null
            val create = if (operation == "create-utxos") CreateUtxosRequest(false, source.getInt("num"),
                Satoshis.of(source.getLong("size")), FeeRate.satPerVbyte("2"), idempotencyKey = key) else null
            val rgb = if (uncertain) OnchainSendRequest(RgbInvoice.of("${source.getString("assetId")}/~/~/${source.getString("recipientId")}?endpoints=${source.getJSONArray("transportEndpoints").getString(0)}"),
                AssetAmount.of(source.getLong("amount")), AssetId.of(source.getString("assetId")), idempotencyKey = key) else null
            suspend fun spend(wallet: UTEXOWallet): String = when {
                btc != null -> wallet.sendBtc(btc).hex
                create != null -> wallet.createUtxos(create).also { check(it.createdUtxoCount == fixture.getJSONArray("coloredOutputIndices").length()) }.txid.hex
                else -> wallet.onchainSend(rgb!!).also { check(it.batchTransferIdx == null) }.txid.hex
            }
            var wallet = UTEXOWallet.create(context, configuration, service(tipProvider))
            try {
                wallet.initialize(); wallet.unlock()
                check(wallet.capabilities.localSigningOperations.containsAll(setOf(OperationKind.SEND_BTC, OperationKind.CREATE_UTXOS, OperationKind.ONCHAIN_SEND)))
                for (mode in listOf("unavailable", "stale", "wrong-network", "low")) {
                    tipMode = mode
                    var rejected = false
                    try { spend(wallet) }
                    catch (error: SdkException) {
                        check(error is SdkException.PolicyRejected || error is SdkException.WrongNetwork)
                        check(!error.message.orEmpty().contains("secret-source-detail"))
                        rejected = true
                    }
                    check(rejected)
                }
                tipMode = "valid"
                if (uncertain) {
                    var ambiguous = false
                    try { spend(wallet) } catch (_: SdkException.OperationAmbiguous) { ambiguous = true }
                    check(ambiguous)
                } else check(spend(wallet) == resultTxid)
                wallet.destroy()
                wallet = UTEXOWallet.create(context, configuration, service(null))
                wallet.initialize(); wallet.unlock()
                check(wallet.capabilities.localSigningOperations.isEmpty())
                check(spend(wallet) == resultTxid)
                check(spend(wallet) == resultTxid)
                finished.get(20, TimeUnit.SECONDS)
            } finally {
                wallet.destroy()
                seedStore.resetForRecovery { true }
                responder.join(1_000)
            }
        }
    }
}

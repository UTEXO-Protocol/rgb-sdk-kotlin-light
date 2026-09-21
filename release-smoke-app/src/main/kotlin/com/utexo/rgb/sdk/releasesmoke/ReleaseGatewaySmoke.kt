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

/** Hostile loopback response through the ordinary factory in the actual R8 artifact. */
public object ReleaseGatewaySmoke {
    /** Real R8 facade + native parser; local synthetic responses, no gateway state or broadcast. */
    public fun validatesReceiveWithNativeParserThroughOrdinaryFactory(context: Context): Boolean = runBlocking {
        check(FeeRate.DEFAULT.milliSatsPerVbyte == 2_000L)
        val witnessRecipient = "bcrt:wvout:Be7zrr7L-VM9RP6X-pBRQduY-5ujGu~3-GqxY7TP-Tk5abpc-6cGRkrQ"
        val blindRecipient = "bcrt:utxob:tjVmHbI2-U0_umHn-bU4cmP6-l3VW00H-ewoi2uz-XZG6O3i-wUFBW"
        val schema = "RWhwUfTMpuP2Zfx1~j4nswCANGeJrYOqDcKelaMV4zU"
        val asset = "rgb:icfqnK9y-wObZKTu-XJcDL98-sKbE5Mh-OuDJhiI-brRJrzE"
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 15_000
            val finished = CompletableFuture<Unit>()
            val responder = Thread {
                try {
                    repeat(8) { step ->
                        server.accept().use { socket ->
                            socket.soTimeout = 15_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = input.readLine()
                            var length = 0
                            var lines = 0
                            while (true) {
                                val line = input.readLine() ?: error("missing headers")
                                if (line.isEmpty()) break
                                check(++lines <= 100)
                                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                            }
                            check(length in 0..16_384)
                            val requestChars = CharArray(length)
                            var offset = 0
                            while (offset < length) {
                                val count = input.read(requestChars, offset, length - offset)
                                check(count > 0); offset += count
                            }
                            val request = if (length == 0) JSONObject() else JSONObject(requestChars.concatToString())
                            val body = when (step) {
                                0, 1 -> {
                                    check(path.startsWith("GET /v1/me "))
                                    JSONObject().put("userId", "native-receive-fixture").put("createdAt", 1)
                                }
                                2 -> {
                                    check(path.startsWith("POST /v1/wallet/xpubs "))
                                    check(request.getString("vanilla").startsWith("tpub"))
                                    JSONObject().put("fingerprint", request.getString("fingerprint")).put("address", "fixture-only-unused-address")
                                }
                                else -> {
                                    check(path.startsWith("POST /v1/wallet/receive "))
                                    val duration = if (step == 7) 60L else 600L
                                    check(request.getLong("durationSeconds") == duration && request.getInt("minConfirmations") == if (step == 7) 100 else 0)
                                    if (step == 7) check(request.getString("assetId") == asset && request.getLong("amount") == 100L)
                                    else check(!request.has("assetId") && !request.has("amount"))
                                    val mode = if (step == 4) "blind" else "witness"
                                    check(request.getString("mode") == mode)
                                    val recipient = if (mode == "blind") blindRecipient else witnessRecipient
                                    val expiry = System.currentTimeMillis() / 1_000 + duration
                                    val state = if (step == 5 || step == 7) "BF" else "~" // 100, hidden by public Any projection without schema/name.
                                    val selectedSchema = if (step == 6 || step == 7) schema else "~"
                                    val contract = if (step == 7) asset else "rgb:~"
                                    val invoice = "$contract/$selectedSchema/$state/$recipient?expiry=$expiry&endpoints=rpc://localhost:3000/json-rpc"
                                    JSONObject().put("invoice", invoice).put("recipientId", recipient)
                                        .put("expirationTimestamp", if (step == 4) JSONObject.NULL else expiry).put("mode", mode)
                                }
                            }.toString().toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 ${if (step < 2) 200 else 201} Fixture\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(body); flush()
                            }
                        }
                    }
                    finished.complete(Unit)
                } catch (failure: Throwable) { finished.completeExceptionally(failure) }
            }.apply { isDaemon = true; start() }
            val configuration = WalletConfiguration(WalletId.of("receive-probe-${UUID.randomUUID()}"), Network.REGTEST)
            val store = AndroidKeystoreSeedStore(context, configuration.walletId, configuration.network)
            SecretSeed.fromBytes(ByteArray(64) { 29 }).use { store.provision(it) }
            val wallet = UTEXOWallet.create(context, configuration, WalletServiceConfiguration(
                "http://127.0.0.1:${server.localPort}", WalletAPIKeyProvider {
                    WalletAPIKey.fromCharacters("test-only-key".toCharArray())
                }, allowInsecureLocalDevelopment = true,
            ))
            try {
                wallet.init(); wallet.unlock()
                for (witness in listOf(true, false)) {
                    val result = wallet.onchainReceive(OnchainReceiveRequest(duration = DurationSeconds.of(600), witness = witness))
                    val decoded = wallet.decodeRGBInvoice(result.invoice)
                    check(result.batchTransferIdx == null && decoded.recipientId == result.recipientId)
                    check(decoded.expirationTimestamp != null)
                    if (!witness) check(result.expirationTimestamp == null)
                    check(decoded.assignment.type == AssignmentType.ANY && decoded.assignment.amount == null)
                    check(decoded.transportEndpoints == listOf("rpc://localhost:3000/json-rpc"))
                }
                repeat(2) {
                    var rejected = false
                    try { wallet.onchainReceive(OnchainReceiveRequest(duration = DurationSeconds.of(600))) }
                    catch (_: SdkException.BackendProtocolMismatch) { rejected = true }
                    check(rejected)
                }
                val explicit = wallet.onchainReceive(OnchainReceiveRequest(
                    amount = AssetAmount.of(100), assetId = AssetId.of(asset),
                    duration = DurationSeconds.of(60), minConfirmations = BlockCount.of(100),
                ))
                val exact = wallet.decodeRgbInvoice(explicit.invoice)
                check(exact.assetId == AssetId.of(asset) && exact.assignment == Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(100)))
                finished.get(15, TimeUnit.SECONDS)
            } finally {
                wallet.destroy()
                store.resetForRecovery { true } // Own synthetic seed only; never application custody.
                responder.join(1_000)
            }
        }
        true
    }

    public fun rejectsDeepJsonWithoutCrashing(context: Context): Boolean = runBlocking {
        val body = ("{\"userId\":\"audit\",\"createdAt\":1,\"extra\":" +
            "[".repeat(100_000) + "0" + "]".repeat(100_000) + "}").toByteArray()
        for (status in listOf(200, 400)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 15_000
                val finished = CompletableFuture<Unit>()
                val responder = Thread {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 15_000
                            val input = socket.getInputStream().bufferedReader()
                            check(input.readLine().startsWith("GET /v1/me "))
                            var lines = 0
                            while (input.readLine().isNotEmpty()) check(++lines <= 100)
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(body)
                                flush()
                            }
                        }
                        finished.complete(Unit)
                    } catch (failure: Throwable) { finished.completeExceptionally(failure) }
                }.apply { isDaemon = true; start() }
                val wallet = UTEXOWallet.create(
                    context, WalletConfiguration(WalletId.of("json-probe-${UUID.randomUUID()}"), Network.REGTEST),
                    WalletServiceConfiguration(
                        "http://127.0.0.1:${server.localPort}",
                        WalletAPIKeyProvider { WalletAPIKey.fromCharacters("test-only-key".toCharArray()) },
                        allowInsecureLocalDevelopment = true,
                    ),
                )
                try {
                    var failure: SdkException? = null
                    try { wallet.init() } catch (sdk: SdkException) { failure = sdk }
                    check(if (status == 200) failure is SdkException.BackendProtocolMismatch
                        else failure is SdkException.BackendRejected)
                    finished.get(15, TimeUnit.SECONDS)
                } finally {
                    wallet.destroy()
                    responder.join(1_000)
                }
            }
        }
        true
    }
}

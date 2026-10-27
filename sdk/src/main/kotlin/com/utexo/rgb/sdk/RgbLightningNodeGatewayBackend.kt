package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.GatewayCreateUtxosPlanExtractor
import com.utexo.rgb.sdk.internal.GatewayRgbInvoice
import com.utexo.rgb.sdk.internal.GatewayRgbInvoiceDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64

/**
 * Connection policy for the PR #174 `minimal-sdk/packages/gateway` service.
 *
 * The bearer token is intentionally not exposed as a property and is redacted
 * from [toString]. Callers should retrieve it from encrypted application
 * storage and avoid retaining additional copies.
 */
@WalletInfrastructureApi
public class RgbLightningNodeGatewayConfiguration(
    baseUrl: String,
    public val expectedNetwork: Network,
    bearerToken: String,
    public val connectTimeoutMillis: Long = 10_000L,
    public val readTimeoutMillis: Long = 30_000L,
    public val callTimeoutMillis: Long = 45_000L,
    /** Rejects unexpectedly long-lived signing requests from a hostile or misconfigured server. */
    public val maximumPreparationTtlSeconds: Long = 3_600L,
    /** Enables HTTP only for loopback and the Android emulator host alias. */
    public val allowInsecureLocalDevelopment: Boolean = false,
    /**
     * Enables the gateway spending transports. The ordinary wallet factory enables
     * this and selects the local gateway signer with an application-supplied chain
     * context. The legacy SPI default remains false for explicit read-only use.
     * This flag does not certify backend crash/TTL recovery or RGB reservation safety.
     */
    public val allowUnverifiedPsbtProfile: Boolean = false,
) {
    public val baseUrl: String

    private val httpBaseUrl: HttpUrl
    private val token: String

    init {
        if (expectedNetwork == Network.TESTNET4 || expectedNetwork == Network.UTEXO) {
            throw SdkException.Unsupported(
                "PR #174 gateway network ${expectedNetwork.wireValue}",
            )
        }
        validateTimeout("connectTimeoutMillis", connectTimeoutMillis)
        validateTimeout("readTimeoutMillis", readTimeoutMillis)
        validateTimeout("callTimeoutMillis", callTimeoutMillis)
        if (maximumPreparationTtlSeconds !in
            MIN_PREPARATION_TTL_SECONDS..MAX_PREPARATION_TTL_SECONDS
        ) {
            throw SdkException.InvalidArgument(
                "maximumPreparationTtlSeconds",
                "must be between $MIN_PREPARATION_TTL_SECONDS and " +
                    "$MAX_PREPARATION_TTL_SECONDS seconds",
            )
        }

        val parsed = baseUrl.trim().toHttpUrlOrNull()
            ?: throw SdkException.InvalidArgument("baseUrl", "must be an absolute HTTP URL")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw SdkException.InvalidArgument("baseUrl", "must not contain user information")
        }
        if (parsed.query != null || parsed.fragment != null || parsed.encodedPath != "/") {
            throw SdkException.InvalidArgument(
                "baseUrl",
                "must contain only scheme, host, and optional port",
            )
        }
        if (parsed.scheme != "https") {
            val localHost = parsed.host in LOCAL_DEVELOPMENT_HOSTS
            if (!allowInsecureLocalDevelopment || parsed.scheme != "http" || !localHost) {
                throw SdkException.InvalidArgument(
                    "baseUrl",
                    "must use HTTPS (HTTP is opt-in for local development hosts only)",
                )
            }
        }

        if (bearerToken.isEmpty() || bearerToken.length > MAX_TOKEN_CHARACTERS) {
            throw SdkException.InvalidArgument(
                "bearerToken",
                "must contain between 1 and $MAX_TOKEN_CHARACTERS characters",
            )
        }
        if (bearerToken.any { it.code !in 0x21..0x7e }) {
            throw SdkException.InvalidArgument(
                "bearerToken",
                "must contain visible ASCII characters only",
            )
        }

        this.httpBaseUrl = parsed
        this.baseUrl = parsed.toString().removeSuffix("/")
        this.token = bearerToken
    }

    override fun toString(): String =
        "RgbLightningNodeGatewayConfiguration(baseUrl=$baseUrl, " +
            "expectedNetwork=${expectedNetwork.wireValue}, bearerToken=[REDACTED], " +
            "allowInsecureLocalDevelopment=$allowInsecureLocalDevelopment, " +
            "allowUnverifiedPsbtProfile=$allowUnverifiedPsbtProfile)"

    @JvmSynthetic
    internal fun resolvedBaseUrl(): HttpUrl = httpBaseUrl

    @JvmSynthetic
    internal fun authorizationValue(): String = token

    private fun validateTimeout(field: String, value: Long): Unit {
        if (value !in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            throw SdkException.InvalidArgument(
                field,
                "must be between $MIN_TIMEOUT_MILLIS and $MAX_TIMEOUT_MILLIS milliseconds",
            )
        }
    }

    private companion object {
        val MIN_TIMEOUT_MILLIS: Long = 100L
        val MAX_TIMEOUT_MILLIS: Long = 300_000L
        val MAX_TOKEN_CHARACTERS: Int = 4_096
        val MIN_PREPARATION_TTL_SECONDS: Long = 1L
        val MAX_PREPARATION_TTL_SECONDS: Long = 604_800L
        val LOCAL_DEVELOPMENT_HOSTS: Set<String> = setOf("127.0.0.1", "::1", "localhost", "10.0.2.2")
    }
}

/**
 * HTTP adapter for the watch-only gateway introduced by rgb-lightning-node PR #174.
 *
 * This is deliberately an infrastructure implementation. [UTEXOWallet]
 * remains the application-facing API and continues to validate and sign a
 * prepared Bitcoin transaction locally before this adapter can submit it.
 *
 * The current adapter enables only response shapes that map without inventing
 * missing data: initialization, xpub registration, BTC/asset balances, and
 * BTC/create prepare/complete only when
 * [RgbLightningNodeGatewayConfiguration.allowUnverifiedPsbtProfile] is
 * explicitly enabled. Sync/refresh await the gateway's combined BTC/RGB sync.
 * Onchain receive returns exact server state for local invoice validation.
 * Address allocation, UTXOs, assets, transfers, fee estimates and blind/witness
 * receives use the gateway's available state. Unavailable optional metadata is
 * represented as null, never fabricated. Issuance remains unsupported.
 * BTC/create/RGB outcome recovery uses the
 * operation-status route only with retained journal evidence and the original
 * request; the evidence-free [getOperation] entry point remains unsupported.
 */
@WalletInfrastructureApi
public class RgbLightningNodeGatewayBackend private constructor(
    private val configuration: RgbLightningNodeGatewayConfiguration,
    private val httpClient: OkHttpClient,
    private val nowMillis: () -> Long,
    private val createUtxosPlanExtractor: suspend (String, Network) -> CreateUtxosOutputPlan,
    private val invoiceDecoder: suspend (RgbInvoice) -> GatewayRgbInvoice,
) : WalletBackend {
    private var apiKeyProvider: WalletAPIKeyProvider? = null
    private var bindServiceIdentity: (suspend (String, String) -> Unit)? = null
    private val sessionGate: Mutex = Mutex()
    private val credentialGate: Mutex = Mutex()
    private var validatedAuthorizationHash: String? = null

    @Volatile
    private var boundWalletId: WalletId? = null

    @Volatile
    private var boundGatewayUserId: String? = null

    @Volatile
    private var activeSession: SessionBinding? = null

    public constructor(configuration: RgbLightningNodeGatewayConfiguration) : this(
        configuration,
        newHttpClient(configuration),
        System::currentTimeMillis,
        GatewayCreateUtxosPlanExtractor::extract,
        GatewayRgbInvoiceDecoder::decode,
    )

    @JvmSynthetic
    internal fun configureCredentials(
        apiKeyProvider: WalletAPIKeyProvider,
        bindServiceIdentity: (suspend (String, String) -> Unit)? = null,
    ): Unit {
        check(this.apiKeyProvider == null && activeSession == null)
        this.apiKeyProvider = apiKeyProvider
        this.bindServiceIdentity = bindServiceIdentity
    }

    public override suspend fun preflightOperation(operation: OperationKind): Unit {
        when (operation) {
            OperationKind.SEND_BTC, OperationKind.CREATE_UTXOS, OperationKind.ONCHAIN_SEND -> requireUnverifiedPsbtProfileEnabled()
            else -> throw SdkException.Unsupported(
                "PR #174 gateway ${operation.wireValue}",
            )
        }
    }

    override suspend fun initialize(request: BackendInitializeRequest): BackendSession {
        if (request.network != configuration.expectedNetwork) {
            throw SdkException.WrongNetwork(configuration.expectedNetwork, request.network)
        }
        if (GATEWAY_API_VERSION !in request.minimumApiVersion..request.maximumApiVersion) {
            throw SdkException.BackendProtocolMismatch(
                "PR #174 gateway adapter API version is outside the configured range",
            )
        }
        boundWalletId?.let { bound ->
            if (bound != request.walletId) {
                throw SdkException.InvalidState(
                    "initialize gateway wallet",
                    "the credential is already bound to another wallet",
                )
            }
        }

        sessionGate.lock()
        try {
            activeSession?.let { active ->
                if (active.walletId != request.walletId || active.network != request.network) {
                    throw SdkException.InvalidState(
                        "initialize gateway wallet",
                        "another wallet session is active",
                    )
                }
                return active.session
            }

            val response = requestJson(
                method = "GET",
                path = "/v1/me",
                expectedStatusCode = 200,
            )
            response.body.requireKeys("me", setOf("userId", "createdAt"))
            val userId = response.body.requiredString("userId", 128)
            try {
                requireProtocolIdentifier("gatewayUserId", userId)
            } catch (_: SdkException.InvalidArgument) {
                throw SdkException.BackendProtocolMismatch(
                    "gateway returned an invalid user identity",
                    correlationId = response.correlationId,
                )
            }
            response.body.requiredLong("createdAt", minimum = 0L)
            if (boundWalletId != null && boundWalletId != request.walletId) {
                throw SdkException.InvalidState(
                    "initialize gateway wallet",
                    "the credential is already bound to another wallet",
                )
            }
            boundGatewayUserId?.let { bound ->
                if (bound != userId) {
                    throw SdkException.BackendProtocolMismatch(
                        "gateway bearer token resolved to a different user identity",
                        correlationId = response.correlationId,
                    )
                }
            }
            bindServiceIdentity?.invoke(configuration.baseUrl, userId)
            boundWalletId = request.walletId
            boundGatewayUserId = userId

            val session = BackendSession(
                id = "gw.${UUID.randomUUID()}",
                apiVersion = GATEWAY_API_VERSION,
                network = request.network,
                correlationId = response.correlationId,
            )
            activeSession = SessionBinding(
                session = session,
                userId = userId,
                walletId = request.walletId,
                network = request.network,
            )
            return session
        } finally {
            sessionGate.unlock()
        }
    }

    override suspend fun registerWallet(request: RegisterWalletRequest): Unit {
        requireContext(request.context)
        val identity = request.identity
        if (identity.network != request.context.network) {
            throw SdkException.WrongNetwork(request.context.network, identity.network)
        }
        if (identity.chainNetwork != configuration.expectedNetwork.chainNetwork) {
            throw SdkException.BackendProtocolMismatch("wallet identity has the wrong chain network")
        }
        if (identity.derivationProfile != WalletCapabilities.DERIVATION_PROFILE_V1) {
            throw SdkException.BackendProtocolMismatch("wallet identity has an unsupported derivation profile")
        }
        if (!FINGERPRINT.matches(identity.masterFingerprint)) {
            throw SdkException.InvalidArgument(
                "masterFingerprint",
                "must be exactly eight lowercase hexadecimal characters",
            )
        }
        requireGatewayXpub("accountXpubVanilla", identity.accountXpubVanilla)
        requireGatewayXpub("accountXpubColored", identity.accountXpubColored)

        val body = buildJsonObject {
            put("vanilla", identity.accountXpubVanilla)
            put("colored", identity.accountXpubColored)
            put("fingerprint", identity.masterFingerprint)
        }
        val response = requestJson(
            method = "POST",
            path = "/v1/wallet/xpubs",
            expectedStatusCode = 201,
            body = body,
        )
        response.body.requireKeys("registerXpubs", setOf("fingerprint", "address"))
        if (response.body.requiredString("fingerprint", 8) != identity.masterFingerprint) {
            throw SdkException.BackendProtocolMismatch(
                "gateway registration returned a different master fingerprint",
                correlationId = response.correlationId,
            )
        }
        // This route does not return branch/index metadata, so the address is
        // validated only as bounded text and is not used for getAddress().
        response.body.requiredString("address", 128)
    }

    override suspend fun shutdown(session: BackendSession): Unit {
        sessionGate.lock()
        try {
            val active = activeSession ?: return
            if (active.session.id != session.id) {
                throw SdkException.BackendProtocolMismatch(
                    "cannot shut down a different active gateway session",
                )
            }
            activeSession = null
        } finally {
            sessionGate.unlock()
        }
    }

    override suspend fun getBtcBalance(context: BackendContext): BtcBalance {
        requireContext(context)
        val response = walletBalances()
        return response.body.requiredObject("btc").toBtcBalance(response.correlationId)
    }

    /** Both address methods allocate a fresh address; the gateway has no pinned reuse mode. */
    override suspend fun reserveAddress(request: ReserveAddressRequest): ReservedAddress {
        requireContext(request.context)
        if (request.account != AccountKind.VANILLA) throw SdkException.Unsupported("gateway colored address allocation")
        val response = requestJson("GET", "/v1/wallet/address", 200)
        return gatewayModel(response.correlationId) {
            val body = response.body
            val derivation = body.requiredObject("derivation")
            val index = derivation.requiredLong("index", 0, Int.MAX_VALUE.toLong()).toInt()
            val coin = if (request.context.network == Network.MAINNET) 0 else 1
            if (derivation.requiredString("account", 16) != "vanilla" ||
                derivation.requiredLong("keychain", 0, 1) != 0L ||
                derivation.requiredString("derivationPath", 128) != "m/86'/$coin'/0'/0/$index") {
                throw SdkException.BackendProtocolMismatch("gateway address derivation is outside the vanilla BIP86 external branch")
            }
            ReservedAddress(BitcoinAddress.of(body.requiredString("address", 128)),
                AccountKind.VANILLA, Keychain.EXTERNAL, index,
                derivation.requiredString("scriptHex", 68))
        }
    }

    override suspend fun estimateFeeRate(context: BackendContext, targetBlocks: BlockCount): FeeEstimate {
        requireContext(context)
        if (targetBlocks.value !in 1..1_008) throw SdkException.InvalidArgument("blocks", "must be between 1 and 1008")
        val response = requestJson("GET", "/v1/onchain/fee-estimate", 200,
            query = mapOf("blocks" to targetBlocks.value.toString()))
        val body = response.body
        val blocks = body.requiredLong("blocks", 1, 1_008)
        val source = body.requiredLong("sourceBlocks", 1, 1_008)
        val rate = body.requiredLong("feeRateSatPerVb", 1, 1_000)
        if (blocks != targetBlocks.value.toLong()) throw SdkException.BackendProtocolMismatch("gateway echoed a different fee confirmation target")
        if (source > blocks) throw SdkException.Unsupported("gateway fee source cannot satisfy the requested confirmation target")
        if (rate == 1_000L) throw SdkException.Unsupported("gateway fee ceiling may conceal a higher estimate")
        return FeeEstimate(FeeRate.satPerVbyte(rate.toString()), targetBlocks)
    }

    override suspend fun listUnspents(context: BackendContext): List<Unspent> {
        requireContext(context)
        val response = requestJson("GET", "/v1/wallet/unspents", 200)
        return gatewayModel(response.correlationId) {
            val seen = mutableSetOf<Outpoint>()
            response.body.requiredArray("unspents").map { element ->
                val item = element.requireObject("unspent")
                val outpoint = Outpoint(item.gatewayTxid("txid"), item.requiredLong("vout", 0, Int.MAX_VALUE.toLong()).toInt())
                if (!seen.add(outpoint)) throw SdkException.BackendProtocolMismatch("gateway returned duplicate unspent outpoints")
                Unspent(
                    Utxo(outpoint, Satoshis.of(item.requiredLong("amountSat", 0, Satoshis.MAX_VALUE)),
                        item.requiredBoolean("colorable"), item.nullableBoolean("exists")),
                    item.requiredArray("allocations").map { allocation ->
                        val value = allocation.requireObject("allocation")
                        val assignment = value.requiredObject("assignment").gatewayAssignment()
                        // The scalar is a redundant fungible summary, never assignment evidence.
                        val scalar = value.nullableAmount("amount")
                        if ((assignment.type == AssignmentType.FUNGIBLE && scalar != assignment.amount) ||
                            (assignment.type != AssignmentType.FUNGIBLE && scalar != null)) {
                            throw SdkException.BackendProtocolMismatch("gateway allocation summary conflicts with assignment")
                        }
                        RgbAllocation(value.optionalString("assetId", 256)?.let(AssetId::of),
                            assignment, value.requiredBoolean("settled"))
                    },
                    item.requiredLong("pendingBlinded", 0, Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        }
    }

    override suspend fun listAssets(context: BackendContext): ListAssets {
        requireContext(context)
        val response = walletBalances()
        return gatewayModel(response.correlationId) {
            val nia = mutableListOf<AssetNia>()
            val ifa = mutableListOf<AssetIfa>()
            val cfa = mutableListOf<AssetCfa>()
            val uda = mutableListOf<AssetUda>()
            val seen = mutableSetOf<AssetId>()
            for (element in response.body.requiredArray("assets")) {
                val item = element.requireObject("asset")
                val id = AssetId.of(item.requiredString("assetId", 256))
                if (!seen.add(id)) throw SdkException.BackendProtocolMismatch("gateway returned duplicate asset identifiers")
                val schema = item.requiredString("schema", 64)
                val name = item.requiredDisplayString("name", 256)
                val precision = item.requiredLong("precision", 0, 18).toInt()
                val timestamp = UnixSeconds.of(item.requiredLong("timestamp", 0, MAX_SAFE_JSON_INTEGER_VALUE))
                val addedAt = UnixSeconds.of(item.requiredLong("addedAt", 0, MAX_SAFE_JSON_INTEGER_VALUE))
                val balance = item.requiredObject("balance").toAssetBalance(response.correlationId)
                val details = if (item["details"] == JsonNull) null else item.requiredDisplayString("details", 65_535)
                val ticker = item.optionalString("ticker", 16)
                fun requiredTicker(): String = ticker ?: throw SdkException.BackendProtocolMismatch("gateway asset is missing its ticker")
                fun supply(): AssetAmount = item.nullableAmount("issuedSupply")
                    ?: throw SdkException.BackendProtocolMismatch("gateway asset is missing issued supply")
                when (schema) {
                    "nia", "NIA" -> nia.add(AssetNia(id, requiredTicker(), name, precision, supply(), timestamp, addedAt, balance, details))
                    "cfa", "CFA" -> cfa.add(AssetCfa(id, name, precision, supply(), timestamp, addedAt, balance, details))
                    "uda", "UDA" -> uda.add(AssetUda(id, requiredTicker(), name, precision, timestamp, addedAt, balance, details))
                    "ifa", "IFA" -> ifa.add(AssetIfa(id, requiredTicker(), name, precision,
                        item.nullableAmount("initialSupply"), item.nullableAmount("maxSupply"),
                        item.nullableAmount("knownCirculatingSupply"), timestamp, addedAt, balance, details))
                    else -> throw SdkException.BackendProtocolMismatch("gateway returned an unsupported asset schema")
                }
            }
            ListAssets(nia, uda, cfa, ifa)
        }
    }

    /**
     * A null [assetId] returns every wallet transfer, ordered by `idx`. The gateway
     * (rgb-lib) treats a null asset as "asset-less transfers only", so the full list
     * is rebuilt here: the asset-less transfers, then the asset list, then one
     * transfers request per asset. That costs N + 2 requests for N assets. Reading
     * the asset-less page before the asset list means a transfer that gains its
     * asset meanwhile is still seen. A non-null [assetId] is a single request.
     */
    override suspend fun listTransfers(context: BackendContext, assetId: AssetId?): List<Transfer> {
        if (assetId != null) return listTransferPage(context, assetId)
        val pages = mutableListOf(listTransferPage(context, null))
        val assets = listAssets(context)
        val assetIds = assets.nia.map { it.assetId } + assets.cfa.map { it.assetId } +
            assets.ifa.map { it.assetId } + assets.uda.map { it.assetId }
        for (id in assetIds) pages += listTransferPage(context, id)
        return mergeTransferPages(pages)
    }

    /**
     * Merges pages by `idx`, which rgb-lib keeps unique per wallet, in fetch order.
     * A later row that carries an asset replaces an earlier row with the same idx
     * (the transfer gained its asset between requests); a later asset-less row never
     * replaces one. The same idx under two different assets is contradictory: no
     * row can be trusted, so it is a protocol mismatch rather than a silent pick.
     */
    private fun mergeTransferPages(pages: List<List<Transfer>>): List<Transfer> {
        val merged = HashMap<Long, Transfer>()
        for (transfer in pages.flatten()) {
            val existing = merged[transfer.idx]
            if (existing != null) {
                if (existing.assetId != null && transfer.assetId != null && existing.assetId != transfer.assetId) {
                    throw SdkException.BackendProtocolMismatch("gateway returned one transfer index for two assets")
                }
                if (transfer.assetId == null) continue
            }
            merged[transfer.idx] = transfer
        }
        return merged.values.sortedBy { it.idx }
    }

    private suspend fun listTransferPage(context: BackendContext, assetId: AssetId?): List<Transfer> {
        requireContext(context)
        val response = requestJson("GET", "/v1/wallet/transfers", 200,
            query = assetId?.let { mapOf("assetId" to it.value) } ?: emptyMap())
        return gatewayModel(response.correlationId) {
            val seen = mutableSetOf<Long>()
            response.body.requiredArray("transfers").map { element ->
                val item = element.requireObject("transfer")
                val idx = item.requiredLong("idx", 0, MAX_SAFE_JSON_INTEGER_VALUE)
                if (!seen.add(idx)) throw SdkException.BackendProtocolMismatch("gateway returned duplicate transfer indices")
                val returnedAsset = item.optionalString("assetId", 256)?.let(AssetId::of)
                if (assetId != null && returnedAsset != assetId) throw SdkException.BackendProtocolMismatch("gateway transfer belongs to a different asset")
                val created = item.requiredLong("createdAt", 0, MAX_SAFE_JSON_INTEGER_VALUE)
                val updated = item.requiredLong("updatedAt", created, MAX_SAFE_JSON_INTEGER_VALUE)
                // The scalar `amount` may summarize requested OR actual assignments;
                // it cannot reconstruct requestedAssignment or replace assignments.
                item.nullableAmount("amount")
                val expiration = item.nullableLong("expiration")
                val expirationAlias = item.nullableLong("expirationTimestamp")
                if (expiration != null && expirationAlias != null && expiration != expirationAlias) {
                    throw SdkException.BackendProtocolMismatch("gateway transfer expiration aliases conflict")
                }
                Transfer(idx = idx, batchTransferIdx = item.nullableLong("batchTransferIdx"),
                    createdAt = UnixSeconds.of(created), updatedAt = UnixSeconds.of(updated),
                    status = TransferStatus.fromWire(item.requiredString("status", 64)),
                    assignments = item.requiredArray("assignments").map { it.requireObject("assignment").gatewayAssignment() },
                    kind = TransferKind.fromWire(item.requiredString("kind", 64)),
                    txid = if (item.optionalString("txid", 64) == null) null else item.gatewayTxid("txid"),
                    recipientId = item.optionalString("recipientId", 1_024),
                    receiveUtxo = item.nullableOutpoint("receiveUtxo"),
                    changeUtxo = item.nullableOutpoint("changeUtxo"),
                    transportEndpoints = item.nullableTransports("transportEndpoints"),
                    expiration = (expiration ?: expirationAlias)?.let(UnixSeconds::of),
                    assetId = returnedAsset)
            }
        }
    }

    override suspend fun blindReceive(context: BackendContext, request: InvoiceRequest): InvoiceReceiveData =
        receiveInvoice(context, request, witness = false)

    override suspend fun witnessReceive(context: BackendContext, request: InvoiceRequest): InvoiceReceiveData =
        receiveInvoice(context, request, witness = true)

    private suspend fun receiveInvoice(context: BackendContext, request: InvoiceRequest, witness: Boolean): InvoiceReceiveData {
        val received = onchainReceive(context, OnchainReceiveRequest(request.amount, request.assetId,
            request.minConfirmations, request.duration, witness))
        return InvoiceReceiveData(received.invoice,
            received.recipientId ?: throw SdkException.BackendProtocolMismatch("gateway receive is missing recipient"),
            received.expirationTimestamp,
            received.batchTransferIdx ?: throw SdkException.BackendProtocolMismatch("gateway receive is missing batch transfer index"))
    }

    /** Both facade methods await the gateway's combined BTC sync and RGB refresh. */
    override suspend fun syncWallet(context: BackendContext): SyncResult {
        requireContext(context)
        val response = requestJson(
            method = "POST", path = "/v1/wallet/sync", expectedStatusCode = 200,
            body = buildJsonObject { },
        )
        response.body.requireKeys("sync", setOf("status"))
        if (response.body.requiredString("status", 16) != "ok") {
            throw SdkException.BackendProtocolMismatch("gateway did not confirm completed sync", correlationId = response.correlationId)
        }
        val completedMillis = nowMillis()
        if (completedMillis !in 0L..MAX_SAFE_JSON_INTEGER_VALUE) {
            throw SdkException.InternalInvariantViolation("local completion clock is invalid")
        }
        // The route awaits both operations but returns no chain height/server timestamp.
        return SyncResult(completedAt = UnixSeconds.of(completedMillis / 1_000), tipHeight = null)
    }

    override suspend fun refreshWallet(context: BackendContext): SyncResult = syncWallet(context)

    /**
     * Creates receive state in the trusted gateway wallet. The facade validates the
     * returned invoice locally; this DTO cannot prove beneficiary ownership by seed.
     */
    override suspend fun onchainReceive(
        context: BackendContext,
        request: OnchainReceiveRequest,
    ): OnchainReceiveResponse {
        requireContext(context)
        val duration = request.duration?.value ?: 86_400L
        if (duration !in 60L..2_592_000L) {
            throw SdkException.Unsupported("gateway receive duration outside 60..2592000 seconds")
        }
        if (request.minConfirmations.value !in 0..100) {
            throw SdkException.Unsupported("gateway receive minConfirmations outside 0..100")
        }
        val amount = request.amount?.decimal?.toLongOrNull()
        if (request.amount != null && (amount == null || amount !in 1..MAX_SAFE_JSON_INTEGER_VALUE)) {
            throw SdkException.Unsupported("lossless gateway receive amounts above the safe JSON integer range")
        }
        val mode = if (request.witness) "witness" else "blind"
        val started = receiveClock()
        val response = requestJson(
            method = "POST", path = "/v1/wallet/receive", expectedStatusCode = 201,
            body = buildJsonObject {
                put("mode", mode)
                request.assetId?.let { put("assetId", it.value) }
                amount?.let { put("amount", it) }
                put("durationSeconds", duration)
                put("minConfirmations", request.minConfirmations.value)
            },
        )
        val finished = receiveClock()
        val body = response.body
        body.requireKeys("receive", setOf("invoice", "recipientId", "expirationTimestamp", "mode"))
        if (body.requiredString("mode", 16) != mode || finished < started) {
            throw SdkException.BackendProtocolMismatch("gateway receive mode or local clock changed")
        }
        // The wire key is required, but its schema permits null. Do not invent a
        // timestamp: the facade always requires and validates the invoice's expiry.
        val expiration = if (body["expirationTimestamp"] == JsonNull) null
            else body.requiredLong("expirationTimestamp", 0, MAX_SAFE_JSON_INTEGER_VALUE)
        val earliest = started + duration - MAX_INVOICE_CLOCK_SKEW_SECONDS
        val latest = finished + duration + MAX_INVOICE_CLOCK_SKEW_SECONDS
        if (expiration != null && (expiration !in earliest..latest || expiration <= finished)) {
            throw SdkException.BackendProtocolMismatch("gateway receive expiration does not match requested duration and local clock")
        }
        return OnchainReceiveResponse(
            invoice = RgbInvoice.of(body.requiredString("invoice", 16_384)),
            recipientId = body.requiredString("recipientId", 1_024),
            expirationTimestamp = expiration?.let(UnixSeconds::of),
            batchTransferIdx = if (body.containsKey("batchTransferIdx") && body["batchTransferIdx"] != JsonNull)
                body.requiredLong("batchTransferIdx", 0, MAX_SAFE_JSON_INTEGER_VALUE) else null,
        )
    }

    private fun receiveClock(): Long {
        val value = nowMillis()
        if (value !in 0..MAX_SAFE_JSON_INTEGER_VALUE) {
            throw SdkException.InternalInvariantViolation("local receive clock is invalid")
        }
        return value / 1_000L
    }

    override suspend fun getAssetBalance(
        context: BackendContext,
        assetId: AssetId,
    ): AssetBalance {
        requireContext(context)
        val response = walletBalances()
        val assets = response.body.requiredArray("assets")
        val matches = assets.mapIndexedNotNull { index, element ->
            val asset = element.requireObject("assets[$index]")
            asset.requireKeys(
                "assets[$index]",
                setOf("assetId", "schema", "ticker", "name", "precision", "balance"),
            )
            val candidateId = asset.requiredString("assetId", 256)
            if (candidateId != assetId.value) return@mapIndexedNotNull null
            asset.requiredString("schema", 64)
            asset.optionalString("ticker", 64)
            asset.requiredDisplayString("name", 256)
            asset.requiredLong("precision", minimum = 0L, maximum = 18L)
            asset.requiredObject("balance").toAssetBalance(response.correlationId)
        }
        if (matches.size > 1) {
            throw SdkException.BackendProtocolMismatch(
                "gateway returned duplicate asset identifiers",
                correlationId = response.correlationId,
            )
        }
        return matches.singleOrNull()
            ?: throw SdkException.BackendRejected(
                safeMessage = "The requested asset is not present in the wallet",
                retryable = false,
                correlationId = response.correlationId,
                backendCode = "ASSET_NOT_FOUND",
            )
    }

    override suspend fun prepareSendBtc(request: PrepareSendBtcRequest): PreparedTransaction {
        requireUnverifiedPsbtProfileEnabled()
        requireOperationContext(request.context)
        validateRequest(request.request)
        val feeRate = requireWholeGatewayFeeRate(request.request.feeRate)
        val startedAtMillis = preparationClock()
        val requestBody = buildJsonObject {
            put("address", request.request.address.value)
            put("amountSat", request.request.amount.value)
            put("feeRateSatPerVb", feeRate)
        }
        val response = requestJson(
            method = "POST",
            path = "/v1/onchain/send-btc/prepare",
            expectedStatusCode = 201,
            body = requestBody,
            idempotencyKey = provisionalStagedIdempotencyKey(
                request.context.idempotencyKey,
                "prepare",
            ),
            logicalIdempotencyKey = request.context.idempotencyKey,
        )
        response.body.requireKeys(
            "preparedSendBtc",
            setOf("opId", "psbt", "intent", "expiresAt"),
        )
        validateSendBtcIntent(response, request.request, feeRate)

        val operationIdValue = response.body.requiredString("opId", 36)
        if (!CANONICAL_UUID.matches(operationIdValue)) {
            throw SdkException.BackendProtocolMismatch(
                "gateway returned a non-canonical operation ID",
                correlationId = response.correlationId,
            )
        }
        val psbt = response.body.requiredString("psbt", MAX_PREPARED_PSBT_CHARACTERS)
        requireCanonicalBase64(
            value = psbt,
            field = "prepared PSBT",
            operationId = OperationId.of(operationIdValue),
            correlationId = response.correlationId,
        )
        val expiresAtMillis = response.body.requiredLong("expiresAt", 1_000L, MAX_SAFE_JSON_INTEGER_VALUE)
        val receivedAtMillis = preparationClock()
        val maximumTtlMillis = configuration.maximumPreparationTtlSeconds * 1_000L
        if (receivedAtMillis < startedAtMillis || expiresAtMillis <= receivedAtMillis ||
            expiresAtMillis - receivedAtMillis > maximumTtlMillis
        ) {
            throw SdkException.BackendProtocolMismatch(
                "gateway preparation expiry is stale or exceeds the configured maximum TTL",
                operationId = OperationId.of(operationIdValue),
                correlationId = response.correlationId,
            )
        }
        return PreparedTransaction(
            operationId = OperationId.of(operationIdValue),
            idempotencyKey = request.context.idempotencyKey,
            requestHash = request.context.requestHash,
            network = configuration.expectedNetwork,
            operation = OperationKind.SEND_BTC,
            psbtBase64 = psbt,
            // Flooring is deliberately conservative: the SDK stops signing up
            // to 999 ms before the server deadline, never after it.
            expiresAt = UnixSeconds.of(expiresAtMillis / 1_000L),
            correlationId = response.correlationId,
        )
    }

    /** Deterministic gateway-domain checks, performed before journaling or acquiring credentials. */
    @JvmSynthetic
    internal fun validateRequest(request: SendBtcRequest): Unit {
        requireWholeGatewayFeeRate(request.feeRate)
        if (request.skipSync) throw SdkException.Unsupported("PR #174 gateway sendBtc skipSync")
        if (request.amount.value < 294L) {
            throw SdkException.InvalidArgument("amount", "gateway requires at least 294 satoshis")
        }
    }

    @JvmSynthetic
    internal fun validateRequest(request: CreateUtxosRequest): Unit {
        requireWholeGatewayFeeRate(request.feeRate)
        if (request.skipSync) throw SdkException.Unsupported("PR #174 gateway createUtxos skipSync")
        if ((request.num ?: 5) !in 1..50) {
            throw SdkException.InvalidArgument("num", "gateway accepts between 1 and 50 create outputs")
        }
        // The service accepts 294 but these owned outputs are P2TR: the local
        // signer requires the 330-satoshi dust floor, without rounding the request.
        if ((request.size?.value ?: 1_000L) !in 330L..4_294_967_295L) {
            throw SdkException.InvalidArgument("size", "gateway P2TR create outputs require 330..4294967295 satoshis")
        }
    }

    @JvmSynthetic
    internal fun validateRequest(request: OnchainSendRequest): Unit {
        requireWholeGatewayFeeRate(request.feeRate)
        if (request.skipSync) throw SdkException.Unsupported("PR #174 gateway onchainSend skipSync")
        if (request.witnessData?.blinding != null) throw SdkException.Unsupported("PR #174 gateway witness blinding")
        if (request.amount.decimal.toLongOrNull()?.let { it in 1..MAX_SAFE_JSON_INTEGER_VALUE } != true) {
            throw SdkException.Unsupported("lossless gateway asset amounts above the safe JSON integer range")
        }
        if (request.minConfirmations.value !in 0..100) throw SdkException.Unsupported("gateway minConfirmations outside 0..100")
        if (request.witnessData?.amountSat?.value?.let { it < 294 } == true) {
            throw SdkException.InvalidArgument("witnessData.amountSat", "gateway requires at least 294 satoshis")
        }
    }

    private suspend fun rgbRouting(request: OnchainSendRequest): Pair<GatewayRgbInvoice, String> {
        val invoice = invoiceDecoder(request.invoice)
        if (invoice.network != configuration.expectedNetwork &&
            !(configuration.expectedNetwork == Network.UTEXO && invoice.network == Network.SIGNET)) {
            throw SdkException.WrongNetwork(configuration.expectedNetwork, invoice.network)
        }
        val asset = request.assetId?.value ?: invoice.assetId
            ?: throw SdkException.InvalidArgument("assetId", "must be supplied in the request or invoice")
        if (invoice.assetId != null && invoice.assetId != asset) throw SdkException.InvalidArgument("assetId", "must match invoice")
        if (invoice.witness != (request.witnessData != null)) throw SdkException.InvalidArgument("witnessData", "must match invoice recipient mode")
        if (invoice.transportEndpoints.size !in 1..4 || invoice.transportEndpoints.distinct().size != invoice.transportEndpoints.size) {
            throw SdkException.Unsupported("gateway requires one to four unique invoice RGB transports")
        }
        return invoice to asset
    }

    override suspend fun prepareOnchainSend(request: PrepareOnchainSendRequest): PreparedTransaction {
        requireUnverifiedPsbtProfileEnabled()
        requireOperationContext(request.context)
        validateRequest(request.request)
        val (invoice, asset) = rgbRouting(request.request)
        requireOperationContext(request.context)
        val started = preparationClock()
        val response = requestJson("POST", "/v1/onchain/send-asset/prepare", 201,
            body = buildJsonObject {
                put("assetId", asset)
                put("amount", request.request.amount.decimal.toLong())
                put("recipientId", invoice.recipientId)
                request.request.witnessData?.let { put("witnessAmountSat", it.amountSat.value) }
                if (invoice.transportEndpoints.isNotEmpty()) put("transportEndpoints", JsonArray(invoice.transportEndpoints.map(::JsonPrimitive)))
                put("donation", request.request.donation)
                put("minConfirmations", request.request.minConfirmations.value)
                put("feeRateSatPerVb", requireWholeGatewayFeeRate(request.request.feeRate))
            },
            idempotencyKey = provisionalStagedIdempotencyKey(request.context.idempotencyKey, "prepare"),
            logicalIdempotencyKey = request.context.idempotencyKey)
        validateRgbIntent(response, request.request, invoice, asset)
        val idText = response.body.requiredString("opId", 36)
        if (!CANONICAL_UUID.matches(idText)) throw SdkException.BackendProtocolMismatch("gateway returned a non-canonical operation ID")
        val id = OperationId.of(idText)
        val psbt = response.body.requiredString("psbt", MAX_PREPARED_PSBT_CHARACTERS)
        requireCanonicalBase64(psbt, "prepared PSBT", id, response.correlationId)
        val expiry = response.body.requiredLong("expiresAt", 1_000, MAX_SAFE_JSON_INTEGER_VALUE)
        val finished = preparationClock()
        if (finished < started || expiry <= finished || expiry - finished > configuration.maximumPreparationTtlSeconds * 1_000L) {
            throw SdkException.BackendProtocolMismatch("gateway RGB preparation expiry is stale or exceeds configured TTL", id)
        }
        return PreparedTransaction(id, request.context.idempotencyKey, request.context.requestHash,
            configuration.expectedNetwork, OperationKind.ONCHAIN_SEND, psbt, UnixSeconds.of(expiry / 1_000),
            correlationId = response.correlationId)
    }

    private fun validateRgbIntent(response: GatewayJsonResponse, request: OnchainSendRequest,
        invoice: GatewayRgbInvoice, assetId: String): Unit {
        val intent = response.body.requiredObject("intent")
        val asset = intent.requiredObject("asset")
        val endpoints = asset.requiredArray("transportEndpoints").map { element ->
            JsonObject(mapOf("endpoint" to element)).requiredString("endpoint", 2_048)
        }
        if (intent.requiredString("kind", 32) != "send_asset" ||
            intent.requiredLong("feeRateSatPerVb", 1, MAX_GATEWAY_FEE_RATE) != requireWholeGatewayFeeRate(request.feeRate) ||
            intent.requiredArray("recipients").isNotEmpty() || intent["utxos"] != JsonNull ||
            asset.requiredString("assetId", 256) != assetId ||
            asset.requiredLong("amount", 1, MAX_SAFE_JSON_INTEGER_VALUE).toString() != request.amount.decimal ||
            asset.requiredString("recipientId", 1_024) != invoice.recipientId ||
            asset.nullableLong("witnessAmountSat") != request.witnessData?.amountSat?.value ||
            endpoints.size !in 1..4 ||
            (invoice.transportEndpoints.isNotEmpty() && endpoints != invoice.transportEndpoints)) {
            throw SdkException.BackendProtocolMismatch("gateway RGB intent differs from original request")
        }
    }

    private fun preparationClock(): Long = nowMillis().also {
        if (it !in 1L..MAX_SAFE_JSON_INTEGER_VALUE) {
            throw SdkException.InternalInvariantViolation("local preparation clock is invalid")
        }
    }

    override suspend fun prepareCreateUtxos(request: PrepareCreateUtxosRequest): PreparedTransaction {
        requireUnverifiedPsbtProfileEnabled()
        requireOperationContext(request.context)
        validateRequest(request.request)
        val started = preparationClock()
        val response = requestJson(
            method = "POST", path = "/v1/onchain/create-utxos/prepare", expectedStatusCode = 201,
            body = buildJsonObject {
                put("num", request.request.num ?: 5)
                put("size", request.request.size?.value ?: 1_000L)
                put("upTo", request.request.upTo)
                put("feeRateSatPerVb", requireWholeGatewayFeeRate(request.request.feeRate))
            },
            idempotencyKey = provisionalStagedIdempotencyKey(request.context.idempotencyKey, "prepare"),
            logicalIdempotencyKey = request.context.idempotencyKey,
        )
        response.body.requireKeys("preparedCreateUtxos", setOf("opId", "psbt", "intent", "expiresAt"))
        validateCreateUtxosIntent(response, request.request)
        val operationIdText = response.body.requiredString("opId", 36)
        if (!CANONICAL_UUID.matches(operationIdText)) {
            throw SdkException.BackendProtocolMismatch("gateway returned a non-canonical operation ID")
        }
        val operationId = OperationId.of(operationIdText)
        val psbt = response.body.requiredString("psbt", MAX_PREPARED_PSBT_CHARACTERS)
        requireCanonicalBase64(psbt, "prepared PSBT", operationId, response.correlationId)
        val expiry = response.body.requiredLong("expiresAt", 1_000L, MAX_SAFE_JSON_INTEGER_VALUE)
        val finished = preparationClock()
        if (finished < started || expiry <= finished ||
            expiry - finished > configuration.maximumPreparationTtlSeconds * 1_000L) {
            throw SdkException.BackendProtocolMismatch(
                "gateway preparation expiry is stale or exceeds the configured maximum TTL", operationId,
            )
        }
        // Extraction supplies untrusted derivation hints, not authorization.
        // The coordinator and signer must validate the exact plan against the
        // original request, local seed and every PSBT output before signing.
        val plan = createUtxosPlanExtractor(psbt, configuration.expectedNetwork)
        currentCoroutineContext().ensureActive()
        requireOperationContext(request.context)
        validateCreateUtxosPlan(request.request, plan)
        return PreparedTransaction(
            operationId = operationId, idempotencyKey = request.context.idempotencyKey,
            requestHash = request.context.requestHash, network = configuration.expectedNetwork,
            operation = OperationKind.CREATE_UTXOS, psbtBase64 = psbt,
            expiresAt = UnixSeconds.of(expiry / 1_000L), createUtxosOutputPlan = plan,
            correlationId = response.correlationId,
        )
    }

    private fun validateCreateUtxosIntent(response: GatewayJsonResponse, request: CreateUtxosRequest): Unit {
        val intent = response.body.requiredObject("intent")
        intent.requireKeys("intent", setOf("kind", "feeRateSatPerVb", "recipients", "asset", "utxos"))
        val utxos = intent.requiredObject("utxos")
        utxos.requireKeys("intent.utxos", setOf("upTo", "num", "size"))
        if (intent.requiredString("kind", 32) != "create_utxos" ||
            intent.requiredLong("feeRateSatPerVb", 1L, MAX_GATEWAY_FEE_RATE) != requireWholeGatewayFeeRate(request.feeRate) ||
            intent.requiredArray("recipients").isNotEmpty() || intent["asset"] !== JsonNull ||
            utxos.requiredBoolean("upTo") != request.upTo ||
            utxos.requiredLong("num", 1L, 50L) != (request.num ?: 5).toLong() ||
            utxos.requiredLong("size", 330L, 4_294_967_295L) != (request.size?.value ?: 1_000L)) {
            throw SdkException.BackendProtocolMismatch("gateway create-UTXOs intent does not match the request")
        }
    }

    private fun validateCreateUtxosPlan(request: CreateUtxosRequest, plan: CreateUtxosOutputPlan): Unit {
        if (plan.outputs.size !in 1..(request.num ?: 5) ||
            plan.outputs.any { it.account != AccountKind.COLORED || it.keychain != Keychain.EXTERNAL ||
                it.amount.value != (request.size?.value ?: 1_000L) } ||
            plan.outputs.map { it.index }.distinct().size != plan.outputs.size) {
            throw SdkException.BackendProtocolMismatch("gateway create-UTXOs PSBT plan does not match the request")
        }
    }

    override suspend fun submitSignedTransaction(
        request: SignedTransactionSubmission,
    ): BackendOperationResult = submitSignedTransactionImpl(request, null, OperationKind.SEND_BTC)

    /** Route selection is bound to the retained preparation, never an ephemeral operation map. */
    @JvmSynthetic
    internal suspend fun submitSignedTransaction(
        request: SignedTransactionSubmission,
        prepared: PreparedTransaction,
    ): BackendOperationResult {
        if (prepared.operationId != request.operationId ||
            prepared.idempotencyKey != request.context.idempotencyKey ||
            prepared.requestHash != request.context.requestHash ||
            prepared.network != request.context.backend.network ||
            prepared.psbtBase64.decodeBase64()?.sha256()?.hex() != request.validation.psbtSha256) {
            throw SdkException.BackendProtocolMismatch("submission does not match retained preparation", request.operationId)
        }
        if (prepared.operation !in setOf(OperationKind.SEND_BTC, OperationKind.CREATE_UTXOS, OperationKind.ONCHAIN_SEND)) {
            throw SdkException.Unsupported("gateway submission for this operation kind")
        }
        return submitSignedTransactionImpl(request, prepared.createUtxosOutputPlan, prepared.operation)
    }

    private suspend fun submitSignedTransactionImpl(
        request: SignedTransactionSubmission,
        createPlan: CreateUtxosOutputPlan?,
        operation: OperationKind,
    ): BackendOperationResult {
        requireUnverifiedPsbtProfileEnabled()
        requireOperationContext(request.context)
        requireCanonicalBase64(
            value = request.signedPsbtBase64,
            field = "signed PSBT",
            operationId = request.operationId,
        )
        val body = buildJsonObject {
            put("opId", request.operationId.value)
            put("signedPsbt", request.signedPsbtBase64)
        }
        if (body.toString().toByteArray(Charsets.UTF_8).size > GATEWAY_REQUEST_BODY_LIMIT_BYTES) {
            throw SdkException.InvalidArgument(
                "signedPsbtBase64",
                "encoded gateway request exceeds the local request-body limit",
            )
        }
        val response = requestJson(
            method = "POST",
            path = when (operation) {
                OperationKind.SEND_BTC -> "/v1/onchain/send-btc/complete"
                OperationKind.CREATE_UTXOS -> "/v1/onchain/create-utxos/complete"
                OperationKind.ONCHAIN_SEND -> "/v1/onchain/send-asset/complete"
                else -> throw SdkException.Unsupported("gateway completion for this operation")
            },
            expectedStatusCode = 200,
            body = body,
            idempotencyKey = provisionalStagedIdempotencyKey(
                request.context.idempotencyKey,
                "complete",
            ),
            logicalIdempotencyKey = request.context.idempotencyKey,
            operationId = request.operationId,
        )
        return try {
            response.body.requireKeys("completedTransaction", setOf("txid"))
            val rawTxid = response.body.requiredString("txid", 64)
            val txid = TransactionId.of(rawTxid)
            if (rawTxid != txid.hex || txid != request.validation.unsignedTxid) {
                throw SdkException.OperationAmbiguous(request.operationId)
            }
            if (operation == OperationKind.ONCHAIN_SEND) {
                BackendOperationResult.OnchainSend(request.operationId, OnchainSendResponse(txid,
                    response.body.nullableLong("batchTransferIdx")))
            } else if (createPlan == null) {
                BackendOperationResult.SendBtc(operationId = request.operationId, txid = txid)
            } else {
                val count = response.body.requiredLong("utxosCreated", 1L, 50L).toInt()
                if (count != createPlan.outputs.size) throw SdkException.OperationAmbiguous(request.operationId)
                BackendOperationResult.CreateUtxos(request.operationId, CreateUtxosResult(txid, count))
            }
        } catch (ambiguous: SdkException.OperationAmbiguous) {
            throw ambiguous
        } catch (_: SdkException) {
            // A syntactically successful completion can mean the transaction
            // was broadcast even when its response cannot be trusted.
            throw SdkException.OperationAmbiguous(request.operationId)
        }
    }

    /** Read-only recovery; no signing-profile opt-in is needed to resolve an existing submission. */
    @JvmSynthetic
    internal suspend fun getOperationForRecovery(
        context: BackendContext,
        checkpoint: OperationCheckpoint,
        request: SendBtcRequest,
    ): BackendOperationStatus {
        validateRequest(request)
        return getOperationForRecoveryImpl(
            context, checkpoint, OperationKind.SEND_BTC, "send_btc", request.idempotencyKey,
            RequestHasher.sendBtc(context.network, request),
            validateIntent = { validateSendBtcIntent(it, request, requireWholeGatewayFeeRate(request.feeRate)) },
            result = { operationId, txid -> BackendOperationResult.SendBtc(operationId, txid) },
        )
    }

    @JvmSynthetic
    internal suspend fun getOperationForRecovery(context: BackendContext, checkpoint: OperationCheckpoint,
        request: OnchainSendRequest): BackendOperationStatus {
        validateRequest(request)
        val (invoice, asset) = rgbRouting(request)
        return getOperationForRecoveryImpl(context, checkpoint, OperationKind.ONCHAIN_SEND, "send_asset",
            request.idempotencyKey, RequestHasher.onchainSend(context.network, request),
            validateIntent = { validateRgbIntent(it, request, invoice, asset) },
            result = { id, txid -> BackendOperationResult.OnchainSend(id, OnchainSendResponse(txid)) })
    }

    @JvmSynthetic
    internal suspend fun getOperationForRecovery(
        context: BackendContext,
        checkpoint: OperationCheckpoint,
        request: CreateUtxosRequest,
    ): BackendOperationStatus {
        validateRequest(request)
        val plan = checkpoint.preparedTransaction?.createUtxosOutputPlan
            ?: throw SdkException.BackendProtocolMismatch("create recovery has no retained output plan")
        validateCreateUtxosPlan(request, plan)
        return getOperationForRecoveryImpl(
            context, checkpoint, OperationKind.CREATE_UTXOS, "create_utxos", request.idempotencyKey,
            RequestHasher.createUtxos(context.network, request),
            validateIntent = { validateCreateUtxosIntent(it, request) },
            result = { operationId, txid ->
                // Status has no count. After authoritative completed+matching txid,
                // the authenticated locally validated output plan supplies it.
                BackendOperationResult.CreateUtxos(operationId, CreateUtxosResult(txid, plan.outputs.size))
            },
        )
    }

    private suspend fun getOperationForRecoveryImpl(
        context: BackendContext,
        checkpoint: OperationCheckpoint,
        kind: OperationKind,
        wireKind: String,
        idempotencyKey: IdempotencyKey,
        requestHash: RequestHash,
        validateIntent: (GatewayJsonResponse) -> Unit,
        result: (OperationId, TransactionId) -> BackendOperationResult,
    ): BackendOperationStatus {
        requireContext(context)
        val operationId = checkpoint.operationId
            ?: throw SdkException.BackendProtocolMismatch("recovery has no retained operation ID")
        fun mismatch(): Nothing = throw SdkException.BackendProtocolMismatch(
            "gateway operation status does not match retained local evidence",
            operationId,
        )
        val prepared = checkpoint.preparedTransaction ?: mismatch()
        val signed = checkpoint.signedPayload ?: mismatch()
        if (checkpoint.phase != OperationPhase.SUBMITTING ||
            checkpoint.walletId != context.walletId ||
            checkpoint.operationKind != kind || prepared.operation != kind ||
            prepared.network != context.network ||
            checkpoint.idempotencyKey != idempotencyKey ||
            checkpoint.requestHash != requestHash ||
            !CANONICAL_UUID.matches(operationId.value)
        ) mismatch()
        requireCanonicalBase64(prepared.psbtBase64, "retained prepared PSBT", operationId)
        requireCanonicalBase64(signed.signedPsbtBase64, "retained signed PSBT", operationId)
        val preparedDigest = prepared.psbtBase64.decodeBase64()?.sha256()?.hex() ?: mismatch()
        if (preparedDigest != signed.validation.psbtSha256) mismatch()
        val response = requestJson(
            method = "GET",
            path = "/v1/onchain/operations/${operationId.value}",
            expectedStatusCode = 200,
            operationId = operationId,
        )
        val body = response.body
        body.requireKeys(
            "operationStatus",
            setOf("opId", "kind", "state", "txid", "mayHaveBroadcast", "intent", "createdAt", "expiresAt"),
        )
        if (body.requiredString("opId", 36) != operationId.value ||
            body.requiredString("kind", 32) != wireKind
        ) mismatch()
        validateIntent(response)
        val createdAt = body.requiredLong("createdAt", 1L, MAX_SAFE_JSON_INTEGER_VALUE)
        val expiresAt = body.requiredLong("expiresAt", 1_000L, MAX_SAFE_JSON_INTEGER_VALUE)
        if (createdAt >= expiresAt ||
            expiresAt - createdAt > configuration.maximumPreparationTtlSeconds * 1_000L ||
            expiresAt / 1_000L != prepared.expiresAt.value
        ) mismatch()
        val state = body.requiredString("state", 16)
        if (state !in setOf("pending", "completed", "expired")) mismatch()
        val txidHex = body.optionalString("txid", 64)
        val txid = txidHex?.let {
            if (it.length != 64 || it.any { char -> char !in "0123456789abcdef" }) mismatch()
            TransactionId.of(it).also { parsed ->
                if (parsed != signed.validation.unsignedTxid) mismatch()
            }
        }
        val mayHaveBroadcast = body["mayHaveBroadcast"] as? JsonPrimitive ?: mismatch()
        if (mayHaveBroadcast.isString || mayHaveBroadcast.content !in setOf("true", "false") ||
            (mayHaveBroadcast.content == "true") != (state != "completed" && txid != null)
        ) mismatch()
        if (state == "completed") {
            return BackendOperationStatus.Succeeded(
                operationId,
                result(operationId, txid ?: mismatch()),
            )
        }
        // GET deliberately bypasses the wallet queue. Even expired + null txid can race an
        // already running complete call or a crash after broadcast and before durable bookkeeping.
        // Never convert this observation into a definitive expiry or automatically submit again.
        return BackendOperationStatus.Pending(operationId, state)
    }

    private suspend fun walletBalances(): GatewayJsonResponse {
        val response = requestJson(
            method = "GET",
            path = "/v1/wallet/balances",
            expectedStatusCode = 200,
        )
        response.body.requireKeys("balances", setOf("btc", "assets"))
        response.body.requiredObject("btc")
        response.body.requiredArray("assets")
        return response
    }

    private fun validateSendBtcIntent(
        response: GatewayJsonResponse,
        request: SendBtcRequest,
        feeRate: Long,
    ): Unit {
        val intent = response.body.requiredObject("intent")
        intent.requireKeys(
            "intent",
            setOf("kind", "feeRateSatPerVb", "recipients", "asset", "utxos"),
        )
        if (intent.requiredString("kind", 32) != "send_btc" ||
            intent.requiredLong("feeRateSatPerVb", 1L, MAX_GATEWAY_FEE_RATE) != feeRate
        ) {
            throw SdkException.BackendProtocolMismatch(
                "gateway send-BTC intent kind or fee rate does not match the request",
                correlationId = response.correlationId,
            )
        }
        if (intent["asset"] !== JsonNull || intent["utxos"] !== JsonNull) {
            throw SdkException.BackendProtocolMismatch(
                "gateway send-BTC intent contains an unrelated operation payload",
                correlationId = response.correlationId,
            )
        }
        val recipients = intent.requiredArray("recipients")
        if (recipients.size != 1) {
            throw SdkException.BackendProtocolMismatch(
                "gateway send-BTC intent must contain exactly one recipient",
                correlationId = response.correlationId,
            )
        }
        val recipient = recipients.single().requireObject("intent.recipients[0]")
        recipient.requireKeys(
            "intent.recipients[0]",
            setOf("address", "scriptHex", "amountSat"),
        )
        val scriptHex = recipient.requiredString("scriptHex", 20_000)
        if (scriptHex.length % 2 != 0 || scriptHex.any { it !in "0123456789abcdef" }) {
            throw SdkException.BackendProtocolMismatch(
                "gateway send-BTC intent contains malformed script hex",
                correlationId = response.correlationId,
            )
        }
        if (recipient.requiredString("address", 128) != request.address.value ||
            recipient.requiredLong("amountSat", 1L, Satoshis.MAX_VALUE) != request.amount.value
        ) {
            throw SdkException.BackendProtocolMismatch(
                "gateway send-BTC recipient does not match the request",
                correlationId = response.correlationId,
            )
        }
    }

    private fun requireContext(context: BackendContext): SessionBinding {
        if (context.network != configuration.expectedNetwork ||
            context.session.network != configuration.expectedNetwork
        ) {
            throw SdkException.WrongNetwork(configuration.expectedNetwork, context.network)
        }
        if (context.session.apiVersion != GATEWAY_API_VERSION) {
            throw SdkException.BackendProtocolMismatch("gateway session API version changed")
        }
        val binding = activeSession?.takeIf { it.session.id == context.session.id }
            ?: throw SdkException.InvalidState("gateway request", "session is not active")
        if (binding.walletId != context.walletId || binding.network != context.network) {
            throw SdkException.BackendProtocolMismatch("gateway context does not match its session")
        }
        return binding
    }

    private fun requireOperationContext(context: OperationRequestContext): SessionBinding =
        requireContext(context.backend)

    private suspend fun requestJson(
        method: String,
        path: String,
        expectedStatusCode: Int,
        body: JsonObject? = null,
        idempotencyKey: String? = null,
        logicalIdempotencyKey: IdempotencyKey? = null,
        operationId: OperationId? = null,
        authorizationOverride: String? = null,
        query: Map<String, String> = emptyMap(),
    ): GatewayJsonResponse {
        val expectedSession = activeSession
        fun requireUnchangedSession(): Unit {
            if (activeSession !== expectedSession) {
                if (operationId != null) throw SdkException.OperationAmbiguous(operationId)
                throw SdkException.InvalidState("gateway request", "session changed while the request was suspended")
            }
        }
        if ((idempotencyKey == null) != (logicalIdempotencyKey == null)) {
            throw SdkException.InternalInvariantViolation(
                "gateway wire and logical idempotency keys must be supplied together",
            )
        }
        val route = configuration.resolvedBaseUrl().resolve(path.removePrefix("/"))
            ?: throw SdkException.InternalInvariantViolation("gateway route could not be resolved")
        val url = route.newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val encodedBody = body?.toString()
        val authorization = authorizationOverride ?: authorizationHeader()
        requireUnchangedSession()
        if (apiKeyProvider != null && authorizationOverride == null && path != "/v1/me") {
            verifyCredentialBinding(authorization)
            requireUnchangedSession()
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .header("Authorization", authorization)
            .header("User-Agent", USER_AGENT)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(
                encodedBody?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: EMPTY_REQUEST_BODY,
            )
            else -> throw SdkException.InternalInvariantViolation("unsupported gateway HTTP method")
        }

        val raw = execute(builder.build(), operationId)
        requireUnchangedSession()
        val correlationId = raw.correlationId
        if (raw.statusCode != expectedStatusCode) {
            if (raw.statusCode in 200..299) {
                throw successfulResponseProtocolFailure(operationId, correlationId)
            }
            throw mapGatewayError(
                statusCode = raw.statusCode,
                body = raw.body,
                correlationId = correlationId,
                operationId = operationId,
                logicalIdempotencyKey = logicalIdempotencyKey,
            )
        }
        if (!raw.isJson) {
            throw successfulResponseProtocolFailure(operationId, correlationId)
        }
        val parsed = try {
            parseJsonObject(raw.body, operationId, correlationId)
        } catch (_: SdkException.BackendProtocolMismatch) {
            throw successfulResponseProtocolFailure(operationId, correlationId)
        }
        return GatewayJsonResponse(parsed, correlationId)
    }

    private suspend fun authorizationHeader(): String {
        val provider = apiKeyProvider ?: return "Bearer ${configuration.authorizationValue()}"
        return try {
            provider.loadAPIKey().use { "Bearer ${it.authorizationValue()}" }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw SdkException.KeyAccessFailed(retryable = true)
        }
    }

    private suspend fun verifyCredentialBinding(authorization: String): Unit {
        credentialGate.lock()
        try {
            val hash = ProtectedWalletFile.digest(authorization.toByteArray(Charsets.UTF_8))
            if (validatedAuthorizationHash == hash) return
            val expected = boundGatewayUserId ?: throw SdkException.InvalidState("authenticate request", "uninitialized")
            val response = requestJson("GET", "/v1/me", 200, authorizationOverride = authorization)
            response.body.requireKeys("me", setOf("userId", "createdAt"))
            if (response.body.requiredString("userId", 128) != expected) {
                throw SdkException.BackendProtocolMismatch("gateway credential resolved to a different user identity")
            }
            response.body.requiredLong("createdAt", minimum = 0L)
            validatedAuthorizationHash = hash
        } finally {
            credentialGate.unlock()
        }
    }

    private fun successfulResponseProtocolFailure(
        operationId: OperationId?,
        correlationId: String?,
    ): SdkException = operationId?.let(SdkException::OperationAmbiguous)
        ?: SdkException.BackendProtocolMismatch(
            "gateway returned an invalid success response",
            correlationId = correlationId,
        )

    private suspend fun execute(request: Request, operationId: OperationId?): GatewayRawResponse =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException): Unit {
                        if (continuation.isCancelled) return
                        continuation.resumeWith(Result.failure(SdkException.BackendUnavailable()))
                    }

                    override fun onResponse(call: Call, response: Response): Unit {
                        val result = try {
                            response.use { toRawResponse(it, operationId) }
                        } catch (sdk: SdkException) {
                            continuation.resumeWith(Result.failure(sdk))
                            return
                        } catch (_: Exception) {
                            continuation.resumeWith(
                                Result.failure(SdkException.BackendUnavailable()),
                            )
                            return
                        }
                        continuation.resumeWith(Result.success(result))
                    }
                },
            )
        }

    private fun toRawResponse(response: Response, operationId: OperationId?): GatewayRawResponse {
        val correlationId = safeCorrelationId(response.header("x-request-id"))
        val text = try {
            val body = response.body
                ?: throw SdkException.BackendProtocolMismatch(
                    "gateway response has no body",
                    operationId = operationId,
                )
            val bytes = body.readBounded(MAX_RESPONSE_BYTES, operationId)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (ambiguous: SdkException.OperationAmbiguous) {
            throw ambiguous
        } catch (_: Exception) {
            if (operationId != null) {
                throw SdkException.OperationAmbiguous(operationId)
            }
            throw SdkException.BackendProtocolMismatch(
                "gateway response body is invalid",
                operationId = operationId,
                correlationId = correlationId,
            )
        }
        return GatewayRawResponse(
            statusCode = response.code,
            body = text,
            isJson = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.equals(JSON_MEDIA_TYPE_VALUE, ignoreCase = true) == true,
            correlationId = correlationId,
        )
    }

    private fun mapGatewayError(
        statusCode: Int,
        body: String,
        correlationId: String?,
        operationId: OperationId?,
        logicalIdempotencyKey: IdempotencyKey?,
    ): SdkException {
        val backendCode = parseGatewayErrorCode(body)
        return when {
            // operationId identifies complete or submitted-operation recovery requests.
            // No redirect or current 4xx proves that this operation never broadcast,
            // including kind/idempotency conflicts and intermediary auth failures.
            // Classify by phase before inspecting a code that is definitive at prepare.
            statusCode in 300..499 && operationId != null ->
                SdkException.OperationAmbiguous(operationId)
            statusCode == 409 && backendCode == "OP_KIND_MISMATCH" ->
                SdkException.BackendProtocolMismatch(
                    "gateway operation kind does not match its completion route",
                    operationId,
                    correlationId,
                )
            statusCode == 409 && backendCode == "IDEMPOTENCY_KEY_REUSED" &&
                logicalIdempotencyKey != null -> SdkException.OperationConflict(
                logicalIdempotencyKey,
            )
            statusCode == 409 && backendCode == "IDEMPOTENCY_KEY_REUSED" ->
                SdkException.BackendProtocolMismatch(
                    "gateway reported idempotency reuse on a non-idempotent request",
                    operationId,
                    correlationId,
                )
            statusCode == 401 -> SdkException.BackendRejected(
                safeMessage = "Gateway authorization failed",
                retryable = false,
                operationId = operationId,
                correlationId = correlationId,
                backendCode = backendCode ?: "UNAUTHORIZED",
            )
            statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500 ||
                backendCode == "IDEMPOTENCY_IN_FLIGHT" ->
                SdkException.BackendUnavailable(correlationId)
            statusCode in 400..499 -> SdkException.BackendRejected(
                safeMessage = gatewaySafeMessage(backendCode),
                retryable = false,
                operationId = operationId,
                correlationId = correlationId,
                backendCode = backendCode,
            )
            else -> SdkException.BackendProtocolMismatch(
                "gateway returned an invalid HTTP status",
                operationId,
                correlationId,
            )
        }
    }

    private fun parseGatewayErrorCode(body: String): String? = try {
        com.utexo.rgb.sdk.internal.BoundedJson.check(body)
        val root = JSON.parseToJsonElement(body).jsonObject
        val primitive = root["error"]?.jsonObject?.get("code") as? JsonPrimitive
        val code = primitive?.takeIf { it.isString }?.content
        code?.takeIf(BACKEND_CODE::matches)
    } catch (_: Exception) {
        null
    }

    private fun gatewaySafeMessage(code: String?): String = when (code) {
        "WALLET_NOT_REGISTERED" -> "The wallet is not registered with the gateway"
        "XPUBS_MISMATCH" -> "Different wallet public keys are already registered for this token"
        "INSUFFICIENT_FUNDS" -> "The wallet has insufficient funds for this operation"
        "INVALID_ADDRESS" -> "The destination address was rejected by the gateway"
        "WALLET_REQUEST_REJECTED" -> "The gateway wallet rejected the request"
        "PSBT_REJECTED", "PSBT_MISMATCH" -> "The gateway rejected the locally signed transaction"
        "OP_NOT_FOUND" -> "The prepared gateway operation was not found"
        else -> "The gateway rejected the request"
    }

    /** Prevents journal mutation and HTTP when the provisional profile is not opted into. */
    private fun requireUnverifiedPsbtProfileEnabled(): Unit {
        if (!configuration.allowUnverifiedPsbtProfile) {
            throw SdkException.Unsupported(UNVERIFIED_PSBT_PROFILE_FEATURE)
        }
    }

    /**
     * The gateway requires distinct prepare/complete wire keys. Preserve the original domain
     * and derivation across upgrades: changing them would lose existing idempotency identities.
     * The public logical key remains authoritative; durable prepare persistence is still a gate.
     */
    private fun provisionalStagedIdempotencyKey(key: IdempotencyKey, stage: String): String {
        if (stage != "prepare" && stage != "complete") {
            throw SdkException.InternalInvariantViolation("invalid gateway idempotency stage")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(PROVISIONAL_IDEMPOTENCY_DOMAIN, stage, key.value).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireWholeGatewayFeeRate(feeRate: FeeRate): Long {
        if (feeRate.milliSatsPerVbyte % 1_000L != 0L) {
            throw SdkException.InvalidArgument(
                "feeRate",
                "PR #174 gateway accepts whole sat/vB values only",
            )
        }
        val value = feeRate.milliSatsPerVbyte / 1_000L
        if (value !in 1L..MAX_GATEWAY_FEE_RATE) {
            throw SdkException.InvalidArgument(
                "feeRate",
                "gateway accepts 1 to $MAX_GATEWAY_FEE_RATE sat/vB",
            )
        }
        return value
    }

    private fun requireGatewayXpub(field: String, value: String): Unit {
        if (value.length !in 100..120 || value.any { !it.isLetterOrDigit() }) {
            throw SdkException.InvalidArgument(
                field,
                "must match the gateway's 100-to-120 character Base58 account-xpub format",
            )
        }
    }

    private fun requireCanonicalBase64(
        value: String,
        field: String,
        operationId: OperationId?,
        correlationId: String? = null,
    ): Unit {
        val decoded = value.decodeBase64()
        if (value.length < MIN_PSBT_BASE64_CHARACTERS || decoded == null ||
            decoded.size == 0 || decoded.base64() != value
        ) {
            throw SdkException.BackendProtocolMismatch(
                "gateway $field is not canonical Base64",
                operationId,
                correlationId,
            )
        }
    }

    private data class SessionBinding(
        val session: BackendSession,
        val userId: String,
        val walletId: WalletId,
        val network: Network,
    )

    private data class GatewayJsonResponse(
        val body: JsonObject,
        val correlationId: String?,
    )

    private data class GatewayRawResponse(
        val statusCode: Int,
        val body: String,
        val isJson: Boolean,
        val correlationId: String?,
    )

    private companion object {
        val GATEWAY_API_VERSION: Int = 1
        val PROVISIONAL_IDEMPOTENCY_DOMAIN: String = "utexo.gateway.idempotency.v1"
        val UNVERIFIED_PSBT_PROFILE_FEATURE: String =
            "sendBtc requires an explicitly enabled, unverified gateway PSBT profile"
        val USER_AGENT: String = "rgb-sdk-kotlin-light/0.1"
        val JSON_MEDIA_TYPE_VALUE: String = "application/json"
        val GATEWAY_REQUEST_BODY_LIMIT_BYTES: Int = 64 * 1_024
        val MAX_PREPARED_PSBT_CHARACTERS: Int = 48 * 1_024
        val MAX_RESPONSE_BYTES: Int = 1_500_000
        val MAX_GATEWAY_FEE_RATE: Long = 1_000L
        val MIN_PSBT_BASE64_CHARACTERS: Int = 20

        val JSON = Json {
            isLenient = false
            allowSpecialFloatingPointValues = false
            ignoreUnknownKeys = false
        }
        val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_VALUE.toMediaType()
        val EMPTY_REQUEST_BODY = ByteArray(0).toRequestBody(null)
        val FINGERPRINT = Regex("^[0-9a-f]{8}$")
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        val BACKEND_CODE = Regex("^[A-Z0-9_]{1,128}$")

        fun newHttpClient(configuration: RgbLightningNodeGatewayConfiguration): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(configuration.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(configuration.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(configuration.callTimeoutMillis, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()

        fun parseJsonObject(
            body: String,
            operationId: OperationId?,
            correlationId: String?,
        ): JsonObject = try {
            com.utexo.rgb.sdk.internal.BoundedJson.check(body)
            JSON.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw SdkException.BackendProtocolMismatch(
                "gateway returned malformed JSON",
                operationId,
                correlationId,
            )
        }

        fun safeCorrelationId(value: String?): String? = value?.takeIf {
            it.length in 1..128 && it.all(::isAsciiProtocolIdentifierCharacter)
        }

        fun isAsciiProtocolIdentifierCharacter(character: Char): Boolean =
            character in 'a'..'z' || character in 'A'..'Z' ||
                character in '0'..'9' || character in "-_.:"
    }
}

private fun ResponseBody.readBounded(limit: Int, operationId: OperationId?): ByteArray {
    val declared = contentLength()
    if (declared > limit.toLong()) {
        throw SdkException.BackendProtocolMismatch(
            "gateway response exceeds the local size limit",
            operationId,
        )
    }
    return byteStream().use { input ->
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                throw SdkException.BackendProtocolMismatch(
                    "gateway response exceeds the local size limit",
                    operationId,
                )
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private fun JsonElement.requireObject(path: String): JsonObject = this as? JsonObject
    ?: throw SdkException.BackendProtocolMismatch("gateway field $path must be an object")

private fun JsonObject.requireKeys(path: String, required: Set<String>): Unit {
    if (!keys.containsAll(required)) {
        throw SdkException.BackendProtocolMismatch(
            "gateway object $path is missing required fields",
        )
    }
}

private fun JsonObject.requiredObject(field: String): JsonObject =
    get(field)?.requireObject(field)
        ?: throw SdkException.BackendProtocolMismatch("gateway response is missing $field")

private fun JsonObject.requiredArray(field: String): JsonArray = get(field) as? JsonArray
    ?: throw SdkException.BackendProtocolMismatch("gateway field $field must be an array")

private fun JsonObject.requiredBoolean(field: String): Boolean {
    val primitive = get(field) as? JsonPrimitive
        ?: throw SdkException.BackendProtocolMismatch("gateway field $field must be a boolean")
    if (primitive.isString || primitive.content !in setOf("true", "false")) {
        throw SdkException.BackendProtocolMismatch("gateway field $field must be a boolean")
    }
    return primitive.content == "true"
}

private fun JsonObject.requiredString(field: String, maximumBytes: Int): String {
    val primitive = get(field) as? JsonPrimitive
        ?: throw SdkException.BackendProtocolMismatch("gateway field $field must be a string")
    if (!primitive.isString) {
        throw SdkException.BackendProtocolMismatch("gateway field $field must be a string")
    }
    val value = primitive.content
    if (value.isEmpty() || value != value.trim() || value.toByteArray(Charsets.UTF_8).size > maximumBytes) {
        throw SdkException.BackendProtocolMismatch("gateway field $field is not bounded canonical text")
    }
    return value
}

private fun JsonObject.optionalString(field: String, maximumBytes: Int): String? {
    val element = get(field) ?: throw SdkException.BackendProtocolMismatch(
        "gateway response is missing $field",
    )
    if (element === JsonNull) return null
    return JsonObject(mapOf(field to element)).requiredString(field, maximumBytes)
}

/** Display metadata is bounded but must not be trimmed or normalized. */
private fun JsonObject.requiredDisplayString(field: String, maximumBytes: Int): String {
    val primitive = get(field) as? JsonPrimitive
        ?: throw SdkException.BackendProtocolMismatch("gateway field $field must be a string")
    if (!primitive.isString || primitive.content.isEmpty() || primitive.content.toByteArray(Charsets.UTF_8).size > maximumBytes) {
        throw SdkException.BackendProtocolMismatch("gateway display field $field is outside the supported bounds")
    }
    return primitive.content
}

private fun JsonObject.requiredLong(
    field: String,
    minimum: Long = Long.MIN_VALUE,
    maximum: Long = Long.MAX_VALUE,
): Long {
    val primitive = get(field) as? JsonPrimitive
        ?: throw SdkException.BackendProtocolMismatch("gateway field $field must be an integer")
    if (primitive.isString || !JSON_INTEGER.matches(primitive.content)) {
        throw SdkException.BackendProtocolMismatch("gateway field $field must be an integer")
    }
    val value = primitive.content.toLongOrNull()
        ?: throw SdkException.BackendProtocolMismatch("gateway field $field is outside the integer range")
    if (value !in minimum..maximum) {
        throw SdkException.BackendProtocolMismatch("gateway field $field is outside the accepted range")
    }
    return value
}

private fun JsonObject.toBtcBalance(correlationId: String?): BtcBalance {
    requireKeys("btc", setOf("vanilla", "colored"))
    return BtcBalance(
        vanilla = requiredObject("vanilla").toBitcoinBalance("btc.vanilla", correlationId),
        colored = requiredObject("colored").toBitcoinBalance("btc.colored", correlationId),
    )
}

private fun JsonObject.toBitcoinBalance(path: String, correlationId: String?): Balance {
    requireKeys(path, setOf("settled", "future", "spendable"))
    return try {
        Balance(
            settled = Satoshis.of(requiredLong("settled", 0L, Satoshis.MAX_VALUE)),
            future = Satoshis.of(requiredLong("future", 0L, Satoshis.MAX_VALUE)),
            spendable = Satoshis.of(requiredLong("spendable", 0L, Satoshis.MAX_VALUE)),
        )
    } catch (_: SdkException.InvalidArgument) {
        throw SdkException.BackendProtocolMismatch(
            "gateway returned an invalid Bitcoin balance",
            correlationId = correlationId,
        )
    }
}

private fun JsonObject.toAssetBalance(correlationId: String?): AssetBalance {
    requireKeys("asset.balance", setOf("settled", "future", "spendable"))
    fun amount(field: String): AssetAmount {
        val value = requiredLong(field, 0L, MAX_SAFE_JSON_INTEGER_VALUE)
        return AssetAmount.parse(value.toString())
    }
    return try {
        AssetBalance(
            settled = amount("settled"),
            future = amount("future"),
            spendable = amount("spendable"),
        )
    } catch (_: SdkException.InvalidArgument) {
        throw SdkException.BackendProtocolMismatch(
            "gateway returned an invalid asset balance",
            correlationId = correlationId,
        )
    }
}

private val JSON_INTEGER: Regex = Regex("^-?(?:0|[1-9][0-9]*)$")
private const val MAX_SAFE_JSON_INTEGER_VALUE: Long = 9_007_199_254_740_991L

private inline fun <T> gatewayModel(correlationId: String?, block: () -> T): T = try {
    block()
} catch (_: SdkException.InvalidArgument) {
    throw SdkException.BackendProtocolMismatch("gateway returned an invalid wallet value", correlationId = correlationId)
}

private fun JsonObject.nullableLong(field: String): Long? =
    if (!containsKey(field) || get(field) == JsonNull) null
    else requiredLong(field, 0, MAX_SAFE_JSON_INTEGER_VALUE)

private fun JsonObject.nullableBoolean(field: String): Boolean? =
    if (!containsKey(field) || get(field) == JsonNull) null else requiredBoolean(field)

private fun JsonObject.nullableAmount(field: String): AssetAmount? = nullableLong(field)?.let(AssetAmount::of)

private fun JsonObject.gatewayTxid(field: String): TransactionId {
    val text = requiredString(field, 64)
    if (!Regex("[0-9a-f]{64}").matches(text)) {
        throw SdkException.BackendProtocolMismatch("gateway returned a noncanonical transaction identifier")
    }
    return TransactionId.of(text)
}

private fun JsonObject.gatewayAssignment(): Assignment {
    requireKeys("assignment", setOf("kind", "amount"))
    val kind = AssignmentType.fromWire(requiredString("kind", 64))
    val amount = nullableAmount("amount")
    if ((kind == AssignmentType.FUNGIBLE || kind == AssignmentType.INFLATION_RIGHT) && amount == null) {
        throw SdkException.BackendProtocolMismatch("gateway numeric assignment is missing its amount")
    }
    if ((kind == AssignmentType.ANY || kind == AssignmentType.REPLACE_RIGHT || kind == AssignmentType.NON_FUNGIBLE) && amount != null) {
        throw SdkException.BackendProtocolMismatch("gateway unit assignment has an unexpected amount")
    }
    return Assignment(kind, amount)
}

private fun JsonObject.nullableOutpoint(field: String): Outpoint? {
    if (!containsKey(field) || get(field) == JsonNull) return null
    val item = requiredObject(field)
    return Outpoint(item.gatewayTxid("txid"), item.requiredLong("vout", 0, Int.MAX_VALUE.toLong()).toInt())
}

private fun JsonObject.nullableTransports(field: String): List<TransferTransportEndpoint>? {
    if (!containsKey(field) || get(field) == JsonNull) return null
    return requiredArray(field).map { element ->
        val item = element.requireObject("transport endpoint")
        TransferTransportEndpoint(item.requiredString("endpoint", 2_048),
            item.requiredString("transportType", 64), item.requiredBoolean("used"))
    }
}

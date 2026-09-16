package com.utexo.rgb.sdk

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
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
     * Enables the provisional PR #174 send-BTC prepare/complete mapping.
     *
     * Keep this false in production until UTEXO freezes the exact PSBT profile,
     * publishes canonical fixtures, and resolves gateway idempotency semantics.
     * Read-only gateway operations do not require this opt-in.
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
 * BTC send prepare/complete only when
 * [RgbLightningNodeGatewayConfiguration.allowUnverifiedPsbtProfile] is
 * explicitly enabled. Address, UTXO, transfer, receive, sync/refresh,
 * issuance, RGB send, and create-UTXO calls retain the
 * fail-closed defaults on [WalletBackend]. The gateway currently has no
 * operation-status route, so [getOperation] also fails closed.
 */
@WalletInfrastructureApi
public class RgbLightningNodeGatewayBackend private constructor(
    private val configuration: RgbLightningNodeGatewayConfiguration,
    private val httpClient: OkHttpClient,
    private val nowMillis: () -> Long,
) : WalletBackend {
    private val sessionGate: Mutex = Mutex()

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
    )

    public override suspend fun preflightOperation(operation: OperationKind): Unit {
        when (operation) {
            OperationKind.SEND_BTC -> requireUnverifiedPsbtProfileEnabled()
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
            asset.requiredString("name", 256)
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
        if (request.request.skipSync) {
            throw SdkException.Unsupported("PR #174 gateway sendBtc skipSync")
        }
        val feeRate = requireWholeGatewayFeeRate(request.request.feeRate)
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
        val expiresAtMillis = response.body.requiredLong("expiresAt", minimum = 1_000L)
        val receivedAtMillis = nowMillis()
        val maximumTtlMillis = configuration.maximumPreparationTtlSeconds * 1_000L
        if (receivedAtMillis < 0L || expiresAtMillis <= receivedAtMillis ||
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

    override suspend fun submitSignedTransaction(
        request: SignedTransactionSubmission,
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
            path = "/v1/onchain/send-btc/complete",
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
            response.body.requireKeys("completedSendBtc", setOf("txid"))
            val txid = TransactionId.of(response.body.requiredString("txid", 64))
            if (txid != request.validation.unsignedTxid) {
                throw SdkException.OperationAmbiguous(request.operationId)
            }
            BackendOperationResult.SendBtc(
                operationId = request.operationId,
                txid = txid,
            )
        } catch (ambiguous: SdkException.OperationAmbiguous) {
            throw ambiguous
        } catch (_: SdkException) {
            // A syntactically successful completion can mean the transaction
            // was broadcast even when its response cannot be trusted.
            throw SdkException.OperationAmbiguous(request.operationId)
        }
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
    ): GatewayJsonResponse {
        if ((idempotencyKey == null) != (logicalIdempotencyKey == null)) {
            throw SdkException.InternalInvariantViolation(
                "gateway wire and logical idempotency keys must be supplied together",
            )
        }
        val url = configuration.resolvedBaseUrl().resolve(path.removePrefix("/"))
            ?: throw SdkException.InternalInvariantViolation("gateway route could not be resolved")
        val encodedBody = body?.toString()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .header("Authorization", "Bearer ${configuration.authorizationValue()}")
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
            statusCode in 300..399 && operationId != null ->
                SdkException.OperationAmbiguous(operationId)
            statusCode == 410 && backendCode == "OP_EXPIRED" && operationId != null ->
                SdkException.OperationExpired(operationId)
            statusCode == 409 && backendCode == "OP_KIND_MISMATCH" ->
                SdkException.BackendProtocolMismatch(
                    "gateway operation kind does not match its completion route",
                    operationId,
                    correlationId,
                )
            statusCode == 409 && backendCode == "OP_ALREADY_COMPLETED" && operationId != null ->
                SdkException.OperationAmbiguous(operationId)
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
            statusCode == 409 && operationId != null ->
                SdkException.OperationAmbiguous(operationId)
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
     * Temporary wire mapping only. The public logical key remains authoritative;
     * this derivation must not be treated as a frozen backend contract.
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

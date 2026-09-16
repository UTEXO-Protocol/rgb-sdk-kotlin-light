package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.LocalSigningEngine
import com.utexo.rgb.sdk.internal.LocalSigningEngineFactory
import com.utexo.rgb.sdk.internal.OperationCoordinator
import com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory
import com.utexo.rgb.sdk.internal.backendCall
import kotlinx.coroutines.sync.Mutex

/**
 * High-level wallet facade matching the semantic surface of `rgb-sdk-rn`.
 *
 * Stateful wallet/RGB work is delegated to [WalletBackend]. Bitcoin key
 * derivation, transaction-policy validation, and signing remain local. No
 * method accepts or returns a PSBT.
 */
public class UTEXOWallet internal constructor(
    public val configuration: WalletConfiguration,
    private val backend: WalletBackend,
    private val keyProvider: WalletKeyProvider,
    private val operationJournal: OperationJournal,
    private val signingEngineFactory: LocalSigningEngineFactory,
    epochSecondsSource: EpochSecondsSource,
) {
    private enum class Lifecycle {
        NEW,
        INITIALIZED,
        UNLOCKED,
        SHUTDOWN,
        DISPOSED,
    }

    private val gate: Mutex = Mutex()
    private val operationCoordinator: OperationCoordinator = OperationCoordinator(
        configuration = configuration,
        backend = backend,
        journal = operationJournal,
        now = epochSecondsSource,
    )

    @Volatile
    private var lifecycle: Lifecycle = Lifecycle.NEW
    private var session: BackendSession? = null
    private var signingEngine: LocalSigningEngine? = null

    @Volatile
    private var reportedCapabilities: WalletCapabilities = WalletCapabilities(
        localKeyDerivation = false,
        localTransactionValidation = false,
        localTransactionSigning = false,
        localRgbInvoiceParsing = false,
        localRgbValidation = false,
        localSigningOperations = emptySet(),
        derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
        signingProfile = WalletCapabilities.SIGNING_PROFILE_V1,
    )

    /**
     * Production callers must supply a durable [OperationJournal]. An
     * in-memory journal cannot safely recover a payment after process death.
     */
    @WalletInfrastructureApi
    public constructor(
        configuration: WalletConfiguration,
        backend: WalletBackend,
        keyProvider: WalletKeyProvider,
        operationJournal: OperationJournal,
    ) : this(
        configuration = configuration,
        backend = backend,
        keyProvider = keyProvider,
        operationJournal = operationJournal,
        signingEngineFactory = UniFfiLocalSigningEngineFactory,
        epochSecondsSource = EpochSecondsSource { System.currentTimeMillis() / 1_000L },
    )

    /** Effective local capabilities. Signing capabilities become true only after unlock. */
    public val capabilities: WalletCapabilities
        get() = reportedCapabilities

    /** Idempotently initializes the remote wallet session. */
    public suspend fun init(): Unit = exclusive { initLocked() }

    /** RN compatibility alias for [init]. */
    public suspend fun initialize(): Unit = init()

    /** Unlocks with no password; useful when the key provider uses OS authentication. */
    public suspend fun unlock(): Unit {
        val params = WalletUnlockParams.none()
        try {
            unlock(params)
        } finally {
            params.close()
        }
    }

    /** Loads key material locally and registers watch-only identity with the backend. */
    public suspend fun unlock(params: WalletUnlockParams): Unit = exclusive {
        unlockLocked(params)
    }

    /** Restart-compatible RN alias. Supplying params also unlocks the wallet. */
    public suspend fun reinit(params: WalletUnlockParams? = null): Unit = exclusive {
        initLocked()
        if (params != null) unlockLocked(params)
    }

    /** Canonical spelling of [reinit], retained alongside the RN-compatible alias. */
    public suspend fun reinitialize(params: WalletUnlockParams? = null): Unit = reinit(params)

    /** Stops the session but preserves durable wallet data and allows [reinit]. */
    public suspend fun shutdown(): Unit = exclusive { shutdownLocked(dispose = false) }

    /** Idempotently closes this instance. This does not delete wallet or backend data. */
    public suspend fun destroy(): Unit = exclusive { shutdownLocked(dispose = true) }

    /** RN compatibility alias for [destroy]. */
    public suspend fun dispose(): Unit = destroy()

    public fun getNetwork(): Network = configuration.network

    public fun isDisposed(): Boolean = lifecycle == Lifecycle.DISPOSED

    public suspend fun getBtcBalance(): BtcBalance = withUnlocked("getBtcBalance") { context, _ ->
        backendCall { backend.getBtcBalance(context) }
    }

    public suspend fun getAddress(): BitcoinAddress = reserveAddress(rotate = false)

    public suspend fun rotateVanillaAddress(): BitcoinAddress = reserveAddress(rotate = true)

    public suspend fun listUnspents(): List<Unspent> = withUnlocked("listUnspents") { context, _ ->
        backendCall { backend.listUnspents(context) }.toList()
    }

    public suspend fun estimateFeeRate(blocks: BlockCount): FeeEstimate =
        withUnlocked("estimateFeeRate") { context, _ ->
            backendCall { backend.estimateFeeRate(context, blocks) }
        }

    public suspend fun sendBtc(request: SendBtcRequest): TransactionId =
        withUnlocked("sendBtc") { context, signer ->
            requireLocalSigning(signer, OperationKind.SEND_BTC)
            operationCoordinator.sendBtc(context, signer, request)
        }

    public suspend fun createUtxos(request: CreateUtxosRequest = CreateUtxosRequest()): CreateUtxosResult =
        withUnlocked("createUtxos") { context, signer ->
            requireLocalSigning(signer, OperationKind.CREATE_UTXOS)
            operationCoordinator.createUtxos(context, signer, request)
        }

    public suspend fun listAssets(): ListAssets = withUnlocked("listAssets") { context, _ ->
        backendCall { backend.listAssets(context) }
    }

    public suspend fun getAssetBalance(assetId: AssetId): AssetBalance =
        withUnlocked("getAssetBalance") { context, _ ->
            backendCall { backend.getAssetBalance(context, assetId) }
        }

    public suspend fun issueAssetNia(request: IssueAssetNiaRequest): AssetNia =
        withUnlocked("issueAssetNia") { context, _ ->
            backendCall { backend.issueAssetNia(context, request) }
        }

    public suspend fun issueAssetIfa(request: IssueAssetIfaRequest): AssetIfa =
        withUnlocked("issueAssetIfa") { context, _ ->
            backendCall { backend.issueAssetIfa(context, request) }
        }

    public suspend fun blindReceive(request: InvoiceRequest): InvoiceReceiveData =
        withUnlocked("blindReceive") { context, signer ->
            requireLocalRgbInvoiceParsing(signer)
            val response = backendCall { backend.blindReceive(context, request) }
            val decoded = signer.decodeRgbInvoice(response.invoice)
            verifyReceivedInvoice(request, response, decoded)
            response
        }

    public suspend fun witnessReceive(request: InvoiceRequest): InvoiceReceiveData =
        withUnlocked("witnessReceive") { context, signer ->
            requireLocalRgbInvoiceParsing(signer)
            val response = backendCall { backend.witnessReceive(context, request) }
            val decoded = signer.decodeRgbInvoice(response.invoice)
            verifyReceivedInvoice(request, response, decoded)
            response
        }

    public suspend fun decodeRgbInvoice(invoice: RgbInvoice): InvoiceData =
        withUnlocked("decodeRgbInvoice") { _, signer ->
            requireLocalRgbInvoiceParsing(signer)
            signer.decodeRgbInvoice(invoice).also { verifyInvoiceNetwork(it) }
        }

    /** RN acronym-compatible alias; prefer [decodeRgbInvoice] in new Kotlin code. */
    public suspend fun decodeRGBInvoice(invoice: RgbInvoice): InvoiceData = decodeRgbInvoice(invoice)

    public suspend fun onchainReceive(request: OnchainReceiveRequest): OnchainReceiveResponse =
        withUnlocked("onchainReceive") { context, signer ->
            requireLocalRgbInvoiceParsing(signer)
            val response = backendCall { backend.onchainReceive(context, request) }
            val decoded = signer.decodeRgbInvoice(response.invoice)
            verifyOnchainReceive(request, response, decoded)
            response
        }

    public suspend fun onchainSend(request: OnchainSendRequest): OnchainSendResponse =
        withUnlocked("onchainSend") { context, signer ->
            requireLocalSigning(signer, OperationKind.ONCHAIN_SEND)
            requireLocalRgbInvoiceParsing(signer)
            verifyOutgoingInvoice(request, signer.decodeRgbInvoice(request.invoice))
            operationCoordinator.onchainSend(context, signer, request)
        }

    /**
     * RN-parity inflation entry point. It intentionally fails closed until the
     * native core and backend expose an exact inflation transition contract.
     */
    public suspend fun inflate(request: InflateRequest): TransactionId =
        withUnlocked("inflate") { _, signer ->
            @Suppress("UNUSED_VARIABLE")
            val boundedRequest = request
            requireLocalSigning(signer, OperationKind.INFLATE)
            throw SdkException.Unsupported(
                "backend-bound inflation preparation and RGB transition validation",
            )
        }

    public suspend fun listTransactions(): List<Transaction> =
        withUnlocked("listTransactions") { context, _ ->
            backendCall { backend.listTransactions(context) }.toList()
        }

    public suspend fun listTransactionsByTxid(
        txid: TransactionId,
        skipSync: Boolean = false,
    ): List<Transaction> = withUnlocked("listTransactionsByTxid") { context, _ ->
        backendCall { backend.listTransactionsByTxid(context, txid, skipSync) }.toList()
    }

    public suspend fun listTransfers(assetId: AssetId? = null): List<Transfer> =
        withUnlocked("listTransfers") { context, _ ->
            backendCall { backend.listTransfers(context, assetId) }.toList()
        }

    public suspend fun listTransfersByTxid(txid: TransactionId): List<Transfer> =
        withUnlocked("listTransfersByTxid") { context, _ ->
            backendCall { backend.listTransfersByTxid(context, txid) }.toList()
        }

    public suspend fun listOnchainTransfers(assetId: AssetId? = null): List<Transfer> =
        listTransfers(assetId)

    public suspend fun failTransfers(request: FailTransfersRequest): FailTransfersResult =
        withUnlocked("failTransfers") { context, _ ->
            backendCall { backend.failTransfers(context, request) }
        }

    public suspend fun refreshWallet(): SyncResult = withUnlocked("refreshWallet") { context, _ ->
        backendCall { backend.refreshWallet(context) }
    }

    public suspend fun syncWallet(): SyncResult = withUnlocked("syncWallet") { context, _ ->
        backendCall { backend.syncWallet(context) }
    }

    private suspend fun reserveAddress(rotate: Boolean): BitcoinAddress =
        withUnlocked(if (rotate) "rotateVanillaAddress" else "getAddress") { context, signer ->
            val reservation = backendCall {
                backend.reserveAddress(
                    ReserveAddressRequest(
                        context = context,
                        account = AccountKind.VANILLA,
                        rotate = rotate,
                    ),
                )
            }
            if (reservation.account != AccountKind.VANILLA ||
                reservation.keychain != Keychain.EXTERNAL
            ) {
                throw SdkException.BackendProtocolMismatch(
                    "backend reserved an address outside the vanilla external branch",
                )
            }
            signer.verifyReservedAddress(reservation)
            reservation.address
        }

    private suspend fun initLocked(): Unit {
        when (lifecycle) {
            Lifecycle.DISPOSED -> throw SdkException.WalletDisposed()
            Lifecycle.INITIALIZED, Lifecycle.UNLOCKED -> return
            Lifecycle.NEW, Lifecycle.SHUTDOWN -> Unit
        }
        val opened = backendCall {
            backend.initialize(
                BackendInitializeRequest(
                    walletId = configuration.walletId,
                    network = configuration.network,
                    minimumApiVersion = configuration.minimumBackendApiVersion,
                    maximumApiVersion = configuration.maximumBackendApiVersion,
                ),
            )
        }
        if (opened.network != configuration.network) {
            runCatching { backend.shutdown(opened) }
            throw SdkException.WrongNetwork(configuration.network, opened.network)
        }
        if (opened.apiVersion !in
            configuration.minimumBackendApiVersion..configuration.maximumBackendApiVersion
        ) {
            runCatching { backend.shutdown(opened) }
            throw SdkException.BackendProtocolMismatch(
                reason = "negotiated API version is outside the configured range",
                correlationId = opened.correlationId,
            )
        }
        session = opened
        lifecycle = Lifecycle.INITIALIZED
    }

    private suspend fun unlockLocked(params: WalletUnlockParams): Unit {
        when (lifecycle) {
            Lifecycle.UNLOCKED -> return
            Lifecycle.DISPOSED -> throw SdkException.WalletDisposed()
            Lifecycle.NEW, Lifecycle.SHUTDOWN -> throw SdkException.InvalidState(
                "unlock",
                lifecycle.name.lowercase(),
            )
            Lifecycle.INITIALIZED -> Unit
        }
        val activeSession = session
            ?: throw SdkException.InternalInvariantViolation("initialized wallet has no session")
        val secret = try {
            keyProvider.loadSeed(params)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (sdk: SdkException) {
            throw sdk
        } catch (_: Exception) {
            throw SdkException.KeyAccessFailed()
        }
        val candidate = try {
            signingEngineFactory.open(configuration.network, secret)
        } finally {
            secret.close()
        }
        try {
            validateCapabilities(candidate.capabilities)
            val identity = candidate.identity()
            validateIdentity(identity)
            backendCall {
                backend.registerWallet(
                    RegisterWalletRequest(
                        context = BackendContext(
                            session = activeSession,
                            walletId = configuration.walletId,
                            network = configuration.network,
                        ),
                        identity = identity,
                    ),
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            runCatching { candidate.close() }
            throw cancellation
        } catch (sdk: SdkException) {
            runCatching { candidate.close() }
            throw sdk
        } catch (_: Exception) {
            runCatching { candidate.close() }
            throw SdkException.InternalInvariantViolation("wallet unlock failed unexpectedly")
        }
        signingEngine = candidate
        reportedCapabilities = candidate.capabilities
        lifecycle = Lifecycle.UNLOCKED
    }

    private suspend fun shutdownLocked(dispose: Boolean): Unit {
        if (lifecycle == Lifecycle.DISPOSED) return
        var failure: Exception? = null
        val engine = signingEngine
        signingEngine = null
        if (engine != null) {
            try {
                engine.close()
            } catch (_: Exception) {
                failure = SdkException.InternalInvariantViolation("local signer cleanup failed")
            }
        }
        val activeSession = session
        session = null
        if (activeSession != null) {
            try {
                backendCall { backend.shutdown(activeSession) }
            } catch (exception: Exception) {
                failure = failure ?: exception
            }
        }
        reportedCapabilities = reportedCapabilities.copy(
            localKeyDerivation = false,
            localTransactionValidation = false,
            localTransactionSigning = false,
            localRgbInvoiceParsing = false,
            localRgbValidation = false,
            localSigningOperations = emptySet(),
        )
        lifecycle = if (dispose) Lifecycle.DISPOSED else Lifecycle.SHUTDOWN
        failure?.let { throw it }
    }

    private fun validateCapabilities(capabilities: WalletCapabilities): Unit {
        if (capabilities.derivationProfile != WalletCapabilities.DERIVATION_PROFILE_V1) {
            throw SdkException.BackendProtocolMismatch("local derivation profile is incompatible")
        }
        if (capabilities.signingProfile != WalletCapabilities.SIGNING_PROFILE_V1) {
            throw SdkException.BackendProtocolMismatch("local signing profile is incompatible")
        }
    }

    private fun validateIdentity(identity: WalletIdentity): Unit {
        if (identity.network != configuration.network) {
            throw SdkException.WrongNetwork(configuration.network, identity.network)
        }
        if (identity.chainNetwork != configuration.network.chainNetwork) {
            throw SdkException.BackendProtocolMismatch("local identity has the wrong chain network")
        }
        if (identity.derivationProfile != WalletCapabilities.DERIVATION_PROFILE_V1) {
            throw SdkException.BackendProtocolMismatch("local identity has an unsupported derivation profile")
        }
        requireBoundedText("masterFingerprint", identity.masterFingerprint, 16)
        requireBoundedText("xpub", identity.xpub, 256)
        requireBoundedText("accountXpubVanilla", identity.accountXpubVanilla, 256)
        requireBoundedText("accountXpubColored", identity.accountXpubColored, 256)
    }

    private fun requireLocalSigning(signer: LocalSigningEngine, operation: OperationKind): Unit {
        if (!signer.capabilities.localTransactionValidation ||
            !signer.capabilities.localTransactionSigning
        ) {
            throw SdkException.Unsupported("local transaction validation and signing")
        }
        if (operation !in signer.capabilities.localSigningOperations) {
            throw SdkException.Unsupported(
                "complete local ${operation.wireValue} intent validation and signing",
            )
        }
    }

    private fun requireLocalRgbInvoiceParsing(signer: LocalSigningEngine): Unit {
        if (!signer.capabilities.localRgbInvoiceParsing) {
            throw SdkException.Unsupported("local RGB invoice parsing")
        }
    }

    private fun verifyReceivedInvoice(
        request: InvoiceRequest,
        response: InvoiceReceiveData,
        decoded: InvoiceData,
    ): Unit {
        verifyInvoiceIntent(request, decoded)
        if (decoded.recipientId != response.recipientId) {
            throw SdkException.BackendProtocolMismatch("received invoice recipient ID does not match")
        }
        if (decoded.expirationTimestamp != response.expirationTimestamp) {
            throw SdkException.BackendProtocolMismatch("received invoice expiration does not match")
        }
    }

    private fun verifyOnchainReceive(
        request: OnchainReceiveRequest,
        response: OnchainReceiveResponse,
        decoded: InvoiceData,
    ): Unit {
        verifyInvoiceIntent(request.asInvoiceRequest(), decoded)
        if (response.recipientId != null && decoded.recipientId != response.recipientId) {
            throw SdkException.BackendProtocolMismatch("onchain invoice recipient ID does not match")
        }
        if (response.expirationTimestamp != null &&
            decoded.expirationTimestamp != response.expirationTimestamp
        ) {
            throw SdkException.BackendProtocolMismatch("onchain invoice expiration does not match")
        }
    }

    private fun verifyOutgoingInvoice(request: OnchainSendRequest, decoded: InvoiceData): Unit {
        verifyInvoiceNetwork(decoded)
        if (request.assetId != null && request.assetId != decoded.assetId) {
            throw SdkException.PolicyRejected("RGB invoice asset does not match the caller request")
        }
        decoded.assignment.amount?.let { invoiceAmount ->
            if (invoiceAmount != request.amount) {
                throw SdkException.PolicyRejected("RGB invoice amount does not match the caller request")
            }
        }
    }

    private fun verifyInvoiceIntent(request: InvoiceRequest, decoded: InvoiceData): Unit {
        verifyInvoiceNetwork(decoded)
        if (request.assetId != null && request.assetId != decoded.assetId) {
            throw SdkException.BackendProtocolMismatch("invoice asset does not match the receive request")
        }
        if (request.amount != null && request.amount != decoded.assignment.amount) {
            throw SdkException.BackendProtocolMismatch("invoice amount does not match the receive request")
        }
    }

    private fun verifyInvoiceNetwork(decoded: InvoiceData): Unit {
        if (decoded.network != configuration.network) {
            throw SdkException.WrongNetwork(configuration.network, decoded.network)
        }
    }

    private suspend fun <T> withUnlocked(
        operation: String,
        block: suspend (BackendContext, LocalSigningEngine) -> T,
    ): T = exclusive {
        when (lifecycle) {
            Lifecycle.DISPOSED -> throw SdkException.WalletDisposed()
            Lifecycle.UNLOCKED -> Unit
            Lifecycle.NEW, Lifecycle.INITIALIZED, Lifecycle.SHUTDOWN -> throw SdkException.WalletLocked()
        }
        val activeSession = session
            ?: throw SdkException.InternalInvariantViolation("unlocked wallet has no backend session")
        val signer = signingEngine
            ?: throw SdkException.InternalInvariantViolation("unlocked wallet has no local signer")
        try {
            block(
                BackendContext(
                    session = activeSession,
                    walletId = configuration.walletId,
                    network = configuration.network,
                ),
                signer,
            )
        } catch (exception: SdkException) {
            throw exception
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw SdkException.InternalInvariantViolation("$operation failed unexpectedly")
        }
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        gate.lock()
        return try {
            block()
        } finally {
            gate.unlock()
        }
    }
}

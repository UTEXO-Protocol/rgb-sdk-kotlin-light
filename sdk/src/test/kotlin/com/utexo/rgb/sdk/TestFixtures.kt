package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.LocalSigningEngine
import com.utexo.rgb.sdk.internal.LocalSigningEngineFactory
import com.utexo.rgb.sdk.internal.LocalSigningResult
import java.io.IOException

internal const val TEST_NOW = 1_000L
internal val TEST_TXID: TransactionId = TransactionId.of("a".repeat(64))
internal val TEST_OPERATION_ID: OperationId = OperationId.of("operation-1")
internal val TEST_IDEMPOTENCY_KEY: IdempotencyKey = IdempotencyKey.of("idempotency-1")
internal val TEST_ADDRESS: BitcoinAddress =
    BitcoinAddress.of("tb1ptestaddress000000000000000000000000000000000000000000000000000")

internal fun testConfiguration(): WalletConfiguration = WalletConfiguration(
    walletId = WalletId.of("wallet-1"),
    network = Network.SIGNET,
)

internal fun testIdentity(network: Network = Network.SIGNET): WalletIdentity = WalletIdentity(
    network = network,
    chainNetwork = network.chainNetwork,
    derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
    masterFingerprint = "deadbeef",
    xpub = "tpub-root",
    accountXpubVanilla = "tpub-vanilla",
    accountXpubColored = "tpub-colored",
)

internal fun testCapabilities(
    operations: Set<OperationKind> = setOf(OperationKind.SEND_BTC),
    rgbInvoiceParsing: Boolean = false,
): WalletCapabilities = WalletCapabilities(
    localKeyDerivation = true,
    localTransactionValidation = true,
    localTransactionSigning = true,
    localRgbInvoiceParsing = rgbInvoiceParsing,
    localRgbValidation = false,
    localSigningOperations = operations,
    derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
    signingProfile = WalletCapabilities.SIGNING_PROFILE_V1,
)

internal fun testValidation(): LocalValidationReport = LocalValidationReport(
    psbtSha256 = "b".repeat(64),
    intentSha256 = "c".repeat(64),
    unsignedTxid = TEST_TXID,
    totalInput = Satoshis.of(20_000),
    totalOutput = Satoshis.of(19_000),
    fee = Satoshis.of(1_000),
    estimatedVbytes = 200,
    feeRate = FeeRate.satPerVbyte(5.0),
    externalOutput = Satoshis.of(10_000),
    change = Satoshis.of(9_000),
    inputCount = 1,
    outputCount = 2,
    opReturnCount = 0,
)

internal class FakeSigningEngine(
    override val capabilities: WalletCapabilities = testCapabilities(),
    var decodedInvoice: InvoiceData? = null,
    private val events: MutableList<String>? = null,
) : LocalSigningEngine {
    var closed: Boolean = false
    var signCalls: Int = 0
    var addressChecks: Int = 0
    val intents: MutableList<LocalOperationIntent> = mutableListOf()

    override suspend fun identity(): WalletIdentity = testIdentity()

    override suspend fun verifyReservedAddress(reservation: ReservedAddress): Unit {
        events?.add("verifyAddress")
        addressChecks += 1
    }

    override suspend fun decodeRgbInvoice(invoice: RgbInvoice): InvoiceData =
        decodedInvoice ?: throw SdkException.Unsupported("test RGB parser")

    override suspend fun validateAndSign(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent,
    ): LocalSigningResult {
        events?.add("sign")
        signCalls += 1
        intents += intent
        return LocalSigningResult(
            signedPsbtBase64 = "signed-psbt",
            validation = testValidation(),
        )
    }

    override fun close(): Unit {
        closed = true
    }
}

internal open class FakeBackend(
    private val events: MutableList<String>? = null,
) : WalletBackend {
    var initializeCalls: Int = 0
    var registrationCalls: Int = 0
    var shutdownCalls: Int = 0
    var prepareSendCalls: Int = 0
    var prepareCreateCalls: Int = 0
    var prepareOnchainCalls: Int = 0
    var submitCalls: Int = 0
    var statusCalls: Int = 0
    var overridePreparedHash: RequestHash? = null
    var createPlan: CreateUtxosOutputPlan? = null
    var submitFailure: Exception? = null
    var statusFailure: Exception? = null
    var status: BackendOperationStatus? = null
    var receiveResponse: InvoiceReceiveData? = null
    var onchainReceiveResponse: OnchainReceiveResponse? = null
    var resultTxid: TransactionId = TEST_TXID
    val submissions: MutableList<SignedTransactionSubmission> = mutableListOf()

    override suspend fun initialize(request: BackendInitializeRequest): BackendSession {
        events?.add("initialize")
        initializeCalls += 1
        return BackendSession(
            id = "session-1",
            apiVersion = 1,
            network = request.network,
        )
    }

    override suspend fun registerWallet(request: RegisterWalletRequest): Unit {
        events?.add("register")
        registrationCalls += 1
    }

    override suspend fun shutdown(session: BackendSession): Unit {
        events?.add("shutdown")
        shutdownCalls += 1
    }

    override suspend fun prepareSendBtc(request: PrepareSendBtcRequest): PreparedTransaction {
        events?.add("prepare")
        prepareSendCalls += 1
        return prepared(
            request.context,
            OperationKind.SEND_BTC,
            overridePreparedHash ?: request.context.requestHash,
        )
    }

    override suspend fun prepareCreateUtxos(
        request: PrepareCreateUtxosRequest,
    ): PreparedTransaction {
        events?.add("prepareCreate")
        prepareCreateCalls += 1
        return prepared(
            request.context,
            OperationKind.CREATE_UTXOS,
            request.context.requestHash,
            createPlan ?: CreateUtxosOutputPlan(
                listOf(
                    ExpectedWalletOutput(
                        account = AccountKind.COLORED,
                        keychain = Keychain.EXTERNAL,
                        index = 0,
                        amount = Satoshis.of(1_000),
                    ),
                ),
            ),
        )
    }

    override suspend fun prepareOnchainSend(
        request: PrepareOnchainSendRequest,
    ): PreparedTransaction {
        prepareOnchainCalls += 1
        return prepared(request.context, OperationKind.ONCHAIN_SEND, request.context.requestHash)
    }

    override suspend fun submitSignedTransaction(
        request: SignedTransactionSubmission,
    ): BackendOperationResult {
        events?.add("submit")
        submitCalls += 1
        submissions += request
        submitFailure?.let { throw it }
        return when (currentOperationKind) {
            OperationKind.SEND_BTC -> BackendOperationResult.SendBtc(request.operationId, resultTxid)
            OperationKind.CREATE_UTXOS -> BackendOperationResult.CreateUtxos(
                request.operationId,
                CreateUtxosResult(resultTxid, createPlan?.outputs?.size ?: 1),
            )
            OperationKind.ONCHAIN_SEND -> BackendOperationResult.OnchainSend(
                request.operationId,
                OnchainSendResponse(resultTxid, 7),
            )
            OperationKind.INFLATE -> error("not used")
        }
    }

    private var currentOperationKind: OperationKind = OperationKind.SEND_BTC

    override suspend fun getOperation(
        context: BackendContext,
        operationId: OperationId,
    ): BackendOperationStatus {
        statusCalls += 1
        statusFailure?.let { throw it }
        return status ?: BackendOperationStatus.Pending(operationId, "submitted")
    }

    override suspend fun blindReceive(
        context: BackendContext,
        request: InvoiceRequest,
    ): InvoiceReceiveData = receiveResponse ?: throw IOException("missing fake response")

    override suspend fun witnessReceive(
        context: BackendContext,
        request: InvoiceRequest,
    ): InvoiceReceiveData = receiveResponse ?: throw IOException("missing fake response")

    override suspend fun onchainReceive(
        context: BackendContext,
        request: OnchainReceiveRequest,
    ): OnchainReceiveResponse = onchainReceiveResponse ?: throw IOException("missing fake response")

    private fun prepared(
        context: OperationRequestContext,
        kind: OperationKind,
        hash: RequestHash,
        plan: CreateUtxosOutputPlan? = null,
    ): PreparedTransaction {
        currentOperationKind = kind
        return PreparedTransaction(
            operationId = TEST_OPERATION_ID,
            idempotencyKey = context.idempotencyKey,
            requestHash = hash,
            network = context.backend.network,
            operation = kind,
            psbtBase64 = "prepared-psbt",
            expiresAt = UnixSeconds.of(TEST_NOW + 1_000),
            createUtxosOutputPlan = plan,
        )
    }
}

internal fun testWallet(
    backend: FakeBackend,
    signer: FakeSigningEngine,
    journal: OperationJournal = InMemoryOperationJournal(),
): UTEXOWallet = UTEXOWallet(
    configuration = testConfiguration(),
    backend = backend,
    keyProvider = WalletKeyProvider { SecretSeed.fromBytes(ByteArray(64) { 1 }) },
    operationJournal = journal,
    signingEngineFactory = LocalSigningEngineFactory { _, _ -> signer },
    epochSecondsSource = EpochSecondsSource { TEST_NOW },
)

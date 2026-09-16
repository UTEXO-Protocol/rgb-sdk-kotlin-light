package com.utexo.rgb.sdk

/** Marks low-level backend/journal transport models that are not wallet application API. */
@RequiresOptIn(
    message = "This is unstable wallet infrastructure SPI, not application-facing wallet API.",
    level = RequiresOptIn.Level.ERROR,
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
@Retention(AnnotationRetention.BINARY)
public annotation class WalletInfrastructureApi

/**
 * Infrastructure service-provider interface used by [UTEXOWallet].
 *
 * Application code should call [UTEXOWallet], not these prepare/submit methods.
 * The prepared PSBT is intentionally confined to this backend SPI and the
 * internal local signer; no public wallet method accepts or returns a PSBT.
 */
@WalletInfrastructureApi
public interface WalletBackend {
    public suspend fun initialize(request: BackendInitializeRequest): BackendSession

    public suspend fun registerWallet(request: RegisterWalletRequest): Unit

    public suspend fun shutdown(session: BackendSession): Unit

    public suspend fun getBtcBalance(context: BackendContext): BtcBalance =
        unsupportedBackendMethod("getBtcBalance")

    public suspend fun reserveAddress(request: ReserveAddressRequest): ReservedAddress =
        unsupportedBackendMethod("reserveAddress")

    public suspend fun listUnspents(context: BackendContext): List<Unspent> =
        unsupportedBackendMethod("listUnspents")

    public suspend fun estimateFeeRate(
        context: BackendContext,
        targetBlocks: BlockCount,
    ): FeeEstimate = unsupportedBackendMethod("estimateFeeRate")

    public suspend fun listAssets(context: BackendContext): ListAssets =
        unsupportedBackendMethod("listAssets")

    public suspend fun getAssetBalance(
        context: BackendContext,
        assetId: AssetId,
    ): AssetBalance = unsupportedBackendMethod("getAssetBalance")

    public suspend fun issueAssetNia(
        context: BackendContext,
        request: IssueAssetNiaRequest,
    ): AssetNia = unsupportedBackendMethod("issueAssetNia")

    public suspend fun issueAssetIfa(
        context: BackendContext,
        request: IssueAssetIfaRequest,
    ): AssetIfa = unsupportedBackendMethod("issueAssetIfa")

    public suspend fun blindReceive(
        context: BackendContext,
        request: InvoiceRequest,
    ): InvoiceReceiveData = unsupportedBackendMethod("blindReceive")

    public suspend fun witnessReceive(
        context: BackendContext,
        request: InvoiceRequest,
    ): InvoiceReceiveData = unsupportedBackendMethod("witnessReceive")

    public suspend fun onchainReceive(
        context: BackendContext,
        request: OnchainReceiveRequest,
    ): OnchainReceiveResponse = unsupportedBackendMethod("onchainReceive")

    public suspend fun listTransactions(context: BackendContext): List<Transaction> =
        unsupportedBackendMethod("listTransactions")

    public suspend fun listTransactionsByTxid(
        context: BackendContext,
        txid: TransactionId,
        skipSync: Boolean = false,
    ): List<Transaction> = unsupportedBackendMethod("listTransactionsByTxid")

    public suspend fun listTransfers(
        context: BackendContext,
        assetId: AssetId? = null,
    ): List<Transfer> = unsupportedBackendMethod("listTransfers")

    public suspend fun listTransfersByTxid(
        context: BackendContext,
        txid: TransactionId,
    ): List<Transfer> = unsupportedBackendMethod("listTransfersByTxid")

    public suspend fun failTransfers(
        context: BackendContext,
        request: FailTransfersRequest,
    ): FailTransfersResult = unsupportedBackendMethod("failTransfers")

    public suspend fun refreshWallet(context: BackendContext): SyncResult =
        unsupportedBackendMethod("refreshWallet")

    public suspend fun syncWallet(context: BackendContext): SyncResult =
        unsupportedBackendMethod("syncWallet")

    public suspend fun prepareSendBtc(request: PrepareSendBtcRequest): PreparedTransaction =
        unsupportedBackendMethod("prepareSendBtc")

    public suspend fun prepareCreateUtxos(
        request: PrepareCreateUtxosRequest,
    ): PreparedTransaction = unsupportedBackendMethod("prepareCreateUtxos")

    public suspend fun prepareOnchainSend(
        request: PrepareOnchainSendRequest,
    ): PreparedTransaction = unsupportedBackendMethod("prepareOnchainSend")

    public suspend fun submitSignedTransaction(
        request: SignedTransactionSubmission,
    ): BackendOperationResult = unsupportedBackendMethod("submitSignedTransaction")

    public suspend fun getOperation(
        context: BackendContext,
        operationId: OperationId,
    ): BackendOperationStatus = unsupportedBackendMethod("getOperation")
}

private fun unsupportedBackendMethod(name: String): Nothing =
    throw SdkException.Unsupported("WalletBackend.$name")

@WalletInfrastructureApi
public data class BackendInitializeRequest(
    public val walletId: WalletId,
    public val network: Network,
    public val minimumApiVersion: Int,
    public val maximumApiVersion: Int,
)

@WalletInfrastructureApi
public data class BackendSession(
    public val id: String,
    public val apiVersion: Int,
    public val network: Network,
    public val backendVersion: String? = null,
    public val correlationId: String? = null,
) {
    init {
        requireProtocolIdentifier("backendSessionId", id)
        if (apiVersion <= 0) {
            throw SdkException.InvalidArgument("apiVersion", "must be positive")
        }
    }

    override fun toString(): String =
        "BackendSession(id=[REDACTED], apiVersion=$apiVersion, network=${network.wireValue})"
}

@WalletInfrastructureApi
public data class BackendContext(
    public val session: BackendSession,
    public val walletId: WalletId,
    public val network: Network,
)

@WalletInfrastructureApi
public data class RegisterWalletRequest(
    public val context: BackendContext,
    public val identity: WalletIdentity,
)

public enum class AccountKind(public val wireValue: String) {
    VANILLA("vanilla"),
    COLORED("colored"),
}

public enum class Keychain(public val wireValue: String) {
    EXTERNAL("external"),
    CHANGE("change"),
}

@WalletInfrastructureApi
public data class ReserveAddressRequest(
    public val context: BackendContext,
    public val account: AccountKind = AccountKind.VANILLA,
    public val rotate: Boolean,
)

@WalletInfrastructureApi
public data class ReservedAddress(
    public val address: BitcoinAddress,
    public val account: AccountKind,
    public val keychain: Keychain,
    public val index: Int,
) {
    init {
        if (index < 0) throw SdkException.InvalidArgument("addressIndex", "must not be negative")
    }
}

public enum class OperationKind(public val wireValue: String) {
    SEND_BTC("sendBtc"),
    CREATE_UTXOS("createUtxos"),
    ONCHAIN_SEND("onchainSend"),
    INFLATE("inflate"),
}

@WalletInfrastructureApi
public data class OperationRequestContext(
    public val backend: BackendContext,
    public val idempotencyKey: IdempotencyKey,
    public val requestHash: RequestHash,
)

@WalletInfrastructureApi
public data class PrepareSendBtcRequest(
    public val context: OperationRequestContext,
    public val request: SendBtcRequest,
)

@WalletInfrastructureApi
public data class PrepareCreateUtxosRequest(
    public val context: OperationRequestContext,
    public val request: CreateUtxosRequest,
)

@WalletInfrastructureApi
public data class PrepareOnchainSendRequest(
    public val context: OperationRequestContext,
    public val request: OnchainSendRequest,
)

/** Exact wallet-owned output reserved by the backend and re-derived locally. */
@WalletInfrastructureApi
public data class ExpectedWalletOutput(
    public val account: AccountKind,
    public val keychain: Keychain,
    public val index: Int,
    public val amount: Satoshis,
) {
    init {
        if (index < 0) throw SdkException.InvalidArgument("outputIndex", "must not be negative")
        Satoshis.positive("outputAmount", amount)
    }
}

@WalletInfrastructureApi
public data class CreateUtxosOutputPlan(public val outputs: List<ExpectedWalletOutput>) {
    init {
        if (outputs.isEmpty()) {
            throw SdkException.InvalidArgument("outputs", "must not be empty")
        }
    }
}

/** Backend-only carrier. Its string form deliberately redacts the transaction. */
@WalletInfrastructureApi
public class PreparedTransaction(
    public val operationId: OperationId,
    public val idempotencyKey: IdempotencyKey,
    public val requestHash: RequestHash,
    public val network: Network,
    public val operation: OperationKind,
    public val psbtBase64: String,
    public val expiresAt: UnixSeconds,
    public val createUtxosOutputPlan: CreateUtxosOutputPlan? = null,
    public val correlationId: String? = null,
) {
    init {
        requireBoundedText("psbt", psbtBase64, 5_400_000)
        correlationId?.let {
            if (requireProtocolIdentifier("correlationId", it) != it) {
                throw SdkException.InvalidArgument(
                    "correlationId",
                    "must not contain surrounding whitespace",
                )
            }
        }
        if ((operation == OperationKind.CREATE_UTXOS) != (createUtxosOutputPlan != null)) {
            throw SdkException.InvalidArgument(
                "createUtxosOutputPlan",
                "must be present only for createUtxos operations",
            )
        }
    }

    override fun toString(): String =
        "PreparedTransaction(operationId=$operationId, operation=${operation.wireValue}, psbt=[REDACTED])"
}

@WalletInfrastructureApi
public data class LocalValidationReport(
    public val psbtSha256: String,
    public val intentSha256: String,
    public val unsignedTxid: TransactionId,
    public val totalInput: Satoshis,
    public val totalOutput: Satoshis,
    public val fee: Satoshis,
    public val estimatedVbytes: Long,
    public val feeRate: FeeRate,
    public val externalOutput: Satoshis,
    public val change: Satoshis,
    public val inputCount: Int,
    public val outputCount: Int,
    public val opReturnCount: Int,
) {
    init {
        if (estimatedVbytes <= 0L) {
            throw SdkException.InvalidArgument("estimatedVbytes", "must be positive")
        }
        if (inputCount <= 0) throw SdkException.InvalidArgument("inputCount", "must be positive")
        if (outputCount <= 0) throw SdkException.InvalidArgument("outputCount", "must be positive")
        if (opReturnCount < 0) {
            throw SdkException.InvalidArgument("opReturnCount", "must not be negative")
        }
    }
}

/** Backend-only submission carrier. Application callers cannot construct a signing request. */
@WalletInfrastructureApi
public class SignedTransactionSubmission internal constructor(
    public val context: OperationRequestContext,
    public val operationId: OperationId,
    public val signedPsbtBase64: String,
    public val validation: LocalValidationReport,
) {
    init {
        requireBoundedText("signedPsbt", signedPsbtBase64, 5_400_000)
    }

    override fun toString(): String =
        "SignedTransactionSubmission(operationId=$operationId, signedPsbt=[REDACTED])"
}

@WalletInfrastructureApi
public sealed interface BackendOperationResult {
    public val operationId: OperationId

    public data class SendBtc(
        override val operationId: OperationId,
        public val txid: TransactionId,
    ) : BackendOperationResult

    public data class CreateUtxos(
        override val operationId: OperationId,
        public val result: CreateUtxosResult,
    ) : BackendOperationResult

    public data class OnchainSend(
        override val operationId: OperationId,
        public val result: OnchainSendResponse,
    ) : BackendOperationResult
}

@WalletInfrastructureApi
public sealed interface BackendOperationStatus {
    public val operationId: OperationId

    public data class Pending(
        override val operationId: OperationId,
        public val phase: String,
    ) : BackendOperationStatus {
        init {
            if (requireProtocolIdentifier("backendOperationPhase", phase) != phase) {
                throw SdkException.InvalidArgument(
                    "backendOperationPhase",
                    "must not contain surrounding whitespace",
                )
            }
        }
    }

    public data class Succeeded(
        override val operationId: OperationId,
        public val result: BackendOperationResult,
    ) : BackendOperationStatus {
        init {
            if (result.operationId != operationId) {
                throw SdkException.InvalidArgument(
                    "result",
                    "operation ID does not match status",
                )
            }
        }
    }

    public data class Failed(
        override val operationId: OperationId,
        public val safeMessage: String,
        public val retryable: Boolean,
        public val backendCode: String? = null,
        public val correlationId: String? = null,
    ) : BackendOperationStatus {
        init {
            if (requireBoundedText("safeMessage", safeMessage, 512) != safeMessage) {
                throw SdkException.InvalidArgument(
                    "safeMessage",
                    "must not contain surrounding whitespace",
                )
            }
            backendCode?.let {
                if (requireProtocolIdentifier("backendCode", it) != it) {
                    throw SdkException.InvalidArgument(
                        "backendCode",
                        "must not contain surrounding whitespace",
                    )
                }
            }
            correlationId?.let {
                if (requireProtocolIdentifier("correlationId", it) != it) {
                    throw SdkException.InvalidArgument(
                        "correlationId",
                        "must not contain surrounding whitespace",
                    )
                }
            }
        }
    }

    public data class Expired(
        override val operationId: OperationId,
    ) : BackendOperationStatus
}

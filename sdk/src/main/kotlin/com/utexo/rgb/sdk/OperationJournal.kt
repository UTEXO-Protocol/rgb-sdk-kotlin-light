package com.utexo.rgb.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@WalletInfrastructureApi
public enum class OperationPhase(public val wireValue: String) {
    CREATED("created"),
    PREPARED("prepared"),
    SIGNED("signed"),
    SUBMITTING("submitting"),
    TERMINAL("terminal"),
}

/**
 * Exact signed transaction state required for crash-safe resubmission.
 *
 * This is infrastructure data, not application API. Production journals must
 * encrypt it at rest and must never log or index [signedPsbtBase64].
 */
@WalletInfrastructureApi
public class SignedOperationPayload private constructor(
    public val signedPsbtBase64: String,
    public val validation: LocalValidationReport,
    private val compactReceipt: Boolean,
) {
    public constructor(signedPsbtBase64: String, validation: LocalValidationReport) :
        this(signedPsbtBase64, validation, false)

    init {
        if (!compactReceipt) requireBoundedText("signedPsbt", signedPsbtBase64, 5_400_000)
    }

    internal val isTerminalReceipt: Boolean get() = compactReceipt

    internal fun terminalReceipt(): SignedOperationPayload = SignedOperationPayload("", validation, true)

    override fun toString(): String =
        "SignedOperationPayload(unsignedTxid=${validation.unsignedTxid}, signedPsbt=[REDACTED])"
}

/**
 * Definitive operation outcome retained across process death.
 *
 * A rejection or expiry is just as terminal as a successful broadcast. Keeping
 * it in the journal prevents a retry from resubmitting a transaction after the
 * backend has already reported a definitive negative outcome.
 */
@WalletInfrastructureApi
public sealed interface OperationTerminalOutcome {
    public val operationId: OperationId

    public data class Succeeded(
        public val result: BackendOperationResult,
    ) : OperationTerminalOutcome {
        override val operationId: OperationId = result.operationId
    }

    public data class Failed(
        override val operationId: OperationId,
        public val safeMessage: String,
        public val retryable: Boolean,
        public val backendCode: String? = null,
        public val correlationId: String? = null,
    ) : OperationTerminalOutcome {
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
    ) : OperationTerminalOutcome
}

/**
 * Durable recovery state for a single caller intent.
 *
 * PREPARED and later checkpoints retain the exact backend preparation.
 * SIGNED and SUBMITTING checkpoints retain the exact signed bytes. This prevents a
 * process restart from preparing, signing, or submitting a different payment.
 * Definitive terminal receipts may discard both PSBTs but retain identity,
 * outcome and local validation forever; they can never become submit-capable.
 */
@WalletInfrastructureApi
public data class OperationCheckpoint(
    public val walletId: WalletId,
    public val idempotencyKey: IdempotencyKey,
    public val requestHash: RequestHash,
    public val operationKind: OperationKind,
    public val phase: OperationPhase,
    public val operationId: OperationId? = null,
    public val preparedTransaction: PreparedTransaction? = null,
    public val signedPayload: SignedOperationPayload? = null,
    public val terminalOutcome: OperationTerminalOutcome? = null,
) {
    /** Compatibility view for successful terminal checkpoints. */
    public val terminalResult: BackendOperationResult?
        get() = (terminalOutcome as? OperationTerminalOutcome.Succeeded)?.result

    init {
        val compactTerminal = phase == OperationPhase.TERMINAL && preparedTransaction == null
        val requiresPrepared = phase != OperationPhase.CREATED && !compactTerminal
        if (requiresPrepared != (preparedTransaction != null)) {
            throw SdkException.InvalidArgument(
                "preparedTransaction",
                "must be present after the created phase",
            )
        }
        if ((phase != OperationPhase.CREATED) != (operationId != null)) {
            throw SdkException.InvalidArgument(
                "operationId",
                "must be present after the created phase",
            )
        }
        val forbidsSigned = phase == OperationPhase.CREATED || phase == OperationPhase.PREPARED
        val requiresSigned = phase == OperationPhase.SIGNED ||
            phase == OperationPhase.SUBMITTING ||
            terminalOutcome is OperationTerminalOutcome.Succeeded
        if ((forbidsSigned && signedPayload != null) || (requiresSigned && signedPayload == null)) {
            throw SdkException.InvalidArgument(
                "signedPayload",
                "does not match the checkpoint phase and terminal outcome",
            )
        }
        if ((signedPayload?.isTerminalReceipt == true && !compactTerminal) ||
            (compactTerminal && signedPayload != null && !signedPayload.isTerminalReceipt)
        ) {
            throw SdkException.InvalidArgument("signedPayload", "compact receipt requires a terminal checkpoint")
        }
        if ((phase == OperationPhase.TERMINAL) != (terminalOutcome != null)) {
            throw SdkException.InvalidArgument(
                "terminalOutcome",
                "must be present if and only if phase is terminal",
            )
        }
        if (terminalOutcome != null && terminalOutcome.operationId != operationId) {
            throw SdkException.InvalidArgument(
                "terminalOutcome",
                "operation ID does not match checkpoint",
            )
        }
        val succeeded = terminalOutcome as? OperationTerminalOutcome.Succeeded
        if (succeeded != null) {
            if (succeeded.result.operationKind() != operationKind) {
                throw SdkException.InvalidArgument(
                    "terminalOutcome",
                    "result variant does not match the operation kind",
                )
            }
            if (succeeded.result.transactionId() != signedPayload?.validation?.unsignedTxid) {
                throw SdkException.InvalidArgument(
                    "terminalOutcome",
                    "transaction ID does not match local validation",
                )
            }
        }
        preparedTransaction?.let { prepared ->
            if (prepared.operationId != operationId ||
                prepared.idempotencyKey != idempotencyKey ||
                prepared.requestHash != requestHash ||
                prepared.operation != operationKind
            ) {
                throw SdkException.InvalidArgument(
                    "preparedTransaction",
                    "does not match the checkpoint identity",
                )
            }
        }
    }
}

internal fun OperationCheckpoint.compactTerminal(): OperationCheckpoint =
    if (phase != OperationPhase.TERMINAL || preparedTransaction == null) this else copy(
        preparedTransaction = null,
        signedPayload = signedPayload?.terminalReceipt(),
    )

/**
 * Persistence boundary for crash-safe idempotency. Production implementations
 * must commit [save] atomically and encrypt storage according to app policy.
 */
@WalletInfrastructureApi
public interface OperationJournal {
    public suspend fun load(
        walletId: WalletId,
        idempotencyKey: IdempotencyKey,
    ): OperationCheckpoint?

    public suspend fun save(checkpoint: OperationCheckpoint): Unit
}

/** Volatile journal intended for tests, samples, and non-money-moving prototypes only. */
@WalletInfrastructureApi
public class InMemoryOperationJournal : OperationJournal {
    private val mutex: Mutex = Mutex()
    private val checkpoints: MutableMap<Pair<WalletId, IdempotencyKey>, OperationCheckpoint> =
        mutableMapOf()

    override suspend fun load(
        walletId: WalletId,
        idempotencyKey: IdempotencyKey,
    ): OperationCheckpoint? = mutex.withLock {
        checkpoints[walletId to idempotencyKey]
    }

    override suspend fun save(checkpoint: OperationCheckpoint): Unit = mutex.withLock {
        val key = checkpoint.walletId to checkpoint.idempotencyKey
        val current = checkpoints[key]
        if (current != null) {
            if (current.phase == OperationPhase.TERMINAL) {
                if (sameCheckpoint(current.compactTerminal(), checkpoint.compactTerminal())) return@withLock
                throw SdkException.InternalInvariantViolation(
                    "terminal operation checkpoint is immutable",
                )
            }
            if (current.requestHash != checkpoint.requestHash ||
                current.operationKind != checkpoint.operationKind
            ) {
                throw SdkException.OperationConflict(checkpoint.idempotencyKey)
            }
            if (checkpoint.phase.ordinal < current.phase.ordinal) {
                throw SdkException.InternalInvariantViolation("operation phase moved backwards")
            }
            if (current.operationId != null &&
                checkpoint.operationId != null &&
                current.operationId != checkpoint.operationId
            ) {
                throw SdkException.BackendProtocolMismatch(
                    reason = "operation ID changed for an existing idempotency key",
                    operationId = checkpoint.operationId,
                )
            }
            if (!samePreparedTransaction(
                    current.preparedTransaction,
                    checkpoint.preparedTransaction,
                )
            ) {
                throw SdkException.InternalInvariantViolation(
                    "prepared transaction changed for an existing idempotency key",
                )
            }
            if (!sameSignedPayload(current.signedPayload, checkpoint.signedPayload)) {
                throw SdkException.InternalInvariantViolation(
                    "signed transaction changed for an existing idempotency key",
                )
            }
        }
        checkpoints[key] = checkpoint
    }
}

private fun sameCheckpoint(left: OperationCheckpoint, right: OperationCheckpoint): Boolean =
    left.walletId == right.walletId &&
        left.idempotencyKey == right.idempotencyKey &&
        left.requestHash == right.requestHash &&
        left.operationKind == right.operationKind &&
        left.phase == right.phase &&
        left.operationId == right.operationId &&
        (left.preparedTransaction == null) == (right.preparedTransaction == null) &&
        (left.signedPayload == null) == (right.signedPayload == null) &&
        samePreparedTransaction(left.preparedTransaction, right.preparedTransaction) &&
        sameSignedPayload(left.signedPayload, right.signedPayload) &&
        left.terminalOutcome == right.terminalOutcome

internal fun BackendOperationResult.operationKind(): OperationKind = when (this) {
    is BackendOperationResult.SendBtc -> OperationKind.SEND_BTC
    is BackendOperationResult.CreateUtxos -> OperationKind.CREATE_UTXOS
    is BackendOperationResult.OnchainSend -> OperationKind.ONCHAIN_SEND
}

internal fun BackendOperationResult.transactionId(): TransactionId = when (this) {
    is BackendOperationResult.SendBtc -> txid
    is BackendOperationResult.CreateUtxos -> result.txid
    is BackendOperationResult.OnchainSend -> result.txid
}

private fun samePreparedTransaction(
    left: PreparedTransaction?,
    right: PreparedTransaction?,
): Boolean {
    if (left == null) return true
    if (right == null) return false
    return left.operationId == right.operationId &&
        left.idempotencyKey == right.idempotencyKey &&
        left.requestHash == right.requestHash &&
        left.network == right.network &&
        left.operation == right.operation &&
        left.psbtBase64 == right.psbtBase64 &&
        left.expiresAt == right.expiresAt &&
        left.createUtxosOutputPlan == right.createUtxosOutputPlan &&
        left.correlationId == right.correlationId
}

private fun sameSignedPayload(
    left: SignedOperationPayload?,
    right: SignedOperationPayload?,
): Boolean {
    if (left == null) return true
    if (right == null) return false
    return left.signedPsbtBase64 == right.signedPsbtBase64 &&
        left.validation == right.validation
}

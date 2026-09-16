package com.utexo.rgb.sdk

/** Stable, language-neutral error codes. Do not branch on exception messages. */
public enum class SdkErrorCode(
    public val value: String,
    public val defaultRetryable: Boolean,
) {
    INVALID_ARGUMENT("validation.invalid_argument", false),
    WRONG_NETWORK("validation.wrong_network", false),
    INVALID_STATE("wallet.invalid_state", false),
    WALLET_LOCKED("wallet.locked", false),
    WALLET_DISPOSED("wallet.disposed", false),
    KEY_ACCESS_FAILED("wallet.key_access_failed", true),
    UNSUPPORTED("configuration.unsupported", false),
    CRYPTO_ENTROPY_UNAVAILABLE("crypto.entropy_unavailable", true),
    LOCAL_SIGNING_FAILED("signing.failed", false),
    POLICY_REJECTED("policy.rejected", false),
    OPERATION_CONFLICT("operation.conflict", false),
    OPERATION_EXPIRED("operation.expired", false),
    OPERATION_AMBIGUOUS("operation.ambiguous", true),
    OPERATION_JOURNAL_UNAVAILABLE("storage.operation_journal_unavailable", true),
    BACKEND_UNAVAILABLE("backend.unavailable", true),
    BACKEND_PROTOCOL_MISMATCH("backend.protocol_mismatch", false),
    BACKEND_REJECTED("backend.rejected", false),
    INTERNAL_INVARIANT_VIOLATION("internal.invariant_violation", false),
}

/**
 * Base failure type exposed by the SDK.
 *
 * Messages and [details] are safe to log: implementations must never include
 * seed material, raw PSBTs, invoices, auth tokens, or backend response bodies.
 */
public sealed class SdkException(
    public val code: SdkErrorCode,
    public val retryable: Boolean = code.defaultRetryable,
    message: String,
    public val operationId: OperationId? = null,
    public val correlationId: String? = null,
    details: Map<String, String> = emptyMap(),
) : Exception(message) {
    public val details: Map<String, String> = details.toMap()

    public class InvalidArgument(
        public val field: String,
        reason: String,
    ) : SdkException(
        code = SdkErrorCode.INVALID_ARGUMENT,
        message = "Invalid $field: $reason",
        details = mapOf("field" to field),
    )

    public class WrongNetwork(
        expected: Network,
        actual: Network,
    ) : SdkException(
        code = SdkErrorCode.WRONG_NETWORK,
        message = "The response belongs to a different network",
        details = mapOf("expected" to expected.wireValue, "actual" to actual.wireValue),
    )

    public class InvalidState(
        operation: String,
        state: String,
    ) : SdkException(
        code = SdkErrorCode.INVALID_STATE,
        message = "$operation is not valid while the wallet is $state",
        details = mapOf("operation" to operation, "state" to state),
    )

    public class WalletLocked : SdkException(
        code = SdkErrorCode.WALLET_LOCKED,
        message = "The wallet must be unlocked",
    )

    public class WalletDisposed : SdkException(
        code = SdkErrorCode.WALLET_DISPOSED,
        message = "The wallet instance has been disposed",
    )

    public class KeyAccessFailed(
        retryable: Boolean = true,
    ) : SdkException(
        code = SdkErrorCode.KEY_ACCESS_FAILED,
        retryable = retryable,
        message = "Protected wallet key material could not be loaded",
    )

    public class Unsupported(
        feature: String,
    ) : SdkException(
        code = SdkErrorCode.UNSUPPORTED,
        message = "$feature is not available in this SDK build",
        details = mapOf("feature" to feature),
    )

    public class EntropyUnavailable : SdkException(
        code = SdkErrorCode.CRYPTO_ENTROPY_UNAVAILABLE,
        message = "Secure operating-system entropy is unavailable",
    )

    public class LocalSigningFailed(
        reason: String,
        operationId: OperationId? = null,
    ) : SdkException(
        code = SdkErrorCode.LOCAL_SIGNING_FAILED,
        message = "Local transaction signing failed: $reason",
        operationId = operationId,
    )

    public class PolicyRejected(
        reason: String,
        operationId: OperationId? = null,
    ) : SdkException(
        code = SdkErrorCode.POLICY_REJECTED,
        message = "Local signing policy rejected the transaction: $reason",
        operationId = operationId,
    )

    public class OperationConflict(
        idempotencyKey: IdempotencyKey,
    ) : SdkException(
        code = SdkErrorCode.OPERATION_CONFLICT,
        message = "The idempotency key was already used for a different request",
        details = mapOf("idempotencyKey" to idempotencyKey.value),
    )

    public class OperationExpired(
        operationId: OperationId,
    ) : SdkException(
        code = SdkErrorCode.OPERATION_EXPIRED,
        message = "The prepared operation expired before submission",
        operationId = operationId,
    )

    public class OperationAmbiguous(
        operationId: OperationId,
    ) : SdkException(
        code = SdkErrorCode.OPERATION_AMBIGUOUS,
        message = "The operation outcome is unknown; retry with the same idempotency key",
        operationId = operationId,
    )

    public class OperationJournalUnavailable : SdkException(
        code = SdkErrorCode.OPERATION_JOURNAL_UNAVAILABLE,
        message = "Durable operation state could not be accessed",
    )

    public class BackendUnavailable(
        correlationId: String? = null,
    ) : SdkException(
        code = SdkErrorCode.BACKEND_UNAVAILABLE,
        message = "The wallet backend is unavailable",
        correlationId = correlationId,
    )

    public class BackendProtocolMismatch(
        reason: String,
        operationId: OperationId? = null,
        correlationId: String? = null,
    ) : SdkException(
        code = SdkErrorCode.BACKEND_PROTOCOL_MISMATCH,
        message = "The wallet backend returned an incompatible response: $reason",
        operationId = operationId,
        correlationId = correlationId,
    )

    public class BackendRejected(
        safeMessage: String,
        retryable: Boolean,
        operationId: OperationId? = null,
        correlationId: String? = null,
        backendCode: String? = null,
    ) : SdkException(
        code = SdkErrorCode.BACKEND_REJECTED,
        retryable = retryable,
        message = safeMessage,
        operationId = operationId,
        correlationId = correlationId,
        details = backendCode?.let { mapOf("backendCode" to it) }.orEmpty(),
    )

    public class InternalInvariantViolation(reason: String) : SdkException(
        code = SdkErrorCode.INTERNAL_INVARIANT_VIOLATION,
        message = "SDK invariant failed: $reason",
    )
}

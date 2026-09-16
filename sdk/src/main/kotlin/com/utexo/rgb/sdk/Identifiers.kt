package com.utexo.rgb.sdk

import java.util.UUID

internal fun requireBoundedText(field: String, value: String, maximumBytes: Int): String {
    val normalized = value.trim()
    if (normalized.isEmpty()) throw SdkException.InvalidArgument(field, "must not be empty")
    if (normalized.toByteArray(Charsets.UTF_8).size > maximumBytes) {
        throw SdkException.InvalidArgument(field, "must be at most $maximumBytes UTF-8 bytes")
    }
    return normalized
}

internal fun requireProtocolIdentifier(field: String, value: String): String {
    val normalized = requireBoundedText(field, value, 128)
    if (normalized.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "-_.:" }) {
        throw SdkException.InvalidArgument(field, "contains unsupported characters")
    }
    return normalized
}

@JvmInline
public value class WalletId private constructor(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(value: String): WalletId = WalletId(requireProtocolIdentifier("walletId", value))
    }
}

@JvmInline
public value class OperationId private constructor(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(value: String): OperationId =
            OperationId(requireProtocolIdentifier("operationId", value))
    }
}

@JvmInline
public value class IdempotencyKey private constructor(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(value: String): IdempotencyKey =
            IdempotencyKey(requireProtocolIdentifier("idempotencyKey", value))

        public fun random(): IdempotencyKey = of(UUID.randomUUID().toString())
    }
}

@JvmInline
public value class RequestHash internal constructor(public val hex: String) {
    init {
        if (hex.length != 64 || hex.any { it !in "0123456789abcdef" }) {
            throw SdkException.InvalidArgument("requestHash", "must be 32 lowercase hex bytes")
        }
    }

    override fun toString(): String = hex

    public companion object {
        /** Strict decoder for backend infrastructure implementations. */
        @WalletInfrastructureApi
        public fun ofLowercaseHex(value: String): RequestHash = RequestHash(value)
    }
}

@JvmInline
public value class TransactionId private constructor(public val hex: String) {
    override fun toString(): String = hex

    public companion object {
        public fun of(value: String): TransactionId {
            val normalized = value.trim().lowercase()
            if (normalized.length != 64 || normalized.any { it !in "0123456789abcdef" }) {
                throw SdkException.InvalidArgument("transactionId", "must be 32 hex bytes")
            }
            return TransactionId(normalized)
        }
    }
}

@JvmInline
public value class AssetId private constructor(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(value: String): AssetId = AssetId(requireBoundedText("assetId", value, 256))
    }
}

/**
 * A syntactically bounded address. Full checksum, script type, and network
 * validation is performed by the local Bitcoin engine before signing.
 */
@JvmInline
public value class BitcoinAddress private constructor(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(value: String): BitcoinAddress =
            BitcoinAddress(requireBoundedText("address", value, 128))
    }
}

/** Opaque until the local RGB invoice parser is linked into the native core. */
@JvmInline
public value class RgbInvoice private constructor(public val value: String) {
    override fun toString(): String = "RgbInvoice([REDACTED])"

    public companion object {
        public fun of(value: String): RgbInvoice =
            RgbInvoice(requireBoundedText("invoice", value, 16_384))
    }
}

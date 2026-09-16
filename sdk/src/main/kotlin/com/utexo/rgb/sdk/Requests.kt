package com.utexo.rgb.sdk

public data class SendBtcRequest(
    public val address: BitcoinAddress,
    public val amount: Satoshis,
    public val feeRate: FeeRate = FeeRate.DEFAULT,
    public val skipSync: Boolean = false,
    /** Persist and reuse this key whenever an operation is retried. */
    public val idempotencyKey: IdempotencyKey = IdempotencyKey.random(),
) {
    init {
        Satoshis.positive("amount", amount)
    }
}

public data class CreateUtxosRequest(
    public val upTo: Boolean = true,
    public val num: Int? = null,
    public val size: Satoshis? = null,
    public val feeRate: FeeRate = FeeRate.DEFAULT,
    public val skipSync: Boolean = false,
    public val idempotencyKey: IdempotencyKey = IdempotencyKey.random(),
) {
    init {
        if (num != null && num !in 1..256) {
            throw SdkException.InvalidArgument("num", "must be between 1 and 256")
        }
        size?.let { Satoshis.positive("size", it) }
    }
}

public data class CreateUtxosResult(
    public val txid: TransactionId,
    /** Authoritative count returned after backend acceptance, never the requested count. */
    public val createdUtxoCount: Int,
) {
    init {
        if (createdUtxoCount < 0) {
            throw SdkException.InvalidArgument("createdUtxoCount", "must not be negative")
        }
    }
}

public data class FeeEstimate(
    public val feeRate: FeeRate,
    public val targetBlocks: BlockCount,
    public val measuredAt: UnixSeconds? = null,
)

public data class WitnessData(
    public val amountSat: Satoshis,
    public val blinding: Long? = null,
) {
    init {
        Satoshis.positive("witnessData.amountSat", amountSat)
        if (blinding != null && blinding < 0L) {
            throw SdkException.InvalidArgument("witnessData.blinding", "must not be negative")
        }
    }
}

public data class InvoiceRequest(
    public val amount: AssetAmount? = null,
    public val assetId: AssetId? = null,
    public val minConfirmations: BlockCount = BlockCount.of(0),
    public val duration: DurationSeconds? = null,
) {
    init {
        amount?.let { AssetAmount.positive("amount", it) }
    }
}

public data class OnchainReceiveRequest(
    public val amount: AssetAmount? = null,
    public val assetId: AssetId? = null,
    public val minConfirmations: BlockCount = BlockCount.of(0),
    public val duration: DurationSeconds? = null,
    public val witness: Boolean = true,
) {
    init {
        amount?.let { AssetAmount.positive("amount", it) }
    }

    internal fun asInvoiceRequest(): InvoiceRequest = InvoiceRequest(
        amount = amount,
        assetId = assetId,
        minConfirmations = minConfirmations,
        duration = duration,
    )
}

public data class OnchainReceiveResponse(
    public val invoice: RgbInvoice,
    public val recipientId: String? = null,
    public val expirationTimestamp: UnixSeconds? = null,
    public val batchTransferIdx: Long? = null,
) {
    init {
        recipientId?.let { requireBoundedText("recipientId", it, 1_024) }
        if (batchTransferIdx != null && batchTransferIdx < 0L) {
            throw SdkException.InvalidArgument("batchTransferIdx", "must not be negative")
        }
    }
}

public data class OnchainSendRequest(
    public val invoice: RgbInvoice,
    public val amount: AssetAmount,
    public val assetId: AssetId? = null,
    public val witnessData: WitnessData? = null,
    public val donation: Boolean = false,
    public val feeRate: FeeRate = FeeRate.DEFAULT,
    public val minConfirmations: BlockCount = BlockCount.of(1),
    public val skipSync: Boolean = false,
    public val idempotencyKey: IdempotencyKey = IdempotencyKey.random(),
) {
    init {
        AssetAmount.positive("amount", amount)
    }
}

public data class OnchainSendResponse(
    public val txid: TransactionId,
    public val batchTransferIdx: Long,
) {
    init {
        if (batchTransferIdx < 0L) {
            throw SdkException.InvalidArgument("batchTransferIdx", "must not be negative")
        }
    }
}

public data class IssueAssetNiaRequest(
    public val ticker: String,
    public val name: String,
    public val amounts: List<AssetAmount>,
    public val precision: Int,
) {
    init {
        requireBoundedText("ticker", ticker, 16)
        requireBoundedText("name", name, 256)
        if (amounts.isEmpty()) throw SdkException.InvalidArgument("amounts", "must not be empty")
        amounts.forEach { AssetAmount.positive("amounts", it) }
        if (precision !in 0..18) {
            throw SdkException.InvalidArgument("precision", "must be between 0 and 18")
        }
    }
}

public data class IssueAssetIfaRequest(
    public val ticker: String,
    public val name: String,
    public val precision: Int,
    public val amounts: List<AssetAmount>,
    public val inflationAmounts: List<AssetAmount>,
    public val rejectListUrl: String? = null,
) {
    init {
        requireBoundedText("ticker", ticker, 16)
        requireBoundedText("name", name, 256)
        if (precision !in 0..18) {
            throw SdkException.InvalidArgument("precision", "must be between 0 and 18")
        }
        if (amounts.isEmpty()) throw SdkException.InvalidArgument("amounts", "must not be empty")
        amounts.forEach { AssetAmount.positive("amounts", it) }
        inflationAmounts.forEach { AssetAmount.positive("inflationAmounts", it) }
        rejectListUrl?.let { requireBoundedText("rejectListUrl", it, 2_048) }
    }
}

public data class InflateRequest(
    public val assetId: AssetId,
    public val inflationAmounts: List<AssetAmount>,
    public val feeRate: FeeRate = FeeRate.DEFAULT,
    public val minConfirmations: BlockCount = BlockCount.of(1),
    public val idempotencyKey: IdempotencyKey = IdempotencyKey.random(),
) {
    init {
        if (inflationAmounts.isEmpty()) {
            throw SdkException.InvalidArgument("inflationAmounts", "must not be empty")
        }
        inflationAmounts.forEach { AssetAmount.positive("inflationAmounts", it) }
    }
}

public data class FailTransfersRequest(
    public val batchTransferIdx: Long? = null,
    public val noAssetOnly: Boolean = false,
    public val skipSync: Boolean = false,
) {
    init {
        if (batchTransferIdx != null && batchTransferIdx < 0L) {
            throw SdkException.InvalidArgument("batchTransferIdx", "must not be negative")
        }
    }
}

public data class FailTransfersResult(public val transfersChanged: Boolean)

public data class SyncResult(
    public val completedAt: UnixSeconds,
    public val tipHeight: Int? = null,
) {
    init {
        if (tipHeight != null && tipHeight < 0) {
            throw SdkException.InvalidArgument("tipHeight", "must not be negative")
        }
    }
}

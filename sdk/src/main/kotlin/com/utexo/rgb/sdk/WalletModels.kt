package com.utexo.rgb.sdk

/** Watch-only identity registered with the backend. No private key material is included. */
public data class WalletIdentity(
    public val network: Network,
    public val chainNetwork: String,
    public val derivationProfile: String,
    public val masterFingerprint: String,
    public val xpub: String,
    public val accountXpubVanilla: String,
    public val accountXpubColored: String,
)

public data class Balance(
    public val settled: Satoshis,
    public val future: Satoshis,
    public val spendable: Satoshis,
)

public data class BtcBalance(
    public val vanilla: Balance,
    public val colored: Balance,
)

public data class AssetBalance(
    public val settled: AssetAmount? = null,
    public val future: AssetAmount? = null,
    public val spendable: AssetAmount? = null,
    public val offchainOutbound: AssetAmount? = null,
    public val offchainInbound: AssetAmount? = null,
)

public data class Outpoint(
    public val txid: TransactionId,
    public val vout: Int,
) {
    init {
        if (vout < 0) throw SdkException.InvalidArgument("vout", "must not be negative")
    }
}

public data class Utxo(
    public val outpoint: Outpoint,
    public val btcAmount: Satoshis,
    public val colorable: Boolean,
    /** Unlike the RN mapper, this must be authoritative backend state. */
    public val exists: Boolean,
)

public class AssignmentType private constructor(public val wireValue: String) {
    override fun equals(other: Any?): Boolean =
        other is AssignmentType && wireValue == other.wireValue

    override fun hashCode(): Int = wireValue.hashCode()
    override fun toString(): String = wireValue

    public companion object {
        public val FUNGIBLE: AssignmentType = AssignmentType("Fungible")
        public val NON_FUNGIBLE: AssignmentType = AssignmentType("NonFungible")
        public val INFLATION_RIGHT: AssignmentType = AssignmentType("InflationRight")
        public val REPLACE_RIGHT: AssignmentType = AssignmentType("ReplaceRight")
        public val ANY: AssignmentType = AssignmentType("Any")

        /** Preserves unknown future wire values instead of failing enum decoding. */
        public fun fromWire(value: String): AssignmentType =
            AssignmentType(requireBoundedText("assignmentType", value, 64))
    }
}

public data class Assignment(
    public val type: AssignmentType,
    public val amount: AssetAmount? = null,
)

public data class RgbAllocation(
    public val assetId: AssetId? = null,
    public val assignment: Assignment,
    public val settled: Boolean,
)

public data class Unspent(
    public val utxo: Utxo,
    public val rgbAllocations: List<RgbAllocation>,
    public val pendingBlinded: Int,
) {
    init {
        if (pendingBlinded < 0) {
            throw SdkException.InvalidArgument("pendingBlinded", "must not be negative")
        }
    }
}

public class TransactionType private constructor(public val wireValue: String) {
    override fun equals(other: Any?): Boolean =
        other is TransactionType && wireValue == other.wireValue

    override fun hashCode(): Int = wireValue.hashCode()
    override fun toString(): String = wireValue

    public companion object {
        public val RGB_SEND: TransactionType = TransactionType("RgbSend")
        public val DRAIN: TransactionType = TransactionType("Drain")
        public val CREATE_UTXOS: TransactionType = TransactionType("CreateUtxos")
        public val SEND_BTC: TransactionType = TransactionType("SendBtc")
        public val INCOMING: TransactionType = TransactionType("Incoming")
        public val USER: TransactionType = TransactionType("User")

        public fun fromWire(value: String): TransactionType =
            TransactionType(requireBoundedText("transactionType", value, 64))
    }
}

public data class BlockTime(
    public val height: Int,
    public val timestamp: UnixSeconds,
) {
    init {
        if (height < 0) throw SdkException.InvalidArgument("height", "must not be negative")
    }
}

public data class Transaction(
    public val transactionType: TransactionType,
    public val txid: TransactionId,
    public val received: Satoshis,
    public val sent: Satoshis,
    public val fee: Satoshis,
    public val confirmationTime: BlockTime? = null,
)

public class TransferKind private constructor(public val wireValue: String) {
    override fun equals(other: Any?): Boolean = other is TransferKind && wireValue == other.wireValue
    override fun hashCode(): Int = wireValue.hashCode()
    override fun toString(): String = wireValue

    public companion object {
        public val ISSUANCE: TransferKind = TransferKind("Issuance")
        public val RECEIVE_BLIND: TransferKind = TransferKind("ReceiveBlind")
        public val RECEIVE_WITNESS: TransferKind = TransferKind("ReceiveWitness")
        public val SEND: TransferKind = TransferKind("Send")
        public val INFLATION: TransferKind = TransferKind("Inflation")
        public val BURN: TransferKind = TransferKind("Burn")

        public fun fromWire(value: String): TransferKind =
            TransferKind(requireBoundedText("transferKind", value, 64))
    }
}

public class TransferStatus private constructor(public val wireValue: String) {
    override fun equals(other: Any?): Boolean =
        other is TransferStatus && wireValue == other.wireValue

    override fun hashCode(): Int = wireValue.hashCode()
    override fun toString(): String = wireValue

    public companion object {
        public val WAITING_COUNTERPARTY: TransferStatus = TransferStatus("WaitingCounterparty")
        public val WAITING_SAFE_HEIGHT: TransferStatus = TransferStatus("WaitingSafeHeight")
        public val WAITING_BROADCAST: TransferStatus = TransferStatus("WaitingBroadcast")
        public val WAITING_CONFIRMATIONS: TransferStatus = TransferStatus("WaitingConfirmations")
        public val SETTLED: TransferStatus = TransferStatus("Settled")
        public val FAILED: TransferStatus = TransferStatus("Failed")
        public val INITIATED: TransferStatus = TransferStatus("Initiated")

        public fun fromWire(value: String): TransferStatus =
            TransferStatus(requireBoundedText("transferStatus", value, 64))
    }
}

public data class TransferTransportEndpoint(
    public val endpoint: String,
    public val transportType: String,
    public val used: Boolean,
) {
    init {
        requireCanonicalText("transportEndpoint", endpoint, 2_048)
        requireCanonicalText("transportType", transportType, 64)
    }
}

public data class Transfer(
    public val idx: Long,
    public val batchTransferIdx: Long,
    public val createdAt: UnixSeconds,
    public val updatedAt: UnixSeconds,
    public val status: TransferStatus,
    public val requestedAssignment: Assignment? = null,
    public val assignments: List<Assignment>,
    public val kind: TransferKind,
    public val txid: TransactionId? = null,
    public val recipientId: String? = null,
    public val receiveUtxo: Outpoint? = null,
    public val changeUtxo: Outpoint? = null,
    public val expiration: UnixSeconds? = null,
    public val transportEndpoints: List<TransferTransportEndpoint> = emptyList(),
    /** Intentionally not exposed: the backend must not leak a server filesystem path. */
    public val invoice: RgbInvoice? = null,
) {
    init {
        if (idx < 0L) throw SdkException.InvalidArgument("idx", "must not be negative")
        if (batchTransferIdx < 0L) {
            throw SdkException.InvalidArgument("batchTransferIdx", "must not be negative")
        }
        recipientId?.let { requireCanonicalText("recipientId", it, 1_024) }
    }
}

public data class Media(
    public val reference: String? = null,
    public val mimeType: String? = null,
) {
    init {
        reference?.let { requireCanonicalText("media.reference", it, 2_048) }
        mimeType?.let { requireCanonicalText("media.mimeType", it, 255) }
    }
}

public data class AssetNia(
    public val assetId: AssetId,
    public val ticker: String,
    public val name: String,
    public val precision: Int,
    public val issuedSupply: AssetAmount,
    public val timestamp: UnixSeconds,
    public val addedAt: UnixSeconds,
    public val balance: AssetBalance,
    public val details: String? = null,
    public val media: Media? = null,
) {
    init { validateAssetMetadata(ticker, name, precision, details) }
}

public data class AssetIfa(
    public val assetId: AssetId,
    public val ticker: String,
    public val name: String,
    public val precision: Int,
    public val initialSupply: AssetAmount,
    public val maxSupply: AssetAmount,
    public val knownCirculatingSupply: AssetAmount,
    public val timestamp: UnixSeconds,
    public val addedAt: UnixSeconds,
    public val balance: AssetBalance,
    public val details: String? = null,
    public val media: Media? = null,
    public val rejectListUrl: String? = null,
) {
    init {
        validateAssetMetadata(ticker, name, precision, details)
        if (initialSupply > maxSupply || knownCirculatingSupply > maxSupply) {
            throw SdkException.InvalidArgument("supply", "must not exceed maximum supply")
        }
        rejectListUrl?.let { requireCanonicalText("rejectListUrl", it, 2_048) }
    }
}

public data class AssetCfa(
    public val assetId: AssetId,
    public val name: String,
    public val precision: Int,
    public val issuedSupply: AssetAmount,
    public val timestamp: UnixSeconds,
    public val addedAt: UnixSeconds,
    public val balance: AssetBalance,
    public val details: String? = null,
    public val media: Media? = null,
) {
    init { validateAssetMetadata(null, name, precision, details) }
}

public data class AssetUda(
    public val assetId: AssetId,
    public val ticker: String,
    public val name: String,
    public val precision: Int,
    public val timestamp: UnixSeconds,
    public val addedAt: UnixSeconds,
    public val balance: AssetBalance,
    public val details: String? = null,
) {
    init { validateAssetMetadata(ticker, name, precision, details) }
}

public data class ListAssets(
    public val nia: List<AssetNia> = emptyList(),
    public val uda: List<AssetUda> = emptyList(),
    public val cfa: List<AssetCfa> = emptyList(),
    public val ifa: List<AssetIfa> = emptyList(),
)

public data class InvoiceReceiveData(
    public val invoice: RgbInvoice,
    public val recipientId: String,
    public val expirationTimestamp: UnixSeconds?,
    public val batchTransferIdx: Long,
) {
    init {
        requireCanonicalText("recipientId", recipientId, 1_024)
        if (batchTransferIdx < 0L) {
            throw SdkException.InvalidArgument("batchTransferIdx", "must not be negative")
        }
    }
}

public data class InvoiceData(
    public val invoice: RgbInvoice,
    public val recipientId: String,
    public val assetSchema: String? = null,
    public val assetId: AssetId? = null,
    public val network: Network,
    public val assignment: Assignment,
    public val assignmentName: String? = null,
    public val expirationTimestamp: UnixSeconds? = null,
    public val transportEndpoints: List<String> = emptyList(),
) {
    init {
        requireCanonicalText("recipientId", recipientId, 1_024)
        assetSchema?.let { requireCanonicalText("assetSchema", it, 128) }
        assignmentName?.let { requireCanonicalText("assignmentName", it, 256) }
        transportEndpoints.forEach { requireCanonicalText("transportEndpoint", it, 2_048) }
    }
}

private fun validateAssetMetadata(ticker: String?, name: String, precision: Int, details: String?): Unit {
    ticker?.let { requireCanonicalText("ticker", it, 16) }
    requireCanonicalText("name", name, 256)
    if (precision !in 0..18) throw SdkException.InvalidArgument("precision", "must be between 0 and 18")
    if (details != null && details.toByteArray(Charsets.UTF_8).size > 4_096) {
        throw SdkException.InvalidArgument("details", "must be at most 4096 UTF-8 bytes")
    }
}

package com.utexo.rgb.sdk

/** Logical SDK network. UTEXO intentionally maps to Signet for Bitcoin keys. */
public enum class Network(
    public val wireValue: String,
    public val chainNetwork: String,
) {
    MAINNET("mainnet", "mainnet"),
    TESTNET("testnet", "testnet"),
    TESTNET4("testnet4", "testnet4"),
    SIGNET("signet", "signet"),
    REGTEST("regtest", "regtest"),
    UTEXO("utexo", "signet"),
}

public enum class RbfPolicy(public val wireValue: String) {
    REQUIRED("required"),
    ALLOWED("allowed"),
    FORBIDDEN("forbidden"),
}

/** Default-deny local limits applied independently of backend policy. */
public data class LocalSigningPolicy(
    public val maximumAbsoluteFee: Satoshis = Satoshis.of(1_000_000),
    public val maximumFeeRate: FeeRate = FeeRate.satPerVbyte(100.0),
    public val maximumPsbtBytes: Int = 1_000_000,
    public val maximumInputs: Int = 128,
    public val maximumOutputs: Int = 256,
    public val maximumChildIndex: Int = 1_000_000,
    public val rbf: RbfPolicy = RbfPolicy.REQUIRED,
    /**
     * Signs an RGB send that arrives without RGB send evidence, which leaves its RGB
     * transitions unverified (blind signing). Only for backends that do not return the
     * evidence yet. Evidence that is supplied is always verified, whatever this says.
     */
    public val allowUnverifiedRgbSends: Boolean = false,
) {
    init {
        if (maximumPsbtBytes !in 100..1_000_000) {
            throw SdkException.InvalidArgument(
                "maximumPsbtBytes",
                "must be between 100 and 1000000",
            )
        }
        if (maximumInputs !in 1..128) {
            throw SdkException.InvalidArgument("maximumInputs", "must be between 1 and 128")
        }
        if (maximumOutputs !in 1..256) {
            throw SdkException.InvalidArgument("maximumOutputs", "must be between 1 and 256")
        }
        if (maximumChildIndex !in 0..1_000_000) {
            throw SdkException.InvalidArgument(
                "maximumChildIndex",
                "must be between 0 and 1000000",
            )
        }
        if (maximumAbsoluteFee.value > 1_000_000L) {
            throw SdkException.InvalidArgument(
                "maximumAbsoluteFee",
                "must not exceed 1000000 sats",
            )
        }
        if (maximumFeeRate.milliSatsPerVbyte > 100_000L) {
            throw SdkException.InvalidArgument(
                "maximumFeeRate",
                "must not exceed 100 sat/vB",
            )
        }
        if (rbf != RbfPolicy.REQUIRED) {
            throw SdkException.InvalidArgument(
                "rbf",
                "signing profile v1 requires sequence 0xFFFFFFFD",
            )
        }
    }
}

public data class WalletConfiguration(
    public val walletId: WalletId,
    public val network: Network,
    public val signingPolicy: LocalSigningPolicy = LocalSigningPolicy(),
    public val minimumBackendApiVersion: Int = 1,
    public val maximumBackendApiVersion: Int = 1,
) {
    init {
        if (minimumBackendApiVersion <= 0) {
            throw SdkException.InvalidArgument("minimumBackendApiVersion", "must be positive")
        }
        if (maximumBackendApiVersion < minimumBackendApiVersion) {
            throw SdkException.InvalidArgument(
                "maximumBackendApiVersion",
                "must not be lower than minimumBackendApiVersion",
            )
        }
    }
}

public data class WalletCapabilities(
    public val localKeyDerivation: Boolean,
    public val localTransactionValidation: Boolean,
    public val localTransactionSigning: Boolean,
    /** Syntactic invoice decoding and caller-intent checks, not RGB state validation. */
    public val localRgbInvoiceParsing: Boolean,
    public val localRgbValidation: Boolean,
    /** Operations for which this build can enforce the complete local policy. */
    public val localSigningOperations: Set<OperationKind>,
    public val derivationProfile: String,
    public val signingProfile: String,
) {
    public companion object {
        public const val DERIVATION_PROFILE_V1: String = "utexo.bip86.v1"
        public const val SIGNING_PROFILE_V1: String = "utexo.psbt.bip86.v1"
    }
}

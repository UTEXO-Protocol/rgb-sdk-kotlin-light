package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.AccountKind
import com.utexo.rgb.sdk.BitcoinAddress
import com.utexo.rgb.sdk.FeeRate
import com.utexo.rgb.sdk.InvoiceData
import com.utexo.rgb.sdk.Keychain
import com.utexo.rgb.sdk.LocalSigningPolicy
import com.utexo.rgb.sdk.LocalValidationReport
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.OperationKind
import com.utexo.rgb.sdk.PreparedTransaction
import com.utexo.rgb.sdk.RbfPolicy
import com.utexo.rgb.sdk.ReservedAddress
import com.utexo.rgb.sdk.RgbInvoice
import com.utexo.rgb.sdk.Satoshis
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.SecretSeed
import com.utexo.rgb.sdk.TransactionId
import com.utexo.rgb.sdk.WalletCapabilities
import com.utexo.rgb.sdk.WalletIdentity
import com.utexo.rgb.sdk.internal.ffi.AccountKind as FfiAccountKind
import com.utexo.rgb.sdk.internal.ffi.KeyScope as FfiKeyScope
import com.utexo.rgb.sdk.internal.ffi.Keychain as FfiKeychain
import com.utexo.rgb.sdk.internal.ffi.LocalSigner as FfiLocalSigner
import com.utexo.rgb.sdk.internal.ffi.Network as FfiNetwork
import com.utexo.rgb.sdk.internal.ffi.OperationKind as FfiOperationKind
import com.utexo.rgb.sdk.internal.ffi.RbfPolicy as FfiRbfPolicy
import com.utexo.rgb.sdk.internal.ffi.Recipient as FfiRecipient
import com.utexo.rgb.sdk.internal.ffi.SignerException as FfiSignerException
import com.utexo.rgb.sdk.internal.ffi.SigningIntent as FfiSigningIntent
import com.utexo.rgb.sdk.internal.ffi.SigningPolicy as FfiSigningPolicy
import com.utexo.rgb.sdk.internal.ffi.ValidationReport as FfiValidationReport
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Concrete private adapter around the generated `utexo-local-signer` bindings. */
internal object UniFfiLocalSigningEngineFactory : LocalSigningEngineFactory {
    override suspend fun open(network: Network, seed: SecretSeed): LocalSigningEngine =
        withContext(Dispatchers.Default) {
            val bytes = seed.copyForNative()
            try {
                UniFfiLocalSigningEngine(FfiLocalSigner.fromSeed(network.toFfi(), bytes))
            } catch (linkage: LinkageError) {
                throw SdkException.Unsupported("native utexo-local-signer library")
            } catch (exception: FfiSignerException) {
                throw exception.toSdkException()
            } finally {
                Arrays.fill(bytes, 0.toByte())
            }
        }
}

internal class UniFfiLocalSigningEngine(
    private val signer: FfiLocalSigner,
) : LocalSigningEngine {
    override val capabilities: WalletCapabilities = WalletCapabilities(
        localKeyDerivation = true,
        localTransactionValidation = true,
        localTransactionSigning = true,
        localRgbInvoiceParsing = false,
        localRgbValidation = false,
        // Rust profile v1 rejects every operation that lacks a complete local
        // intent/evidence contract. Do not add a value here before Rust does.
        localSigningOperations = setOf(OperationKind.SEND_BTC),
        derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
        signingProfile = WalletCapabilities.SIGNING_PROFILE_V1,
    )

    override suspend fun identity(): WalletIdentity = nativeCall {
        val identity = signer.identity()
        WalletIdentity(
            network = identity.network.toPublic(),
            chainNetwork = identity.chainNetwork,
            derivationProfile = identity.derivationProfile,
            masterFingerprint = identity.masterFingerprint,
            xpub = identity.xpub,
            accountXpubVanilla = identity.accountXpubVanilla,
            accountXpubColored = identity.accountXpubColored,
        )
    }

    override suspend fun verifyReservedAddress(reservation: ReservedAddress): Unit = nativeCall {
        val derived = signer.deriveAddress(
            reservation.account.toFfi(),
            reservation.keychain.toFfi(),
            reservation.index.toUInt(),
        )
        val expected = BitcoinAddress.of(derived.address)
        if (expected != reservation.address) {
            throw SdkException.BackendProtocolMismatch(
                "reserved address does not match its local BIP86 derivation",
            )
        }
    }

    override suspend fun decodeRgbInvoice(invoice: RgbInvoice): InvoiceData {
        @Suppress("UNUSED_VARIABLE")
        val boundedInvoice = invoice
        throw SdkException.Unsupported("local RGB invoice parsing")
    }

    override suspend fun validateAndSign(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent,
    ): LocalSigningResult = nativeCall {
        when (intent) {
            is LocalOperationIntent.SendBtc -> signSendBtc(prepared, intent)
            is LocalOperationIntent.CreateUtxos -> throw SdkException.Unsupported(
                "complete local createUtxos intent validation and signing",
            )
            is LocalOperationIntent.OnchainSend -> throw SdkException.Unsupported(
                "validated RGB transition evidence and onchainSend signing",
            )
        }
    }

    private fun signSendBtc(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent.SendBtc,
    ): LocalSigningResult {
        if (prepared.operation != OperationKind.SEND_BTC) {
            throw SdkException.InternalInvariantViolation("sendBtc signer received another operation")
        }
        if (intent.policy.rbf != RbfPolicy.REQUIRED) {
            throw SdkException.PolicyRejected("signing profile v1 requires RBF sequence 0xFFFFFFFD")
        }
        val maximumFeeRate = minOf(
            intent.policy.maximumFeeRate.milliSatsPerVbyte,
            intent.request.feeRate.milliSatsPerVbyte,
        )
        val ffiIntent = FfiSigningIntent(
            operationId = prepared.operationId.value,
            idempotencyKey = prepared.idempotencyKey.value,
            network = intent.network.toFfi(),
            operation = FfiOperationKind.SEND_BTC,
            recipients = listOf(
                FfiRecipient(
                    address = intent.request.address.value,
                    amountSats = intent.request.amount.value.toULong(),
                ),
            ),
            policy = intent.policy.toSendBtcFfi(maximumFeeRate),
        )
        val result = signer.signPsbt(prepared.psbtBase64, ffiIntent)
        return LocalSigningResult(
            signedPsbtBase64 = result.signedPsbtBase64,
            validation = result.validation.toPublic(),
        )
    }

    override fun close(): Unit = signer.close()

    private suspend fun <T> nativeCall(block: () -> T): T = withContext(Dispatchers.Default) {
        try {
            block()
        } catch (sdk: SdkException) {
            throw sdk
        } catch (linkage: LinkageError) {
            throw SdkException.Unsupported("native utexo-local-signer library")
        } catch (exception: FfiSignerException) {
            throw exception.toSdkException()
        } catch (_: RuntimeException) {
            throw SdkException.InternalInvariantViolation("native signer call failed")
        }
    }
}

private fun LocalSigningPolicy.toSendBtcFfi(maximumFeeRate: Long): FfiSigningPolicy =
    FfiSigningPolicy(
        maxPsbtBytes = maximumPsbtBytes.toUInt(),
        maxInputs = maximumInputs.toUInt(),
        maxOutputs = maximumOutputs.toUInt(),
        maxChildIndex = maximumChildIndex.toUInt(),
        maxAbsoluteFeeSats = maximumAbsoluteFee.value.toULong(),
        maxFeeRateMsatPerVbyte = maximumFeeRate.toULong(),
        rbf = FfiRbfPolicy.REQUIRED,
        expectedLockTime = 0u,
        expectedSequence = 0xfffffffdu,
        allowSighashAll = true,
        allowUnknownFields = false,
        allowedProprietaryPrefixes = emptyList(),
        maxOpReturnOutputs = 0u,
        maxOpReturnScriptBytes = 83u,
        allowedInputScopes = listOf(
            FfiKeyScope(FfiAccountKind.VANILLA, FfiKeychain.EXTERNAL),
            FfiKeyScope(FfiAccountKind.VANILLA, FfiKeychain.CHANGE),
        ),
        allowedChangeScopes = listOf(
            FfiKeyScope(FfiAccountKind.VANILLA, FfiKeychain.CHANGE),
        ),
    )

private fun FfiValidationReport.toPublic(): LocalValidationReport = LocalValidationReport(
    psbtSha256 = psbtSha256,
    intentSha256 = intentSha256,
    unsignedTxid = TransactionId.of(unsignedTxid),
    totalInput = Satoshis.of(totalInputSats.toSatsLong("totalInputSats")),
    totalOutput = Satoshis.of(totalOutputSats.toSatsLong("totalOutputSats")),
    fee = Satoshis.of(feeSats.toSatsLong("feeSats")),
    estimatedVbytes = estimatedVbytes.toLongExact("estimatedVbytes"),
    feeRate = FeeRate.milliSatsPerVbyte(
        feeRateMsatPerVbyte.toLongExact("feeRateMsatPerVbyte"),
    ),
    externalOutput = Satoshis.of(externalOutputSats.toSatsLong("externalOutputSats")),
    change = Satoshis.of(changeSats.toSatsLong("changeSats")),
    inputCount = inputCount.toIntExact("inputCount"),
    outputCount = outputCount.toIntExact("outputCount"),
    opReturnCount = opReturnCount.toIntExact("opReturnCount"),
)

private fun ULong.toLongExact(field: String): Long {
    if (this > Long.MAX_VALUE.toULong()) {
        throw SdkException.InternalInvariantViolation("$field exceeds the Kotlin signed range")
    }
    return toLong()
}

private fun ULong.toSatsLong(field: String): Long {
    val value = toLongExact(field)
    if (value > Satoshis.MAX_VALUE) {
        throw SdkException.InternalInvariantViolation("$field exceeds the Bitcoin supply")
    }
    return value
}

private fun UInt.toIntExact(field: String): Int {
    if (this > Int.MAX_VALUE.toUInt()) {
        throw SdkException.InternalInvariantViolation("$field exceeds the Kotlin integer range")
    }
    return toInt()
}

internal fun Network.toFfi(): FfiNetwork = when (this) {
    Network.MAINNET -> FfiNetwork.MAINNET
    Network.TESTNET -> FfiNetwork.TESTNET
    Network.TESTNET4 -> FfiNetwork.TESTNET4
    Network.SIGNET -> FfiNetwork.SIGNET
    Network.REGTEST -> FfiNetwork.REGTEST
    Network.UTEXO -> FfiNetwork.UTEXO
}

private fun FfiNetwork.toPublic(): Network = when (this) {
    FfiNetwork.MAINNET -> Network.MAINNET
    FfiNetwork.TESTNET -> Network.TESTNET
    FfiNetwork.TESTNET4 -> Network.TESTNET4
    FfiNetwork.SIGNET -> Network.SIGNET
    FfiNetwork.REGTEST -> Network.REGTEST
    FfiNetwork.UTEXO -> Network.UTEXO
}

private fun AccountKind.toFfi(): FfiAccountKind = when (this) {
    AccountKind.VANILLA -> FfiAccountKind.VANILLA
    AccountKind.COLORED -> FfiAccountKind.COLORED
}

private fun Keychain.toFfi(): FfiKeychain = when (this) {
    Keychain.EXTERNAL -> FfiKeychain.EXTERNAL
    Keychain.CHANGE -> FfiKeychain.CHANGE
}

internal fun FfiSignerException.toSdkException(): SdkException = when (this) {
    is FfiSignerException.InvalidArgument -> SdkException.InvalidArgument("localSigner", "input is invalid")
    is FfiSignerException.InvalidMnemonic -> SdkException.InvalidArgument("mnemonic", "is invalid")
    is FfiSignerException.InvalidSeed -> SdkException.InvalidArgument("seed", "is invalid")
    is FfiSignerException.EntropyUnavailable -> SdkException.EntropyUnavailable()
    is FfiSignerException.InvalidNetwork -> SdkException.PolicyRejected("transaction network mismatch")
    is FfiSignerException.InvalidPsbt -> SdkException.PolicyRejected("transaction encoding is invalid")
    is FfiSignerException.PolicyRejected -> SdkException.PolicyRejected("transaction violates local policy")
    is FfiSignerException.KeyDerivation -> SdkException.LocalSigningFailed("key derivation failed")
    is FfiSignerException.SigningFailed -> SdkException.LocalSigningFailed("not every input could be signed")
    is FfiSignerException.SignatureVerification ->
        SdkException.LocalSigningFailed("signature verification failed")
}

package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.AccountKind
import com.utexo.rgb.sdk.AssetAmount
import com.utexo.rgb.sdk.AssetId
import com.utexo.rgb.sdk.Assignment
import com.utexo.rgb.sdk.AssignmentType
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
import com.utexo.rgb.sdk.UnixSeconds
import com.utexo.rgb.sdk.internal.ffi.decodeRgbInvoice as ffiDecodeRgbInvoice
import com.utexo.rgb.sdk.internal.ffi.RgbInvoiceRecipientType as FfiInvoiceRecipientType
import com.utexo.rgb.sdk.internal.ffi.RgbInvoiceAssignmentKind as FfiInvoiceAssignmentKind
import com.utexo.rgb.sdk.internal.ffi.RgbInvoiceEncodedStateKind as FfiInvoiceEncodedStateKind
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
import com.utexo.rgb.sdk.internal.ffi.GatewaySigningIntent as FfiGatewaySigningIntent
import com.utexo.rgb.sdk.internal.ffi.CreateUtxosAuthorization as FfiCreateUtxosAuthorization
import com.utexo.rgb.sdk.internal.ffi.GatewayOwnedOutput as FfiGatewayOwnedOutput
import com.utexo.rgb.sdk.internal.ffi.RgbBitcoinAuthorization as FfiRgbBitcoinAuthorization
import com.utexo.rgb.sdk.internal.ffi.gatewaySigningPolicy as ffiGatewaySigningPolicy
import com.utexo.rgb.sdk.internal.ffi.rgbInvoiceWitnessRecipient as ffiRgbInvoiceWitnessRecipient
import com.utexo.rgb.sdk.internal.ffi.SigningPolicy as FfiSigningPolicy
import com.utexo.rgb.sdk.internal.ffi.ValidationReport as FfiValidationReport
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Concrete private adapter around the generated `utexo-local-signer` bindings. */
internal object UniFfiLocalSigningEngineFactory : LocalSigningEngineFactory {
    override suspend fun open(network: Network, seed: SecretSeed): LocalSigningEngine =
        openWithContext(network, seed, null)

    /** Internal qualification path; ordinary wallet creation does not opt into it. */
    suspend fun openGateway(
        network: Network,
        seed: SecretSeed,
        context: GatewaySigningContext,
    ): LocalSigningEngine = openWithContext(network, seed, context)

    private suspend fun openWithContext(
        network: Network,
        seed: SecretSeed,
        context: GatewaySigningContext?,
    ): LocalSigningEngine {
        var acquired: LocalSigningEngine? = null
        return try {
            withContext(Dispatchers.Default) {
                NativeRuntime.ensureInitialized()
                val bytes = seed.copyForNative()
                try {
                    UniFfiLocalSigningEngine(FfiLocalSigner.fromSeed(network.toFfi(), bytes), context).also { acquired = it }
                } catch (linkage: LinkageError) {
                    throw SdkException.Unsupported("native utexo-local-signer library")
                } catch (exception: FfiSignerException) {
                    throw exception.toSdkException()
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        } catch (failure: Exception) {
            runCatching { acquired?.close() }
            throw failure
        }
    }
}

internal class UniFfiLocalSigningEngine(
    private val signer: FfiLocalSigner,
    private val gatewayContext: GatewaySigningContext? = null,
) : LocalSigningEngine {
    override val capabilities: WalletCapabilities = WalletCapabilities(
        localKeyDerivation = true,
        localTransactionValidation = true,
        localTransactionSigning = gatewayContext?.canSign ?: true,
        localRgbInvoiceParsing = true,
        localRgbValidation = false,
        // The additive profile authorizes Bitcoin intent, not RGB transitions.
        // Backend gates are independent; this is not gateway readiness.
        localSigningOperations = if (gatewayContext != null && !gatewayContext.canSign) emptySet()
            else if (gatewayContext == null) setOf(OperationKind.SEND_BTC)
            else setOf(OperationKind.SEND_BTC, OperationKind.CREATE_UTXOS, OperationKind.ONCHAIN_SEND),
        derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
        signingProfile = if (gatewayContext == null) WalletCapabilities.SIGNING_PROFILE_V1 else GatewaySigningContext.PROFILE,
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

    override suspend fun prepareSigningContext(): Unit { gatewayContext?.validated() }

    override suspend fun verifyReservedAddress(reservation: ReservedAddress): Unit = nativeCall {
        val derived = signer.deriveAddress(
            reservation.account.toFfi(),
            reservation.keychain.toFfi(),
            reservation.index.toUInt(),
        )
        val expected = BitcoinAddress.of(derived.address)
        if (expected != reservation.address || (reservation.scriptPubkeyHex != null &&
                reservation.scriptPubkeyHex != derived.scriptPubkeyHex)) {
            throw SdkException.BackendProtocolMismatch(
                "reserved address does not match its local BIP86 derivation",
            )
        }
    }

    override suspend fun decodeRgbInvoice(invoice: RgbInvoice): LocallyDecodedInvoice = nativeCall {
        val decoded = ffiDecodeRgbInvoice(invoice.value)
        if (decoded.expirationTimestamp?.let { it < 0 } == true) {
            throw SdkException.InvalidArgument("rgbInvoice", "expiration is outside the supported timestamp range")
        }
        LocallyDecodedInvoice(
            data = InvoiceData(
                invoice = invoice,
                recipientId = decoded.recipientId,
                assetSchema = decoded.assetSchema,
                assetId = decoded.assetId?.let(AssetId::of),
                network = decoded.network.toPublic(),
                assignment = Assignment(
                    type = when (decoded.assignment.kind) {
                        FfiInvoiceAssignmentKind.FUNGIBLE -> AssignmentType.FUNGIBLE
                        FfiInvoiceAssignmentKind.NON_FUNGIBLE -> AssignmentType.NON_FUNGIBLE
                        FfiInvoiceAssignmentKind.INFLATION_RIGHT -> AssignmentType.INFLATION_RIGHT
                        FfiInvoiceAssignmentKind.ANY -> AssignmentType.ANY
                    },
                    amount = decoded.assignment.amount?.let { AssetAmount.parse(it.toString()) },
                ),
                assignmentName = decoded.assignmentName,
                expirationTimestamp = decoded.expirationTimestamp?.let(UnixSeconds::of),
                transportEndpoints = decoded.transportEndpoints,
            ),
            witness = when (decoded.recipientType) {
                FfiInvoiceRecipientType.BLIND -> false
                FfiInvoiceRecipientType.WITNESS -> true
            },
            encodedState = when (decoded.encodedState.kind) {
                FfiInvoiceEncodedStateKind.OMITTED -> InvoiceEncodedState.OMITTED
                FfiInvoiceEncodedStateKind.AMOUNT -> InvoiceEncodedState.AMOUNT
                FfiInvoiceEncodedStateKind.NON_FUNGIBLE -> InvoiceEncodedState.NON_FUNGIBLE
            },
            encodedAmount = decoded.encodedState.amount?.let { AssetAmount.parse(it.toString()) },
        )
    }

    override suspend fun validateAndSign(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent,
    ): LocalSigningResult {
        val context = gatewayContext?.validated()
        return nativeCall {
            if (context != null) {
                val result = signer.signGatewayPsbt(prepared.psbtBase64, gatewayIntent(prepared, intent, context))
                LocalSigningResult(result.signedPsbtBase64, result.validation.toPublic())
            } else when (intent) {
                is LocalOperationIntent.SendBtc -> signSendBtc(prepared, intent)
                is LocalOperationIntent.CreateUtxos -> throw SdkException.Unsupported(
                    "complete local createUtxos intent validation and signing",
                )
                is LocalOperationIntent.OnchainSend -> throw SdkException.Unsupported(
                    "complete local onchainSend Bitcoin intent validation and signing",
                )
            }
        }
    }

    private fun signSendBtc(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent.SendBtc,
    ): LocalSigningResult {
        val result = signer.signPsbt(prepared.psbtBase64, sendBtcIntent(prepared, intent))
        return LocalSigningResult(
            signedPsbtBase64 = result.signedPsbtBase64,
            validation = result.validation.toPublic(),
        )
    }

    override suspend fun validateRetainedSigning(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent,
    ): LocalValidationReport {
        val context = gatewayContext?.validated()
        return nativeCall {
            if (context != null) {
                signer.validateGatewayPsbt(prepared.psbtBase64, gatewayIntent(prepared, intent, context)).toPublic()
            } else when (intent) {
                is LocalOperationIntent.SendBtc -> signer.validatePsbt(
                    prepared.psbtBase64, sendBtcIntent(prepared, intent),
                ).toPublic()
                else -> throw SdkException.Unsupported("retained operation policy validation")
            }
        }
    }

    private fun gatewayIntent(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent,
        context: GatewaySigningContext,
    ): FfiGatewaySigningIntent {
        val operation = when (intent) {
            is LocalOperationIntent.SendBtc -> FfiOperationKind.SEND_BTC
            is LocalOperationIntent.CreateUtxos -> FfiOperationKind.CREATE_UTXOS
            is LocalOperationIntent.OnchainSend -> FfiOperationKind.ONCHAIN_SEND
        }
        val expectedKind = when (intent) {
            is LocalOperationIntent.SendBtc -> OperationKind.SEND_BTC
            is LocalOperationIntent.CreateUtxos -> OperationKind.CREATE_UTXOS
            is LocalOperationIntent.OnchainSend -> OperationKind.ONCHAIN_SEND
        }
        if (prepared.operation != expectedKind || prepared.network != intent.network) {
            throw SdkException.InternalInvariantViolation("gateway signer received a different operation or network")
        }
        val requestKey = when (intent) {
            is LocalOperationIntent.SendBtc -> intent.request.idempotencyKey
            is LocalOperationIntent.CreateUtxos -> intent.request.idempotencyKey
            is LocalOperationIntent.OnchainSend -> intent.request.idempotencyKey
        }
        val requestHash = when (intent) {
            is LocalOperationIntent.SendBtc -> RequestHasher.sendBtc(intent.network, intent.request)
            is LocalOperationIntent.CreateUtxos -> RequestHasher.createUtxos(intent.network, intent.request)
            is LocalOperationIntent.OnchainSend -> RequestHasher.onchainSend(intent.network, intent.request)
        }
        if (prepared.idempotencyKey != requestKey || prepared.requestHash != requestHash) {
            throw SdkException.PolicyRejected("prepared gateway transaction is not bound to the caller request")
        }
        val policy = intent.policy
        if (policy.rbf != RbfPolicy.REQUIRED) {
            throw SdkException.PolicyRejected("RGB-lib signing profile requires RBF sequence 0xFFFFFFFD")
        }
        val targetFee = when (intent) {
            is LocalOperationIntent.SendBtc -> intent.request.feeRate
            is LocalOperationIntent.CreateUtxos -> intent.request.feeRate
            is LocalOperationIntent.OnchainSend -> intent.request.feeRate
        }
        val ffiPolicy = ffiGatewaySigningPolicy(operation).copy(
            maxPsbtBytes = policy.maximumPsbtBytes.toUInt(),
            maxInputs = policy.maximumInputs.toUInt(),
            maxOutputs = policy.maximumOutputs.toUInt(),
            maxChildIndex = policy.maximumChildIndex.toUInt(),
            maxAbsoluteFeeSats = policy.maximumAbsoluteFee.value.toULong(),
            maxFeeRateMsatPerVbyte = policy.maximumFeeRate.milliSatsPerVbyte.toULong(),
        )
        val create = (intent as? LocalOperationIntent.CreateUtxos)?.let {
            FfiCreateUtxosAuthorization(
                minCount = 1u,
                maxCount = (it.request.num ?: 5).toUInt(),
                sizeSats = (it.request.size?.value ?: 1_000L).toULong(),
                exactOutputs = it.exactOutputPlan.outputs.map { output ->
                    FfiGatewayOwnedOutput(output.account.toFfi(), output.keychain.toFfi(),
                        output.index.toUInt(), output.amount.value.toULong())
                },
            )
        }
        val rgb = (intent as? LocalOperationIntent.OnchainSend)?.let {
            val asset = it.request.assetId?.value ?: ffiDecodeRgbInvoice(it.request.invoice.value).assetId
                ?: throw SdkException.PolicyRejected("RGB send requires an explicit asset in the request or invoice")
            FfiRgbBitcoinAuthorization(
                invoice = it.request.invoice.value,
                assetId = asset,
                amount = it.request.amount.decimal.toULong(),
                witnessAmountSats = it.request.witnessData?.amountSat?.value?.toULong(),
                witnessBlinding = it.request.witnessData?.blinding?.toULong(),
                donation = it.request.donation,
                minConfirmations = it.request.minConfirmations.value.toUInt(),
                skipSync = it.request.skipSync,
                validationTimeUnixSeconds = context.validationTime().toULong(),
            )
        }
        val recipients = when (intent) {
            is LocalOperationIntent.SendBtc -> listOf(
                FfiRecipient(intent.request.address.value, intent.request.amount.value.toULong()),
            )
            is LocalOperationIntent.CreateUtxos -> emptyList()
            is LocalOperationIntent.OnchainSend -> {
                val decoded = ffiDecodeRgbInvoice(intent.request.invoice.value)
                if (decoded.recipientType == FfiInvoiceRecipientType.WITNESS) {
                    val witnessAmount = intent.request.witnessData?.amountSat
                        ?: throw SdkException.PolicyRejected("witness RGB recipient requires an explicit Bitcoin amount")
                    listOf(ffiRgbInvoiceWitnessRecipient(intent.request.invoice.value, intent.network.toFfi(),
                        witnessAmount.value.toULong()))
                } else {
                    if (intent.request.witnessData != null) {
                        throw SdkException.PolicyRejected("blind RGB recipient cannot have witness payment data")
                    }
                    emptyList()
                }
            }
        }
        return FfiGatewaySigningIntent(
            intent = FfiSigningIntent(
                operationId = prepared.operationId.value,
                idempotencyKey = prepared.idempotencyKey.value,
                network = intent.network.toFfi(),
                operation = operation,
                recipients = recipients,
                policy = ffiPolicy,
            ),
            maxBlockHeight = context.maximumBlockHeight.toUInt(),
            targetFeeRateMsatPerVbyte = targetFee.milliSatsPerVbyte.toULong(),
            createUtxos = create,
            rgb = rgb,
        )
    }

    private fun sendBtcIntent(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent.SendBtc,
    ): FfiSigningIntent {
        if (prepared.operation != OperationKind.SEND_BTC || prepared.network != intent.network) {
            throw SdkException.InternalInvariantViolation("sendBtc signer received another operation or network")
        }
        if (prepared.idempotencyKey != intent.request.idempotencyKey ||
            prepared.requestHash != RequestHasher.sendBtc(intent.network, intent.request)
        ) {
            throw SdkException.PolicyRejected("prepared transaction is not bound to the caller request")
        }
        if (intent.policy.rbf != RbfPolicy.REQUIRED) {
            throw SdkException.PolicyRejected("signing profile v1 requires RBF sequence 0xFFFFFFFD")
        }
        val maximumFeeRate = minOf(
            intent.policy.maximumFeeRate.milliSatsPerVbyte,
            intent.request.feeRate.milliSatsPerVbyte,
        )
        return FfiSigningIntent(
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
    }

    override fun close(): Unit = signer.close()

    private suspend fun <T> nativeCall(block: () -> T): T = withContext(Dispatchers.Default) {
        try {
            NativeRuntime.ensureInitialized()
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
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
    is FfiSignerException.InvalidRgbInvoice -> SdkException.InvalidArgument("rgbInvoice", "is invalid or unsupported")
    is FfiSignerException.PolicyRejected -> SdkException.PolicyRejected("transaction violates local policy")
    is FfiSignerException.KeyDerivation -> SdkException.LocalSigningFailed("key derivation failed")
    is FfiSignerException.SigningFailed -> SdkException.LocalSigningFailed("not every input could be signed")
    is FfiSignerException.SignatureVerification ->
        SdkException.LocalSigningFailed("signature verification failed")
}

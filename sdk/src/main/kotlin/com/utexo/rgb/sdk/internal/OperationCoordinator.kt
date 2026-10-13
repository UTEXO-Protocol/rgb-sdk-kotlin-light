package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.BackendContext
import com.utexo.rgb.sdk.BackendOperationResult
import com.utexo.rgb.sdk.BackendOperationStatus
import com.utexo.rgb.sdk.CreateUtxosOutputPlan
import com.utexo.rgb.sdk.CreateUtxosRequest
import com.utexo.rgb.sdk.CreateUtxosResult
import com.utexo.rgb.sdk.IdempotencyKey
import com.utexo.rgb.sdk.OnchainSendRequest
import com.utexo.rgb.sdk.OnchainSendResponse
import com.utexo.rgb.sdk.OperationCheckpoint
import com.utexo.rgb.sdk.OperationJournal
import com.utexo.rgb.sdk.OperationKind
import com.utexo.rgb.sdk.OperationPhase
import com.utexo.rgb.sdk.OperationRequestContext
import com.utexo.rgb.sdk.OperationTerminalOutcome
import com.utexo.rgb.sdk.PrepareCreateUtxosRequest
import com.utexo.rgb.sdk.PreparedTransaction
import com.utexo.rgb.sdk.PrepareOnchainSendRequest
import com.utexo.rgb.sdk.PrepareSendBtcRequest
import com.utexo.rgb.sdk.RequestHash
import com.utexo.rgb.sdk.RgbLightningNodeGatewayBackend
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.SendBtcRequest
import com.utexo.rgb.sdk.SignedOperationPayload
import com.utexo.rgb.sdk.SignedTransactionSubmission
import com.utexo.rgb.sdk.TransactionId
import com.utexo.rgb.sdk.WalletBackend
import com.utexo.rgb.sdk.WalletConfiguration
import kotlinx.coroutines.CancellationException

internal fun interface EpochSecondsSource {
    fun now(): Long
}

internal class OperationCoordinator(
    private val configuration: WalletConfiguration,
    private val backend: WalletBackend,
    private val journal: OperationJournal,
    private val now: EpochSecondsSource = EpochSecondsSource { System.currentTimeMillis() / 1_000L },
) {
    suspend fun sendBtc(
        backendContext: BackendContext,
        signer: LocalSigningEngine,
        request: SendBtcRequest,
    ): TransactionId {
        val localPreflight: suspend () -> Unit = {
            requireLocalSigning(signer, OperationKind.SEND_BTC)
            if (backend is RgbLightningNodeGatewayBackend) backend.validateRequest(request)
            signer.prepareSigningContext()
        }
        val hash = RequestHasher.sendBtc(configuration.network, request)
        val operationContext = OperationRequestContext(
            backend = backendContext,
            idempotencyKey = request.idempotencyKey,
            requestHash = hash,
        )
        val existing = loadValidated(
            request.idempotencyKey,
            hash,
            OperationKind.SEND_BTC,
        )
        if (existing?.phase == OperationPhase.TERMINAL) {
            return requireSendBtcResult(requireTerminalResult(existing)).txid
        }
        if (existing?.phase == OperationPhase.SUBMITTING) {
            return requireSendBtcResult(recoverSubmitting(operationContext, existing, request)).txid
        }
        val current = existing ?: run {
            requireFeeWithinPolicy(request.feeRate.milliSatsPerVbyte)
            createAfterPreflight(
                request.idempotencyKey,
                hash,
                OperationKind.SEND_BTC,
                localPreflight,
            )
        }
        val prepared = ensurePrepared(
            checkpoint = current,
            context = operationContext,
            expectedKind = OperationKind.SEND_BTC,
            createdPreflightSatisfied = existing == null,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            prepare = {
                backendCall {
                    backend.prepareSendBtc(PrepareSendBtcRequest(operationContext, request))
                }
            },
        )
        val signed = ensureSigned(
            checkpoint = prepared,
            signer = signer,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            intent = LocalOperationIntent.SendBtc(
                configuration.network,
                configuration.signingPolicy,
                request,
            ),
        )
        val result = submitPersisted(
            context = operationContext,
            checkpoint = signed,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            sendBtcRequest = request,
            signer = signer,
            intent = LocalOperationIntent.SendBtc(configuration.network, configuration.signingPolicy, request),
        )
        return requireSendBtcResult(result).txid
    }

    suspend fun createUtxos(
        backendContext: BackendContext,
        signer: LocalSigningEngine,
        request: CreateUtxosRequest,
    ): CreateUtxosResult {
        val localPreflight: suspend () -> Unit = {
            requireLocalSigning(signer, OperationKind.CREATE_UTXOS)
            if (backend is RgbLightningNodeGatewayBackend) backend.validateRequest(request)
            signer.prepareSigningContext()
        }
        val hash = RequestHasher.createUtxos(configuration.network, request)
        val operationContext = OperationRequestContext(
            backend = backendContext,
            idempotencyKey = request.idempotencyKey,
            requestHash = hash,
        )
        val existing = loadValidated(
            request.idempotencyKey,
            hash,
            OperationKind.CREATE_UTXOS,
        )
        if (existing?.phase == OperationPhase.TERMINAL) {
            val result = requireCreateUtxosResult(requireTerminalResult(existing)).result
            val maximum = request.num ?: 5
            if (result.createdUtxoCount !in 1..maximum) {
                throw SdkException.BackendProtocolMismatch("retained create UTXO count differs from the original request")
            }
            return result
        }
        if (existing?.phase == OperationPhase.SUBMITTING) {
            return requireCreateUtxosResult(recoverSubmitting(operationContext, existing, createUtxosRequest = request)).result
        }
        val current = existing ?: run {
            requireFeeWithinPolicy(request.feeRate.milliSatsPerVbyte)
            createAfterPreflight(
                request.idempotencyKey,
                hash,
                OperationKind.CREATE_UTXOS,
                localPreflight,
            )
        }
        val prepared = ensurePrepared(
            checkpoint = current,
            context = operationContext,
            expectedKind = OperationKind.CREATE_UTXOS,
            createdPreflightSatisfied = existing == null,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            prepare = {
                backendCall {
                    backend.prepareCreateUtxos(PrepareCreateUtxosRequest(operationContext, request))
                }
            },
            verifyOperation = { transaction ->
                val plan = transaction.createUtxosOutputPlan
                    ?: throw protocolMismatch(
                        "createUtxos response has no exact output plan",
                        transaction,
                    )
                verifyCreateUtxosPlan(request, plan, transaction)
            },
        )
        val preparedTransaction = requireNotNull(prepared.preparedTransaction)
        val plan = preparedTransaction.createUtxosOutputPlan
            ?: throw protocolMismatch("createUtxos response has no exact output plan", preparedTransaction)
        val signed = ensureSigned(
            checkpoint = prepared,
            signer = signer,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            intent = LocalOperationIntent.CreateUtxos(
                network = configuration.network,
                policy = configuration.signingPolicy,
                request = request,
                exactOutputPlan = plan,
            ),
        )
        val result = submitPersisted(
            context = operationContext,
            checkpoint = signed,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            createUtxosRequest = request,
            signer = signer,
            intent = LocalOperationIntent.CreateUtxos(configuration.network, configuration.signingPolicy, request, plan),
        )
        return requireCreateUtxosResult(result).result
    }

    suspend fun onchainSend(
        backendContext: BackendContext,
        signer: LocalSigningEngine,
        request: OnchainSendRequest,
        validateInvoice: suspend () -> Unit,
    ): OnchainSendResponse {
        val localPreflight: suspend () -> Unit = {
            requireLocalSigning(signer, OperationKind.ONCHAIN_SEND)
            if (backend is RgbLightningNodeGatewayBackend) backend.validateRequest(request)
            validateInvoice()
            signer.prepareSigningContext()
        }
        val hash = RequestHasher.onchainSend(configuration.network, request)
        val operationContext = OperationRequestContext(
            backend = backendContext,
            idempotencyKey = request.idempotencyKey,
            requestHash = hash,
        )
        val existing = loadValidated(
            request.idempotencyKey,
            hash,
            OperationKind.ONCHAIN_SEND,
        )
        if (existing?.phase == OperationPhase.TERMINAL) {
            return requireOnchainSendResult(requireTerminalResult(existing)).result
        }
        if (existing?.phase == OperationPhase.SUBMITTING) {
            return requireOnchainSendResult(recoverSubmitting(operationContext, existing, onchainSendRequest = request)).result
        }
        val current = existing ?: run {
            requireFeeWithinPolicy(request.feeRate.milliSatsPerVbyte)
            createAfterPreflight(
                request.idempotencyKey,
                hash,
                OperationKind.ONCHAIN_SEND,
                localPreflight,
            )
        }
        val prepared = ensurePrepared(
            checkpoint = current,
            context = operationContext,
            expectedKind = OperationKind.ONCHAIN_SEND,
            createdPreflightSatisfied = existing == null,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            prepare = {
                backendCall {
                    backend.prepareOnchainSend(PrepareOnchainSendRequest(operationContext, request))
                }
            },
        )
        val signed = ensureSigned(
            checkpoint = prepared,
            signer = signer,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            intent = LocalOperationIntent.OnchainSend(
                network = configuration.network,
                policy = configuration.signingPolicy,
                request = request,
            ),
        )
        val result = submitPersisted(
            context = operationContext,
            checkpoint = signed,
            requestedFeeRate = request.feeRate.milliSatsPerVbyte,
            localPreflight = localPreflight,
            onchainSendRequest = request,
            signer = signer,
            intent = LocalOperationIntent.OnchainSend(configuration.network, configuration.signingPolicy, request),
        )
        return requireOnchainSendResult(result).result
    }

    private suspend fun submitPersisted(
        context: OperationRequestContext,
        checkpoint: OperationCheckpoint,
        requestedFeeRate: Long,
        sendBtcRequest: SendBtcRequest? = null,
        createUtxosRequest: CreateUtxosRequest? = null,
        onchainSendRequest: OnchainSendRequest? = null,
        signer: LocalSigningEngine,
        intent: LocalOperationIntent,
        localPreflight: suspend () -> Unit,
    ): BackendOperationResult {
        val prepared = checkpoint.preparedTransaction
            ?: throw SdkException.InternalInvariantViolation("signed checkpoint has no preparation")
        val signed = checkpoint.signedPayload
            ?: throw SdkException.InternalInvariantViolation("signed checkpoint has no payload")
        val submitting = when (checkpoint.phase) {
            OperationPhase.SIGNED -> {
                if (prepared.expiresAt.value <= now.now()) {
                    persistExpiredAndThrow(checkpoint)
                }
                requireFeeWithinPolicy(requestedFeeRate)
                localPreflight()
                backendPreflight(checkpoint.operationKind)
                localPreflight()
                val current = signer.validateRetainedSigning(prepared, intent)
                if (current.copy(intentSha256 = signed.validation.intentSha256) != signed.validation) {
                    throw SdkException.InternalInvariantViolation("pre-submit signing validation changed")
                }
                if (prepared.expiresAt.value <= now.now()) {
                    persistExpiredAndThrow(checkpoint)
                }
                checkpoint.copy(phase = OperationPhase.SUBMITTING).also { journalSave(it) }
            }
            OperationPhase.SUBMITTING -> checkpoint
            else -> throw SdkException.InternalInvariantViolation(
                "only a signed transaction can be submitted",
            )
        }
        val submission = SignedTransactionSubmission(
            context = context,
            operationId = prepared.operationId,
            signedPsbtBase64 = signed.signedPsbtBase64,
            validation = signed.validation,
        )
        val result = try {
            backendCall {
                if (backend is RgbLightningNodeGatewayBackend) backend.submitSignedTransaction(submission, prepared)
                else backend.submitSignedTransaction(submission)
            }
        } catch (cancellation: CancellationException) {
            // SUBMITTING was persisted first. Cancellation stops waiting; it
            // does not imply that backend processing or broadcast was undone.
            throw cancellation
        } catch (_: SdkException.BackendUnavailable) {
            return reconcileAfterSubmit(context.backend, submitting, sendBtcRequest, createUtxosRequest, onchainSendRequest)
        } catch (expired: SdkException.OperationExpired) {
            if (expired.operationId != prepared.operationId) {
                throw SdkException.OperationAmbiguous(prepared.operationId)
            }
            persistExpiredAndThrow(submitting)
        } catch (rejected: SdkException.BackendRejected) {
            persistDirectRejectionOrThrow(submitting, rejected)
        } catch (ambiguous: SdkException.OperationAmbiguous) {
            if (ambiguous.operationId != prepared.operationId) {
                throw SdkException.OperationAmbiguous(prepared.operationId)
            }
            if (backend is RgbLightningNodeGatewayBackend) {
                return reconcileAfterSubmit(context.backend, submitting, sendBtcRequest, createUtxosRequest, onchainSendRequest)
            }
            throw ambiguous
        } catch (failure: SdkException) {
            // Custom infrastructure backends retain their error contract. An API
            // error is not proof of no broadcast: preserve SUBMITTING rather than
            // inventing a terminal receipt. The concrete gateway maps uncertain
            // completion responses to OperationAmbiguous for one GET reconciliation.
            throw failure
        }
        verifyResultIdentityOrAmbiguous(result, submitting)
        journalSave(
            submitting.copy(
                phase = OperationPhase.TERMINAL,
                terminalOutcome = OperationTerminalOutcome.Succeeded(result),
            ),
        )
        return result
    }

    private fun requireLocalSigning(signer: LocalSigningEngine, operation: OperationKind): Unit {
        val capabilities = signer.capabilities
        if (!capabilities.localTransactionValidation || !capabilities.localTransactionSigning ||
            operation !in capabilities.localSigningOperations) {
            throw SdkException.Unsupported("complete local ${operation.wireValue} Bitcoin intent validation and signing")
        }
    }

    private suspend fun loadValidated(
        idempotencyKey: IdempotencyKey,
        hash: RequestHash,
        kind: OperationKind,
    ): OperationCheckpoint? {
        val current = journalLoad(configuration.walletId, idempotencyKey)
        if (current != null) {
            if (current.requestHash != hash || current.operationKind != kind) {
                throw SdkException.OperationConflict(idempotencyKey)
            }
        }
        return current
    }

    private suspend fun createAfterPreflight(
        idempotencyKey: IdempotencyKey,
        hash: RequestHash,
        kind: OperationKind,
        localPreflight: suspend () -> Unit,
    ): OperationCheckpoint {
        // A deterministic readiness failure must occur before CREATED is
        // persisted, so a disabled provisional profile leaves no checkpoint.
        localPreflight()
        backendPreflight(kind)
        localPreflight()
        val created = OperationCheckpoint(
            walletId = configuration.walletId,
            idempotencyKey = idempotencyKey,
            requestHash = hash,
            operationKind = kind,
            phase = OperationPhase.CREATED,
        )
        journalSave(created)
        return created
    }

    private suspend fun ensurePrepared(
        checkpoint: OperationCheckpoint,
        context: OperationRequestContext,
        expectedKind: OperationKind,
        createdPreflightSatisfied: Boolean,
        requestedFeeRate: Long,
        localPreflight: suspend () -> Unit,
        prepare: suspend () -> PreparedTransaction,
        verifyOperation: (PreparedTransaction) -> Unit = {},
    ): OperationCheckpoint {
        if (checkpoint.phase == OperationPhase.CREATED) {
            if (!createdPreflightSatisfied) {
                requireFeeWithinPolicy(requestedFeeRate)
                localPreflight()
                backendPreflight(expectedKind)
            }
            localPreflight()
            val prepared = prepare()
            verifyPrepared(prepared, context, expectedKind)
            verifyOperation(prepared)
            if (prepared.expiresAt.value <= now.now()) {
                val expired = checkpoint.copy(
                    phase = OperationPhase.TERMINAL,
                    operationId = prepared.operationId,
                    preparedTransaction = prepared,
                    terminalOutcome = OperationTerminalOutcome.Expired(prepared.operationId),
                )
                journalSave(expired)
                throw SdkException.OperationExpired(prepared.operationId)
            }
            return checkpoint.copy(
                phase = OperationPhase.PREPARED,
                operationId = prepared.operationId,
                preparedTransaction = prepared,
            ).also { journalSave(it) }
        }
        val prepared = checkpoint.preparedTransaction
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no prepared transaction")
        verifyPrepared(prepared, context, expectedKind)
        verifyOperation(prepared)
        if ((checkpoint.phase == OperationPhase.PREPARED ||
                checkpoint.phase == OperationPhase.SIGNED
            ) && prepared.expiresAt.value <= now.now()
        ) {
            journalSave(
                checkpoint.copy(
                    phase = OperationPhase.TERMINAL,
                    terminalOutcome = OperationTerminalOutcome.Expired(prepared.operationId),
                ),
            )
            throw SdkException.OperationExpired(prepared.operationId)
        }
        return checkpoint
    }

    private suspend fun ensureSigned(
        checkpoint: OperationCheckpoint,
        signer: LocalSigningEngine,
        requestedFeeRate: Long,
        intent: LocalOperationIntent,
        localPreflight: suspend () -> Unit,
    ): OperationCheckpoint {
        if (checkpoint.phase == OperationPhase.SIGNED) {
            val prepared = checkpoint.preparedTransaction
                ?: throw SdkException.InternalInvariantViolation("signed checkpoint has no preparation")
            val retained = checkpoint.signedPayload
                ?: throw SdkException.InternalInvariantViolation("signed checkpoint has no payload")
            requireFeeWithinPolicy(requestedFeeRate)
            localPreflight()
            backendPreflight(checkpoint.operationKind)
            localPreflight()
            val current = signer.validateRetainedSigning(prepared, intent)
            // Policy contributes to the intent digest. All transaction facts and
            // preparation binding must remain identical; never replace signed bytes.
            if (current.copy(intentSha256 = retained.validation.intentSha256) != retained.validation) {
                throw SdkException.InternalInvariantViolation("retained signing validation changed")
            }
            return checkpoint
        }
        if (checkpoint.phase != OperationPhase.PREPARED) {
            throw SdkException.InternalInvariantViolation("checkpoint cannot enter signing")
        }
        val prepared = checkpoint.preparedTransaction
            ?: throw SdkException.InternalInvariantViolation("prepared checkpoint has no transaction")
        requireFeeWithinPolicy(requestedFeeRate)
        localPreflight()
        backendPreflight(checkpoint.operationKind)
        localPreflight()
        if (prepared.expiresAt.value <= now.now()) {
            persistExpiredAndThrow(checkpoint)
        }
        val signed = signer.validateAndSign(prepared, intent)
        val next = checkpoint.copy(
            phase = OperationPhase.SIGNED,
            signedPayload = SignedOperationPayload(
                signedPsbtBase64 = signed.signedPsbtBase64,
                validation = signed.validation,
            ),
        )
        journalSave(next)
        return next
    }

    private suspend fun recoverSubmitting(
        context: OperationRequestContext,
        checkpoint: OperationCheckpoint,
        sendBtcRequest: SendBtcRequest? = null,
        createUtxosRequest: CreateUtxosRequest? = null,
        onchainSendRequest: OnchainSendRequest? = null,
    ): BackendOperationResult {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("submitting checkpoint has no operation ID")
        val status = try {
            tryGetOperation(context.backend, checkpoint, sendBtcRequest, createUtxosRequest, onchainSendRequest)
        } catch (_: SdkException) {
            throw SdkException.OperationAmbiguous(operationId)
        }
        return when (status) {
            is BackendOperationStatus.Succeeded -> {
                verifyResultIdentityOrAmbiguous(status.result, checkpoint)
                journalSave(
                    checkpoint.copy(
                        phase = OperationPhase.TERMINAL,
                        terminalOutcome = OperationTerminalOutcome.Succeeded(status.result),
                    ),
                )
                status.result
            }
            is BackendOperationStatus.Expired -> persistExpiredAndThrow(checkpoint)
            is BackendOperationStatus.Failed -> persistFailedAndThrow(checkpoint, status)
            // Without a frozen backend resubmission contract, Pending cannot
            // prove whether broadcast is still in flight. Poll on the next
            // same-key retry; never issue a second submit automatically.
            is BackendOperationStatus.Pending -> throw SdkException.OperationAmbiguous(operationId)
        }
    }

    private suspend fun reconcileAfterSubmit(
        backendContext: BackendContext,
        checkpoint: OperationCheckpoint,
        sendBtcRequest: SendBtcRequest? = null,
        createUtxosRequest: CreateUtxosRequest? = null,
        onchainSendRequest: OnchainSendRequest? = null,
    ): BackendOperationResult {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("submitting checkpoint has no operation ID")
        return when (val status = try {
            tryGetOperation(backendContext, checkpoint, sendBtcRequest, createUtxosRequest, onchainSendRequest)
        } catch (_: SdkException) {
            throw SdkException.OperationAmbiguous(operationId)
        }) {
            is BackendOperationStatus.Succeeded -> {
                verifyResultIdentityOrAmbiguous(status.result, checkpoint)
                journalSave(
                    checkpoint.copy(
                        phase = OperationPhase.TERMINAL,
                        terminalOutcome = OperationTerminalOutcome.Succeeded(status.result),
                    ),
                )
                status.result
            }
            is BackendOperationStatus.Expired -> persistExpiredAndThrow(checkpoint)
            is BackendOperationStatus.Failed -> persistFailedAndThrow(checkpoint, status)
            is BackendOperationStatus.Pending ->
                throw SdkException.OperationAmbiguous(operationId)
        }
    }

    private suspend fun tryGetOperation(
        context: BackendContext,
        checkpoint: OperationCheckpoint,
        sendBtcRequest: SendBtcRequest?,
        createUtxosRequest: CreateUtxosRequest?,
        onchainSendRequest: OnchainSendRequest?,
    ): BackendOperationStatus {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("recovery checkpoint has no operation ID")
        val status = backendCall {
            if (backend is RgbLightningNodeGatewayBackend) {
                when {
                    sendBtcRequest != null -> backend.getOperationForRecovery(context, checkpoint, sendBtcRequest)
                    createUtxosRequest != null -> backend.getOperationForRecovery(context, checkpoint, createUtxosRequest)
                    onchainSendRequest != null -> backend.getOperationForRecovery(context, checkpoint, onchainSendRequest)
                    else -> throw SdkException.Unsupported("gateway recovery for this operation kind")
                }
            } else {
                backend.getOperation(context, operationId)
            }
        }
        if (status.operationId != operationId) {
            throw SdkException.BackendProtocolMismatch(
                reason = "operation status has the wrong operation ID",
                operationId = operationId,
            )
        }
        return status
    }

    private fun verifyPrepared(
        prepared: PreparedTransaction,
        context: OperationRequestContext,
        expectedKind: OperationKind,
    ): Unit {
        if (prepared.network != configuration.network) {
            throw SdkException.WrongNetwork(configuration.network, prepared.network)
        }
        if (prepared.operation != expectedKind) {
            throw protocolMismatch("prepared operation has the wrong kind", prepared)
        }
        if (prepared.idempotencyKey != context.idempotencyKey) {
            throw protocolMismatch("prepared operation changed the idempotency key", prepared)
        }
        if (prepared.requestHash != context.requestHash) {
            throw protocolMismatch("prepared operation changed the request hash", prepared)
        }
        val maximumEncodedBytes = configuration.signingPolicy.maximumPsbtBytes
            .toLong()
            .times(4L)
            .div(3L)
            .plus(8L)
        if (prepared.psbtBase64.toByteArray(Charsets.UTF_8).size.toLong() > maximumEncodedBytes) {
            throw SdkException.PolicyRejected("prepared PSBT exceeds the local size limit", prepared.operationId)
        }
    }

    private fun verifyCreateUtxosPlan(
        request: CreateUtxosRequest,
        plan: CreateUtxosOutputPlan,
        prepared: PreparedTransaction,
    ): Unit {
        requireColoredExternalOutputs(plan)
        if (plan.outputs.any { it.keychain != com.utexo.rgb.sdk.Keychain.EXTERNAL }) {
            throw SdkException.PolicyRejected(
                "createUtxos output plan contains a non-external output",
                prepared.operationId,
            )
        }
        val uniquePaths = plan.outputs.map { Triple(it.account, it.keychain, it.index) }.toSet()
        if (uniquePaths.size != plan.outputs.size) {
            throw SdkException.PolicyRejected(
                "createUtxos output plan repeats a derivation path",
                prepared.operationId,
            )
        }
        run {
            val requested = request.num ?: 5
            // Native coin selection can create fewer outputs even for upTo=false.
            // upTo controls subtracting existing slots, not an exact-count guarantee.
            val valid = plan.outputs.size <= requested
            if (!valid) {
                throw SdkException.PolicyRejected(
                    "createUtxos output count does not match the caller request",
                    prepared.operationId,
                )
            }
        }
        run {
            val requestedSize = request.size ?: com.utexo.rgb.sdk.Satoshis.of(1_000)
            if (plan.outputs.any { it.amount != requestedSize }) {
                throw SdkException.PolicyRejected(
                    "createUtxos output amount does not match the caller request",
                    prepared.operationId,
                )
            }
        }
        if (plan.outputs.size > configuration.signingPolicy.maximumOutputs) {
            throw SdkException.PolicyRejected(
                "createUtxos output count exceeds local policy",
                prepared.operationId,
            )
        }
    }

    private fun requireFeeWithinPolicy(requestedMilliSatsPerVbyte: Long): Unit {
        if (requestedMilliSatsPerVbyte > configuration.signingPolicy.maximumFeeRate.milliSatsPerVbyte) {
            throw SdkException.PolicyRejected("requested fee rate exceeds wallet policy")
        }
    }

    private suspend fun backendPreflight(operation: OperationKind): Unit =
        backendCall { backend.preflightOperation(operation) }

    private fun verifyResultIdentity(
        result: BackendOperationResult,
        checkpoint: OperationCheckpoint,
    ): Unit {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no operation ID")
        fun mismatch(reason: String): SdkException.BackendProtocolMismatch =
            SdkException.BackendProtocolMismatch(reason, operationId, checkpoint.preparedTransaction?.correlationId)
        if (result.operationId != checkpoint.operationId) {
            throw mismatch("submission result has the wrong operation ID")
        }
        val variantMatches = when (checkpoint.operationKind) {
            OperationKind.SEND_BTC -> result is BackendOperationResult.SendBtc
            OperationKind.CREATE_UTXOS -> result is BackendOperationResult.CreateUtxos
            OperationKind.ONCHAIN_SEND -> result is BackendOperationResult.OnchainSend
            OperationKind.INFLATE -> false
        }
        if (!variantMatches) {
            throw mismatch("submission result has the wrong operation variant")
        }
        val expectedTxid = checkpoint.signedPayload?.validation?.unsignedTxid
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no local validation report")
        val actualTxid = when (result) {
            is BackendOperationResult.SendBtc -> result.txid
            is BackendOperationResult.CreateUtxos -> result.result.txid
            is BackendOperationResult.OnchainSend -> result.result.txid
        }
        if (actualTxid != expectedTxid) {
            throw mismatch("backend transaction ID differs from local validation")
        }
        if (result is BackendOperationResult.CreateUtxos) {
            val plan = checkpoint.preparedTransaction?.createUtxosOutputPlan
            // A compact authenticated terminal receipt intentionally removes
            // the PSBT/plan after the first result has passed this comparison.
            if (plan == null && (checkpoint.phase != OperationPhase.TERMINAL ||
                checkpoint.signedPayload?.isTerminalReceipt != true)) {
                throw mismatch("completed createUtxos operation has no retained output plan")
            }
            if (result.result.createdUtxoCount !in 1..255 ||
                (plan != null && result.result.createdUtxoCount != plan.outputs.size)) {
                throw mismatch("backend created UTXO count differs from the locally validated output plan")
            }
        }
    }

    private fun verifyResultIdentityOrAmbiguous(
        result: BackendOperationResult,
        checkpoint: OperationCheckpoint,
    ): Unit = try {
        verifyResultIdentity(result, checkpoint)
    } catch (_: SdkException.BackendProtocolMismatch) {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no operation ID")
        throw SdkException.OperationAmbiguous(operationId)
    }

    private fun requireTerminalResult(checkpoint: OperationCheckpoint): BackendOperationResult {
        return when (val outcome = checkpoint.terminalOutcome) {
            is OperationTerminalOutcome.Succeeded -> {
                verifyResultIdentity(outcome.result, checkpoint)
                outcome.result
            }
            is OperationTerminalOutcome.Expired -> throw SdkException.OperationExpired(
                outcome.operationId,
            )
            is OperationTerminalOutcome.Failed -> throw outcome.asException()
            null -> throw SdkException.InternalInvariantViolation(
                "terminal checkpoint has no outcome",
            )
        }
    }

    private suspend fun persistExpiredAndThrow(checkpoint: OperationCheckpoint): Nothing {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no operation ID")
        journalSave(
            checkpoint.copy(
                phase = OperationPhase.TERMINAL,
                terminalOutcome = OperationTerminalOutcome.Expired(operationId),
            ),
        )
        throw SdkException.OperationExpired(operationId)
    }

    private suspend fun persistFailedAndThrow(
        checkpoint: OperationCheckpoint,
        status: BackendOperationStatus.Failed,
    ): Nothing {
        val outcome = OperationTerminalOutcome.Failed(
            operationId = status.operationId,
            safeMessage = status.safeMessage,
            retryable = status.retryable,
            backendCode = status.backendCode,
            correlationId = status.correlationId,
        )
        journalSave(
            checkpoint.copy(
                phase = OperationPhase.TERMINAL,
                terminalOutcome = outcome,
            ),
        )
        throw outcome.asException()
    }

    private suspend fun persistDirectRejectionOrThrow(
        checkpoint: OperationCheckpoint,
        rejected: SdkException.BackendRejected,
    ): Nothing {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no operation ID")
        if (rejected.operationId != operationId) {
            throw SdkException.OperationAmbiguous(operationId)
        }
        if (rejected.retryable) {
            // A retryable rejection has no durable terminal representation.
            // Preserve SUBMITTING so the caller cannot blindly resubmit.
            throw rejected
        }
        val outcome = try {
            OperationTerminalOutcome.Failed(
                operationId = operationId,
                safeMessage = rejected.message ?: "The gateway rejected the transaction",
                retryable = false,
                backendCode = rejected.details["backendCode"],
                correlationId = rejected.correlationId,
            )
        } catch (_: SdkException.InvalidArgument) {
            throw SdkException.OperationAmbiguous(operationId)
        }
        journalSave(
            checkpoint.copy(
                phase = OperationPhase.TERMINAL,
                terminalOutcome = outcome,
            ),
        )
        throw outcome.asException()
    }

    private suspend fun journalLoad(
        walletId: com.utexo.rgb.sdk.WalletId,
        idempotencyKey: IdempotencyKey,
    ): OperationCheckpoint? = try {
        journal.load(walletId, idempotencyKey)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (sdk: SdkException) {
        throw sdk
    } catch (_: Exception) {
        throw SdkException.OperationJournalUnavailable()
    }

    private suspend fun journalSave(checkpoint: OperationCheckpoint): Unit = try {
        journal.save(checkpoint)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (sdk: SdkException) {
        throw sdk
    } catch (_: Exception) {
        throw SdkException.OperationJournalUnavailable()
    }

    private fun requireSendBtcResult(result: BackendOperationResult): BackendOperationResult.SendBtc =
        result as? BackendOperationResult.SendBtc
            ?: throw SdkException.BackendProtocolMismatch(
                "sendBtc returned a different operation result type",
                result.operationId,
            )

    private fun requireCreateUtxosResult(
        result: BackendOperationResult,
    ): BackendOperationResult.CreateUtxos =
        result as? BackendOperationResult.CreateUtxos
            ?: throw SdkException.BackendProtocolMismatch(
                "createUtxos returned a different operation result type",
                result.operationId,
            )

    private fun requireOnchainSendResult(
        result: BackendOperationResult,
    ): BackendOperationResult.OnchainSend =
        result as? BackendOperationResult.OnchainSend
            ?: throw SdkException.BackendProtocolMismatch(
                "onchainSend returned a different operation result type",
                result.operationId,
            )

    private fun protocolMismatch(
        reason: String,
        prepared: PreparedTransaction,
    ): SdkException.BackendProtocolMismatch = SdkException.BackendProtocolMismatch(
        reason = reason,
        operationId = prepared.operationId,
        correlationId = prepared.correlationId,
    )

    private fun BackendOperationStatus.Failed.asException(): SdkException.BackendRejected =
        SdkException.BackendRejected(
            safeMessage = safeMessage,
            retryable = retryable,
            operationId = operationId,
            correlationId = correlationId,
            backendCode = backendCode,
        )

    private fun OperationTerminalOutcome.Failed.asException(): SdkException.BackendRejected =
        SdkException.BackendRejected(
            safeMessage = safeMessage,
            retryable = retryable,
            operationId = operationId,
            correlationId = correlationId,
            backendCode = backendCode,
        )
}

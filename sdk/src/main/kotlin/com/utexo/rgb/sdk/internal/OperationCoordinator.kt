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
        requireFeeWithinPolicy(request.feeRate.milliSatsPerVbyte)
        val hash = RequestHasher.sendBtc(configuration.network, request)
        val operationContext = OperationRequestContext(
            backend = backendContext,
            idempotencyKey = request.idempotencyKey,
            requestHash = hash,
        )
        val current = loadOrCreate(
            request.idempotencyKey,
            hash,
            OperationKind.SEND_BTC,
        )
        if (current.phase == OperationPhase.TERMINAL) {
            return requireSendBtcResult(requireTerminalResult(current)).txid
        }
        if (current.phase == OperationPhase.SUBMITTING) {
            return requireSendBtcResult(recoverSubmitting(operationContext, current)).txid
        }
        val prepared = ensurePrepared(
            checkpoint = current,
            context = operationContext,
            expectedKind = OperationKind.SEND_BTC,
            prepare = {
                backendCall {
                    backend.prepareSendBtc(PrepareSendBtcRequest(operationContext, request))
                }
            },
        )
        val signed = ensureSigned(
            checkpoint = prepared,
            signer = signer,
            LocalOperationIntent.SendBtc(configuration.network, configuration.signingPolicy, request),
        )
        val result = submitPersisted(operationContext, signed)
        return requireSendBtcResult(result).txid
    }

    suspend fun createUtxos(
        backendContext: BackendContext,
        signer: LocalSigningEngine,
        request: CreateUtxosRequest,
    ): CreateUtxosResult {
        requireFeeWithinPolicy(request.feeRate.milliSatsPerVbyte)
        val hash = RequestHasher.createUtxos(configuration.network, request)
        val operationContext = OperationRequestContext(
            backend = backendContext,
            idempotencyKey = request.idempotencyKey,
            requestHash = hash,
        )
        val current = loadOrCreate(
            request.idempotencyKey,
            hash,
            OperationKind.CREATE_UTXOS,
        )
        if (current.phase == OperationPhase.TERMINAL) {
            return requireCreateUtxosResult(requireTerminalResult(current)).result
        }
        if (current.phase == OperationPhase.SUBMITTING) {
            return requireCreateUtxosResult(recoverSubmitting(operationContext, current)).result
        }
        val prepared = ensurePrepared(
            checkpoint = current,
            context = operationContext,
            expectedKind = OperationKind.CREATE_UTXOS,
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
            LocalOperationIntent.CreateUtxos(
                network = configuration.network,
                policy = configuration.signingPolicy,
                request = request,
                exactOutputPlan = plan,
            ),
        )
        val result = submitPersisted(operationContext, signed)
        return requireCreateUtxosResult(result).result
    }

    suspend fun onchainSend(
        backendContext: BackendContext,
        signer: LocalSigningEngine,
        request: OnchainSendRequest,
    ): OnchainSendResponse {
        requireFeeWithinPolicy(request.feeRate.milliSatsPerVbyte)
        val hash = RequestHasher.onchainSend(configuration.network, request)
        val operationContext = OperationRequestContext(
            backend = backendContext,
            idempotencyKey = request.idempotencyKey,
            requestHash = hash,
        )
        val current = loadOrCreate(
            request.idempotencyKey,
            hash,
            OperationKind.ONCHAIN_SEND,
        )
        if (current.phase == OperationPhase.TERMINAL) {
            return requireOnchainSendResult(requireTerminalResult(current)).result
        }
        if (current.phase == OperationPhase.SUBMITTING) {
            return requireOnchainSendResult(recoverSubmitting(operationContext, current)).result
        }
        val prepared = ensurePrepared(
            checkpoint = current,
            context = operationContext,
            expectedKind = OperationKind.ONCHAIN_SEND,
            prepare = {
                backendCall {
                    backend.prepareOnchainSend(PrepareOnchainSendRequest(operationContext, request))
                }
            },
        )
        val signed = ensureSigned(
            checkpoint = prepared,
            signer = signer,
            LocalOperationIntent.OnchainSend(
                network = configuration.network,
                policy = configuration.signingPolicy,
                request = request,
            ),
        )
        val result = submitPersisted(operationContext, signed)
        return requireOnchainSendResult(result).result
    }

    private suspend fun submitPersisted(
        context: OperationRequestContext,
        checkpoint: OperationCheckpoint,
    ): BackendOperationResult {
        val prepared = checkpoint.preparedTransaction
            ?: throw SdkException.InternalInvariantViolation("signed checkpoint has no preparation")
        val signed = checkpoint.signedPayload
            ?: throw SdkException.InternalInvariantViolation("signed checkpoint has no payload")
        val submitting = when (checkpoint.phase) {
            OperationPhase.SIGNED -> checkpoint.copy(phase = OperationPhase.SUBMITTING).also {
                journalSave(it)
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
            backendCall { backend.submitSignedTransaction(submission) }
        } catch (cancellation: CancellationException) {
            // SUBMITTING was persisted first. Cancellation stops waiting; it
            // does not imply that backend processing or broadcast was undone.
            throw cancellation
        } catch (_: SdkException.BackendUnavailable) {
            return reconcileAfterSubmit(context.backend, submitting)
        } catch (expired: SdkException.OperationExpired) {
            if (expired.operationId != prepared.operationId) {
                throw SdkException.OperationAmbiguous(prepared.operationId)
            }
            persistExpiredAndThrow(submitting)
        } catch (rejected: SdkException.BackendRejected) {
            persistDirectRejectionOrThrow(submitting, rejected)
        } catch (ambiguous: SdkException.OperationAmbiguous) {
            throw ambiguous
        } catch (definitive: SdkException) {
            // OperationConflict and protocol failures are definitive API
            // outcomes. The current journal schema has no matching terminal
            // variant, so preserve SUBMITTING and never poll or resubmit.
            throw definitive
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

    private suspend fun loadOrCreate(
        idempotencyKey: IdempotencyKey,
        hash: RequestHash,
        kind: OperationKind,
    ): OperationCheckpoint {
        val current = journalLoad(configuration.walletId, idempotencyKey)
        if (current == null) {
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
        if (current.requestHash != hash || current.operationKind != kind) {
            throw SdkException.OperationConflict(idempotencyKey)
        }
        return current
    }

    private suspend fun ensurePrepared(
        checkpoint: OperationCheckpoint,
        context: OperationRequestContext,
        expectedKind: OperationKind,
        prepare: suspend () -> PreparedTransaction,
        verifyOperation: (PreparedTransaction) -> Unit = {},
    ): OperationCheckpoint {
        if (checkpoint.phase == OperationPhase.CREATED) {
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
        if (checkpoint.phase == OperationPhase.PREPARED && prepared.expiresAt.value <= now.now()) {
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
        intent: LocalOperationIntent,
    ): OperationCheckpoint {
        if (checkpoint.phase == OperationPhase.SIGNED) return checkpoint
        if (checkpoint.phase != OperationPhase.PREPARED) {
            throw SdkException.InternalInvariantViolation("checkpoint cannot enter signing")
        }
        val prepared = checkpoint.preparedTransaction
            ?: throw SdkException.InternalInvariantViolation("prepared checkpoint has no transaction")
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
    ): BackendOperationResult {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("submitting checkpoint has no operation ID")
        val status = try {
            tryGetOperation(context.backend, operationId)
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
    ): BackendOperationResult {
        val operationId = checkpoint.operationId
            ?: throw SdkException.InternalInvariantViolation("submitting checkpoint has no operation ID")
        return when (val status = try {
            tryGetOperation(backendContext, operationId)
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
        operationId: com.utexo.rgb.sdk.OperationId,
    ): BackendOperationStatus {
        val status = backendCall { backend.getOperation(context, operationId) }
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
        request.num?.let { requested ->
            val valid = if (request.upTo) plan.outputs.size <= requested else plan.outputs.size == requested
            if (!valid) {
                throw SdkException.PolicyRejected(
                    "createUtxos output count does not match the caller request",
                    prepared.operationId,
                )
            }
        }
        request.size?.let { requestedSize ->
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

    private fun verifyResultIdentity(
        result: BackendOperationResult,
        checkpoint: OperationCheckpoint,
    ): Unit {
        val prepared = checkpoint.preparedTransaction
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no preparation")
        if (result.operationId != checkpoint.operationId) {
            throw protocolMismatch("submission result has the wrong operation ID", prepared)
        }
        val variantMatches = when (checkpoint.operationKind) {
            OperationKind.SEND_BTC -> result is BackendOperationResult.SendBtc
            OperationKind.CREATE_UTXOS -> result is BackendOperationResult.CreateUtxos
            OperationKind.ONCHAIN_SEND -> result is BackendOperationResult.OnchainSend
            OperationKind.INFLATE -> false
        }
        if (!variantMatches) {
            throw protocolMismatch("submission result has the wrong operation variant", prepared)
        }
        val expectedTxid = checkpoint.signedPayload?.validation?.unsignedTxid
            ?: throw SdkException.InternalInvariantViolation("checkpoint has no local validation report")
        val actualTxid = when (result) {
            is BackendOperationResult.SendBtc -> result.txid
            is BackendOperationResult.CreateUtxos -> result.result.txid
            is BackendOperationResult.OnchainSend -> result.result.txid
        }
        if (actualTxid != expectedTxid) {
            throw protocolMismatch("backend transaction ID differs from local validation", prepared)
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

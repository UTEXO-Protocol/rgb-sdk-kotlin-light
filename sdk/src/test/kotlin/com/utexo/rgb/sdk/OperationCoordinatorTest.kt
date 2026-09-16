package com.utexo.rgb.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

internal class OperationCoordinatorTest {
    @Test
    fun `sendBtc prepares signs and submits without exposing PSBT to caller`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val backend = FakeBackend(events)
        val signer = FakeSigningEngine(events = events)
        val journal = InMemoryOperationJournal()
        val wallet = testWallet(backend, signer, journal)
        wallet.init()
        wallet.unlock()
        val request = SendBtcRequest(
            address = TEST_ADDRESS,
            amount = Satoshis.of(10_000),
            feeRate = FeeRate.satPerVbyte(5.0),
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
        )

        assertEquals(TEST_TXID, wallet.sendBtc(request))
        assertEquals(
            listOf("initialize", "register", "prepare", "sign", "submit"),
            events,
        )
        assertIs<com.utexo.rgb.sdk.internal.LocalOperationIntent.SendBtc>(signer.intents.single())
        val checkpoint = journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)
        assertEquals(OperationPhase.TERMINAL, checkpoint?.phase)
        assertEquals("signed-psbt", checkpoint?.signedPayload?.signedPsbtBase64)

        // An exact retry returns the durable terminal result and cannot submit twice.
        assertEquals(TEST_TXID, wallet.sendBtc(request))
        assertEquals(1, backend.prepareSendCalls)
        assertEquals(1, backend.submitCalls)
        assertEquals(1, signer.signCalls)
    }

    @Test
    fun `idempotency key cannot be reused for another intent`(): Unit = runBlocking {
        val backend = FakeBackend()
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()
        val first = SendBtcRequest(
            TEST_ADDRESS,
            Satoshis.of(10_000),
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
        )
        wallet.sendBtc(first)

        val error = assertFailsWith<SdkException.OperationConflict> {
            wallet.sendBtc(first.copy(amount = Satoshis.of(10_001)))
        }
        assertEquals(SdkErrorCode.OPERATION_CONFLICT, error.code)
        assertEquals(1, backend.prepareSendCalls)
    }

    @Test
    fun `tampered preparation is rejected before signing`(): Unit = runBlocking {
        val backend = FakeBackend().apply {
            overridePreparedHash = RequestHash("0".repeat(64))
        }
        val signer = FakeSigningEngine()
        val wallet = testWallet(backend, signer)
        wallet.init()
        wallet.unlock()

        val error = assertFailsWith<SdkException.BackendProtocolMismatch> {
            wallet.sendBtc(
                SendBtcRequest(
                    TEST_ADDRESS,
                    Satoshis.of(10_000),
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        assertEquals(SdkErrorCode.BACKEND_PROTOCOL_MISMATCH, error.code)
        assertEquals(0, signer.signCalls)
        assertEquals(0, backend.submitCalls)
    }

    @Test
    fun `lost submit response is reconciled by operation ID`(): Unit = runBlocking {
        val backend = FakeBackend().apply {
            submitFailure = IOException("socket closed after write")
            status = BackendOperationStatus.Succeeded(
                TEST_OPERATION_ID,
                BackendOperationResult.SendBtc(TEST_OPERATION_ID, TEST_TXID),
            )
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()

        val result = wallet.sendBtc(
            SendBtcRequest(
                TEST_ADDRESS,
                Satoshis.of(10_000),
                idempotencyKey = TEST_IDEMPOTENCY_KEY,
            ),
        )
        assertEquals(TEST_TXID, result)
        assertEquals(1, backend.submitCalls)
        assertEquals(1, backend.statusCalls)
    }

    @Test
    fun `current core capabilities fail closed for unbound createUtxos`(): Unit = runBlocking {
        val backend = FakeBackend()
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()

        val error = assertFailsWith<SdkException.Unsupported> {
            wallet.createUtxos(
                CreateUtxosRequest(
                    num = 1,
                    size = Satoshis.of(1_000),
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        assertEquals(SdkErrorCode.UNSUPPORTED, error.code)
        assertEquals(0, backend.prepareCreateCalls)
    }

    @Test
    fun `inflate surface fails closed before contacting the backend`(): Unit = runBlocking {
        val backend = FakeBackend()
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()

        val error = assertFailsWith<SdkException.Unsupported> {
            wallet.inflate(
                InflateRequest(
                    assetId = AssetId.of("rgb:asset"),
                    inflationAmounts = listOf(AssetAmount.of(1)),
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        assertEquals(SdkErrorCode.UNSUPPORTED, error.code)
        assertEquals(0, backend.prepareSendCalls)
        assertEquals(0, backend.prepareOnchainCalls)
    }

    @Test
    fun `createUtxos exact output plan is bound before signer invocation`(): Unit = runBlocking {
        val backend = FakeBackend().apply {
            createPlan = CreateUtxosOutputPlan(
                listOf(
                    ExpectedWalletOutput(
                        AccountKind.COLORED,
                        Keychain.EXTERNAL,
                        index = 8,
                        amount = Satoshis.of(2_000),
                    ),
                ),
            )
        }
        val signer = FakeSigningEngine(
            capabilities = testCapabilities(setOf(OperationKind.SEND_BTC, OperationKind.CREATE_UTXOS)),
        )
        val wallet = testWallet(backend, signer)
        wallet.init()
        wallet.unlock()

        assertFailsWith<SdkException.PolicyRejected> {
            wallet.createUtxos(
                CreateUtxosRequest(
                    upTo = false,
                    num = 1,
                    size = Satoshis.of(1_000),
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        assertEquals(0, signer.signCalls)
        assertEquals(0, backend.submitCalls)
    }

    @Test
    fun `wallet serializes concurrent money moving calls`(): Unit = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val backend = object : FakeBackend() {
            override suspend fun prepareSendBtc(request: PrepareSendBtcRequest): PreparedTransaction {
                val nowActive = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, nowActive) }
                delay(20)
                return try {
                    super.prepareSendBtc(request)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()

        listOf(1, 2).map { index ->
            async {
                wallet.sendBtc(
                    SendBtcRequest(
                        address = TEST_ADDRESS,
                        amount = Satoshis.of(10_000L + index),
                        idempotencyKey = IdempotencyKey.of("concurrent-$index"),
                    ),
                )
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
        assertEquals(2, backend.submitCalls)
    }

    @Test
    fun `restart from prepared signs the exact persisted preparation without reprepare`(): Unit =
        runBlocking {
            val durable = InMemoryOperationJournal()
            val crashJournal = FailOnceBeforePhaseJournal(durable, OperationPhase.SIGNED)
            val backend = FakeBackend()
            val firstSigner = FakeSigningEngine()
            val firstWallet = testWallet(backend, firstSigner, crashJournal)
            firstWallet.init()
            firstWallet.unlock()
            val request = testSendRequest()

            assertFailsWith<SdkException.OperationJournalUnavailable> {
                firstWallet.sendBtc(request)
            }
            val prepared = assertNotNull(
                durable.load(testConfiguration().walletId, request.idempotencyKey),
            )
            assertEquals(OperationPhase.PREPARED, prepared.phase)
            assertEquals("prepared-psbt", prepared.preparedTransaction?.psbtBase64)

            val resumedSigner = FakeSigningEngine()
            val resumedWallet = testWallet(backend, resumedSigner, durable)
            resumedWallet.init()
            resumedWallet.unlock()
            assertEquals(TEST_TXID, resumedWallet.sendBtc(request))
            assertEquals(1, backend.prepareSendCalls)
            assertEquals(1, resumedSigner.signCalls)
            assertEquals("signed-psbt", backend.submissions.single().signedPsbtBase64)
        }

    @Test
    fun `restart from signed submits identical bytes without reprepare or resign`(): Unit =
        runBlocking {
            val durable = InMemoryOperationJournal()
            val crashJournal = FailOnceBeforePhaseJournal(durable, OperationPhase.SUBMITTING)
            val backend = FakeBackend()
            val firstSigner = FakeSigningEngine()
            val firstWallet = testWallet(backend, firstSigner, crashJournal)
            firstWallet.init()
            firstWallet.unlock()
            val request = testSendRequest()

            assertFailsWith<SdkException.OperationJournalUnavailable> {
                firstWallet.sendBtc(request)
            }
            val signed = assertNotNull(
                durable.load(testConfiguration().walletId, request.idempotencyKey),
            )
            assertEquals(OperationPhase.SIGNED, signed.phase)
            assertEquals("signed-psbt", signed.signedPayload?.signedPsbtBase64)

            val resumedSigner = FakeSigningEngine()
            val resumedWallet = testWallet(backend, resumedSigner, durable)
            resumedWallet.init()
            resumedWallet.unlock()
            assertEquals(TEST_TXID, resumedWallet.sendBtc(request))
            assertEquals(1, backend.prepareSendCalls)
            assertEquals(0, resumedSigner.signCalls)
            assertEquals("signed-psbt", backend.submissions.single().signedPsbtBase64)
        }

    @Test
    fun `restart from submitting only polls and never assumes resubmission is safe`(): Unit =
        runBlocking {
            val journal = InMemoryOperationJournal()
            val backend = FakeBackend().apply {
                submitFailure = IOException("response lost")
                status = BackendOperationStatus.Pending(TEST_OPERATION_ID, "accepted")
            }
            val signer = FakeSigningEngine()
            val wallet = testWallet(backend, signer, journal)
            wallet.init()
            wallet.unlock()
            val request = testSendRequest()

            assertFailsWith<SdkException.OperationAmbiguous> { wallet.sendBtc(request) }
            assertEquals(
                OperationPhase.SUBMITTING,
                journal.load(testConfiguration().walletId, request.idempotencyKey)?.phase,
            )
            val resumedSigner = FakeSigningEngine()
            val resumedWallet = testWallet(backend, resumedSigner, journal)
            resumedWallet.init()
            resumedWallet.unlock()
            assertFailsWith<SdkException.OperationAmbiguous> {
                resumedWallet.sendBtc(request)
            }
            assertEquals(1, backend.prepareSendCalls)
            assertEquals(0, resumedSigner.signCalls)
            assertEquals(1, backend.submitCalls)
            assertEquals(listOf("signed-psbt"), backend.submissions.map { it.signedPsbtBase64 })
            assertEquals(2, backend.statusCalls)
        }

    @Test
    fun `backend txid must equal the locally validated unsigned txid`(): Unit = runBlocking {
        val backend = FakeBackend().apply {
            resultTxid = TransactionId.of("f".repeat(64))
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()

        val error = assertFailsWith<SdkException.OperationAmbiguous> {
            wallet.sendBtc(testSendRequest())
        }
        assertEquals(SdkErrorCode.OPERATION_AMBIGUOUS, error.code)
    }

    @Test
    fun `backend result variant must match before terminal persistence`(): Unit = runBlocking {
        val backend = object : FakeBackend() {
            override suspend fun submitSignedTransaction(
                request: SignedTransactionSubmission,
            ): BackendOperationResult = BackendOperationResult.OnchainSend(
                operationId = request.operationId,
                result = OnchainSendResponse(TEST_TXID, batchTransferIdx = 9),
            )
        }
        val journal = InMemoryOperationJournal()
        val wallet = testWallet(backend, FakeSigningEngine(), journal)
        wallet.init()
        wallet.unlock()

        assertFailsWith<SdkException.OperationAmbiguous> {
            wallet.sendBtc(testSendRequest())
        }
        val checkpoint = journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)
        assertEquals(OperationPhase.SUBMITTING, checkpoint?.phase)
        assertEquals(null, checkpoint?.terminalResult)
    }

    @Test
    fun `definitive backend rejection is durable and replayed without resubmission`(): Unit =
        runBlocking {
            val journal = InMemoryOperationJournal()
            val backend = FakeBackend().apply {
                submitFailure = SdkException.BackendRejected(
                    safeMessage = "transaction rejected",
                    retryable = false,
                    operationId = TEST_OPERATION_ID,
                    backendCode = "POLICY_REJECTED",
                    correlationId = "correlation-1",
                )
            }
            val wallet = testWallet(backend, FakeSigningEngine(), journal)
            wallet.init()
            wallet.unlock()

            val first = assertFailsWith<SdkException.BackendRejected> {
                wallet.sendBtc(testSendRequest())
            }
            assertEquals(false, first.retryable)
            val checkpoint = assertNotNull(
                journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY),
            )
            assertEquals(OperationPhase.TERMINAL, checkpoint.phase)
            assertIs<OperationTerminalOutcome.Failed>(checkpoint.terminalOutcome)

            val resumed = testWallet(backend, FakeSigningEngine(), journal)
            resumed.init()
            resumed.unlock()
            assertFailsWith<SdkException.BackendRejected> {
                resumed.sendBtc(testSendRequest())
            }
            assertEquals(1, backend.submitCalls)
            assertEquals(0, backend.statusCalls)
        }

    @Test
    fun `definitive backend expiry is durable and replayed without resubmission`(): Unit =
        runBlocking {
            val journal = InMemoryOperationJournal()
            val backend = FakeBackend().apply {
                submitFailure = SdkException.OperationExpired(TEST_OPERATION_ID)
            }
            val wallet = testWallet(backend, FakeSigningEngine(), journal)
            wallet.init()
            wallet.unlock()

            assertFailsWith<SdkException.OperationExpired> {
                wallet.sendBtc(testSendRequest())
            }
            val checkpoint = assertNotNull(
                journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY),
            )
            assertEquals(OperationPhase.TERMINAL, checkpoint.phase)
            assertIs<OperationTerminalOutcome.Expired>(checkpoint.terminalOutcome)

            val resumed = testWallet(backend, FakeSigningEngine(), journal)
            resumed.init()
            resumed.unlock()
            assertFailsWith<SdkException.OperationExpired> {
                resumed.sendBtc(testSendRequest())
            }
            assertEquals(1, backend.submitCalls)
            assertEquals(0, backend.statusCalls)
        }

    @Test
    fun `definitive submit protocol failure propagates without unsupported reconciliation`(): Unit =
        runBlocking {
            val journal = InMemoryOperationJournal()
            val backend = FakeBackend().apply {
                submitFailure = SdkException.BackendProtocolMismatch(
                    reason = "operation kind mismatch",
                    operationId = TEST_OPERATION_ID,
                )
            }
            val wallet = testWallet(backend, FakeSigningEngine(), journal)
            wallet.init()
            wallet.unlock()

            assertFailsWith<SdkException.BackendProtocolMismatch> {
                wallet.sendBtc(testSendRequest())
            }
            assertEquals(0, backend.statusCalls)
            assertEquals(
                OperationPhase.SUBMITTING,
                journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)?.phase,
            )
        }

    @Test
    fun `cancellation during submit remains cancellation and does not reconcile`(): Unit =
        runBlocking {
            val journal = InMemoryOperationJournal()
            val backend = FakeBackend().apply {
                submitFailure = CancellationException("caller cancelled")
            }
            val wallet = testWallet(backend, FakeSigningEngine(), journal)
            wallet.init()
            wallet.unlock()

            assertFailsWith<CancellationException> { wallet.sendBtc(testSendRequest()) }
            assertEquals(0, backend.statusCalls)
            assertEquals(
                OperationPhase.SUBMITTING,
                journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)?.phase,
            )
        }

    @Test
    fun `cancellation during reconciliation is never remapped to ambiguity`(): Unit =
        runBlocking {
            val journal = InMemoryOperationJournal()
            val backend = FakeBackend().apply {
                submitFailure = IOException("response lost")
                statusFailure = CancellationException("caller cancelled")
            }
            val wallet = testWallet(backend, FakeSigningEngine(), journal)
            wallet.init()
            wallet.unlock()

            assertFailsWith<CancellationException> { wallet.sendBtc(testSendRequest()) }
            assertEquals(1, backend.statusCalls)
            assertEquals(
                OperationPhase.SUBMITTING,
                journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)?.phase,
            )
        }
}

private fun testSendRequest(): SendBtcRequest = SendBtcRequest(
    address = TEST_ADDRESS,
    amount = Satoshis.of(10_000),
    idempotencyKey = TEST_IDEMPOTENCY_KEY,
)

private class FailOnceBeforePhaseJournal(
    private val delegate: OperationJournal,
    private val phase: OperationPhase,
) : OperationJournal {
    private var failed: Boolean = false

    override suspend fun load(
        walletId: WalletId,
        idempotencyKey: IdempotencyKey,
    ): OperationCheckpoint? = delegate.load(walletId, idempotencyKey)

    override suspend fun save(checkpoint: OperationCheckpoint): Unit {
        if (!failed && checkpoint.phase == phase) {
            failed = true
            throw IOException("simulated process loss before durable commit")
        }
        delegate.save(checkpoint)
    }
}

package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.InvoiceEncodedState
import com.utexo.rgb.sdk.internal.EpochSecondsSource
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class RgbIntentValidationTest {
    private val invoice: RgbInvoice = RgbInvoice.of("rgb:invoice-test")
    private val assetId: AssetId = AssetId.of("rgb:asset-test")
    private val amount: AssetAmount = AssetAmount.of(42)

    @Test
    fun `RGB operations stop before backend when local parser is unavailable`(): Unit = runBlocking {
        val backend = FakeBackend()
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()

        assertFailsWith<SdkException.Unsupported> {
            wallet.onchainSend(
                OnchainSendRequest(
                    invoice = invoice,
                    amount = amount,
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        assertEquals(0, backend.prepareOnchainCalls)
    }

    @Test
    fun `onchain send binds locally decoded invoice network asset and amount`(): Unit = runBlocking {
        val backend = FakeBackend()
        val signer = FakeSigningEngine(
            capabilities = testCapabilities(
                operations = setOf(OperationKind.SEND_BTC, OperationKind.ONCHAIN_SEND),
                rgbInvoiceParsing = true,
            ),
            decodedInvoice = decodedInvoice(Network.SIGNET),
        )
        val wallet = testWallet(backend, signer)
        wallet.init()
        wallet.unlock()

        val result = wallet.onchainSend(
            OnchainSendRequest(
                invoice = invoice,
                assetId = assetId,
                amount = amount,
                idempotencyKey = TEST_IDEMPOTENCY_KEY,
            ),
        )
        assertEquals(TEST_TXID, result.txid)
        assertEquals(
            listOf(
                OperationKind.ONCHAIN_SEND,
                OperationKind.ONCHAIN_SEND,
                OperationKind.ONCHAIN_SEND,
            ),
            backend.preflightOperations,
        )
        assertEquals(1, backend.prepareOnchainCalls)
        assertEquals(1, signer.signCalls)
    }

    @Test
    fun `wrong-network RGB invoice is rejected locally before prepare`(): Unit = runBlocking {
        val backend = FakeBackend()
        val signer = FakeSigningEngine(
            capabilities = testCapabilities(
                operations = setOf(OperationKind.ONCHAIN_SEND),
                rgbInvoiceParsing = true,
            ),
            decodedInvoice = decodedInvoice(Network.MAINNET),
        )
        val wallet = testWallet(backend, signer)
        wallet.init()
        wallet.unlock()

        assertFailsWith<SdkException.WrongNetwork> {
            wallet.onchainSend(
                OnchainSendRequest(
                    invoice = invoice,
                    amount = amount,
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        assertEquals(0, backend.prepareOnchainCalls)
    }

    @Test
    fun `outgoing invoice binds raw amount rather than lossy RN projection`(): Unit = runBlocking {
        val cases = listOf(
            Triple(Assignment(AssignmentType.FUNGIBLE, AssetAmount.ZERO), InvoiceEncodedState.OMITTED, null),
            Triple(Assignment(AssignmentType.ANY), InvoiceEncodedState.AMOUNT, amount),
            Triple(Assignment(AssignmentType.ANY), InvoiceEncodedState.AMOUNT, AssetAmount.of(43)),
            Triple(Assignment(AssignmentType.ANY), InvoiceEncodedState.NON_FUNGIBLE, null),
            Triple(Assignment(AssignmentType.ANY), InvoiceEncodedState.OMITTED, amount),
        )
        for ((index, testCase) in cases.withIndex()) {
            val backend = FakeBackend()
            val signer = outgoingSigner(decodedInvoice(Network.SIGNET).copy(assignment = testCase.first)).apply {
                rawInvoiceState = testCase.second
                rawInvoiceAmount = testCase.third
            }
            val wallet = testWallet(backend, signer)
            wallet.init()
            wallet.unlock()
            try {
                val request = OnchainSendRequest(invoice, amount, idempotencyKey = TEST_IDEMPOTENCY_KEY)
                if (index < 2) {
                    assertEquals(TEST_TXID, wallet.onchainSend(request).txid)
                    assertEquals(1, backend.prepareOnchainCalls)
                } else {
                    assertFailsWith<SdkException.PolicyRejected> { wallet.onchainSend(request) }
                    assertEquals(0, backend.prepareOnchainCalls)
                    assertEquals(0, signer.signCalls)
                }
            } finally {
                wallet.destroy()
            }
        }
    }

    @Test
    fun `generic outgoing invoice needs caller asset but fixed invoice asset cannot be overridden`(): Unit = runBlocking {
        for (generic in listOf(false, true)) {
            for (callerAsset in listOf(null, assetId, AssetId.of("other-asset"))) {
                val backend = FakeBackend()
                val decoded = decodedInvoice(Network.SIGNET).copy(assetId = if (generic) null else assetId)
                val wallet = testWallet(backend, outgoingSigner(decoded))
                wallet.init()
                wallet.unlock()
                try {
                    val request = OnchainSendRequest(invoice, amount, callerAsset, idempotencyKey = TEST_IDEMPOTENCY_KEY)
                    val valid = if (generic) callerAsset != null else callerAsset == null || callerAsset == assetId
                    if (valid) assertEquals(TEST_TXID, wallet.onchainSend(request).txid)
                    else {
                        assertFailsWith<SdkException.PolicyRejected> { wallet.onchainSend(request) }
                        assertEquals(0, backend.prepareOnchainCalls)
                    }
                } finally {
                    wallet.destroy()
                }
            }
        }
    }

    @Test
    fun `outgoing invoice identity assignment expiry and witness mode are checked before prepare`(): Unit = runBlocking {
        val changed = listOf(
            decodedInvoice(Network.SIGNET).copy(invoice = RgbInvoice.of("rgb:other")),
            decodedInvoice(Network.SIGNET).copy(assignmentName = "inflationAllowance"),
            decodedInvoice(Network.SIGNET).copy(assignment = Assignment(AssignmentType.INFLATION_RIGHT, amount)),
            decodedInvoice(Network.SIGNET).copy(expirationTimestamp = UnixSeconds.of(TEST_NOW)),
        )
        for (decoded in changed) {
            val backend = FakeBackend()
            val wallet = testWallet(backend, outgoingSigner(decoded))
            wallet.init()
            wallet.unlock()
            try {
                assertFailsWith<SdkException.PolicyRejected> { wallet.onchainSend(OnchainSendRequest(invoice, amount)) }
                assertEquals(0, backend.prepareOnchainCalls)
            } finally {
                wallet.destroy()
            }
        }
        val backend = FakeBackend()
        val wallet = testWallet(backend, outgoingSigner(decodedInvoice(Network.SIGNET)).apply { decodedWitness = false })
        wallet.init()
        wallet.unlock()
        try {
            assertFailsWith<SdkException.PolicyRejected> {
                wallet.onchainSend(OnchainSendRequest(invoice, amount, witnessData = WitnessData(Satoshis.of(1_000))))
            }
            assertEquals(0, backend.prepareOnchainCalls)
        } finally {
            wallet.destroy()
        }
    }

    @Test
    fun `expired RGB invoice and disabled signer do not hide submitting recovery or terminal replay`(): Unit = runBlocking {
        var now = TEST_NOW
        val request = OnchainSendRequest(invoice, amount, idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val journal = InMemoryOperationJournal()
        val firstBackend = FakeBackend().apply { submitFailure = IOException("response lost") }
        val first = testWallet(firstBackend,
            outgoingSigner(decodedInvoice(Network.SIGNET).copy(expirationTimestamp = UnixSeconds.of(TEST_NOW + 10))),
            journal, EpochSecondsSource { now })
        first.init()
        first.unlock()
        try {
            assertFailsWith<SdkException.OperationAmbiguous> { first.onchainSend(request) }
            assertEquals(OperationPhase.SUBMITTING, journal.load(testConfiguration().walletId, request.idempotencyKey)?.phase)
        } finally { first.destroy() }

        now += 11
        val backend = FakeBackend().apply {
            status = BackendOperationStatus.Succeeded(TEST_OPERATION_ID,
                BackendOperationResult.OnchainSend(TEST_OPERATION_ID, OnchainSendResponse(TEST_TXID, 7)))
        }
        val disabled = FakeSigningEngine(testCapabilities(operations = emptySet(), rgbInvoiceParsing = false))
        val restored = testWallet(backend, disabled, journal, EpochSecondsSource { now })
        restored.init()
        restored.unlock()
        try {
            assertEquals(TEST_TXID, restored.onchainSend(request).txid)
            assertEquals(TEST_TXID, restored.onchainSend(request).txid)
            assertEquals(1, backend.statusCalls)
            assertEquals(0, backend.prepareOnchainCalls)
            assertEquals(0, backend.submitCalls)
            assertEquals(0, disabled.signCalls)
            assertEquals(emptyList(), backend.preflightOperations)
            assertFailsWith<SdkException.Unsupported> {
                restored.onchainSend(request.copy(idempotencyKey = IdempotencyKey.of("new-operation")))
            }
            assertEquals(null, journal.load(testConfiguration().walletId, IdempotencyKey.of("new-operation")))
        } finally { restored.destroy() }
    }

    @Test
    fun `invoice expiry during final backend preflight preserves signed checkpoint without submission`(): Unit = runBlocking {
        var now = TEST_NOW
        var preflightCalls = 0
        val backend = FakeBackend(onPreflight = {
            preflightCalls++
            if (preflightCalls == 3) now = TEST_NOW + 10
        })
        val signer = outgoingSigner(decodedInvoice(Network.SIGNET).copy(expirationTimestamp = UnixSeconds.of(TEST_NOW + 10)))
        val journal = InMemoryOperationJournal()
        val wallet = testWallet(backend, signer, journal, EpochSecondsSource { now })
        wallet.init()
        wallet.unlock()
        try {
            assertFailsWith<SdkException.PolicyRejected> {
                wallet.onchainSend(OnchainSendRequest(invoice, amount, idempotencyKey = TEST_IDEMPOTENCY_KEY))
            }
            assertEquals(1, signer.signCalls)
            assertEquals(3, preflightCalls)
            assertEquals(0, backend.submitCalls)
            assertEquals(OperationPhase.SIGNED, journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)?.phase)
        } finally { wallet.destroy() }
    }

    private fun outgoingSigner(decoded: InvoiceData): FakeSigningEngine = FakeSigningEngine(
        testCapabilities(operations = setOf(OperationKind.ONCHAIN_SEND), rgbInvoiceParsing = true), decoded,
    )

    private fun decodedInvoice(network: Network): InvoiceData = InvoiceData(
        invoice = invoice,
        recipientId = "recipient-1",
        assetId = assetId,
        network = network,
        assignment = Assignment(AssignmentType.FUNGIBLE, amount),
    )
}

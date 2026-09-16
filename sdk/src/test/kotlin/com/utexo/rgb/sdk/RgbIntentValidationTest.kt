package com.utexo.rgb.sdk

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

    private fun decodedInvoice(network: Network): InvoiceData = InvoiceData(
        invoice = invoice,
        recipientId = "recipient-1",
        assetId = assetId,
        network = network,
        assignment = Assignment(AssignmentType.FUNGIBLE, amount),
    )
}

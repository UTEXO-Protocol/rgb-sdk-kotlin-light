package com.utexo.rgb.sdk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.utexo.rgb.sdk.internal.InvoiceEncodedState

internal class InvoiceIntentTest {
    private val invoice = RgbInvoice.of("rgb:local-parser-fixture")
    private val asset = AssetId.of("asset-fixture")
    private val request = OnchainReceiveRequest(AssetAmount.of(10), asset, duration = DurationSeconds.of(600))
    private fun decoded(): InvoiceData = InvoiceData(
        invoice, "recipient-fixture", assetId = asset, network = Network.SIGNET,
        assignment = Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(10)),
        expirationTimestamp = UnixSeconds.of(TEST_NOW + 600),
    )
    private fun response(): OnchainReceiveResponse = OnchainReceiveResponse(
        invoice, "recipient-fixture", UnixSeconds.of(TEST_NOW + 600),
    )

    @Test
    fun `valid receive returns unchanged result and decoder alias preserves original invoice`(): Unit = runBlocking {
        val backend = FakeBackend().apply { onchainReceiveResponse = response() }
        val signer = FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), decoded())
        val wallet = testWallet(backend, signer)
        wallet.init(); wallet.unlock()
        try {
            assertEquals(response(), wallet.onchainReceive(request))
            assertEquals(decoded(), wallet.decodeRgbInvoice(invoice))
            assertEquals(decoded(), wallet.decodeRGBInvoice(invoice))
        } finally { wallet.destroy() }
    }

    @Test
    fun `received invoice cannot substitute asset amount assignment beneficiary mode or expiry`(): Unit = runBlocking {
        val changes = listOf(
            decoded().copy(invoice = RgbInvoice.of("rgb:different")),
            decoded().copy(recipientId = "other-recipient"),
            decoded().copy(assetId = null), decoded().copy(assetId = AssetId.of("other-asset")),
            decoded().copy(network = Network.MAINNET),
            decoded().copy(assignment = Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(11))),
            decoded().copy(assignment = Assignment(AssignmentType.ANY, AssetAmount.of(10))),
            decoded().copy(assignment = Assignment(AssignmentType.INFLATION_RIGHT, AssetAmount.of(10))),
            decoded().copy(assignment = Assignment(AssignmentType.NON_FUNGIBLE)),
            decoded().copy(expirationTimestamp = null),
            decoded().copy(expirationTimestamp = UnixSeconds.of(TEST_NOW)),
            // Outside the 300-second clock-skew allowance around TEST_NOW + 600.
            decoded().copy(expirationTimestamp = UnixSeconds.of(TEST_NOW + 299)),
            decoded().copy(expirationTimestamp = UnixSeconds.of(TEST_NOW + 901)),
        )
        for (changed in changes) {
            val backend = FakeBackend().apply { onchainReceiveResponse = response() }
            val wallet = testWallet(backend, FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), changed))
            wallet.init(); wallet.unlock()
            try { assertFailsWith<SdkException> { wallet.onchainReceive(request) } }
            finally { wallet.destroy() }
        }
        val signer = FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), decoded()).apply { decodedWitness = false }
        val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response() }, signer)
        wallet.init(); wallet.unlock()
        try { assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(request) } }
        finally { wallet.destroy() }
    }

    @Test
    fun `received expiry tolerates bounded device and gateway clock skew`(): Unit = runBlocking {
        for (offset in listOf(2L, -2L, 300L, -300L)) {
            val expiration = UnixSeconds.of(TEST_NOW + 600 + offset)
            val returned = response().copy(expirationTimestamp = expiration)
            val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = returned },
                FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), decoded().copy(expirationTimestamp = expiration)))
            wallet.init(); wallet.unlock()
            try { assertEquals(returned, wallet.onchainReceive(request)) }
            finally { wallet.destroy() }
        }
    }

    @Test
    fun `unspecified asset and amount mean Any rather than an invented fixed receive intent`(): Unit = runBlocking {
        val any = decoded().copy(assetId = null, assignment = Assignment(AssignmentType.ANY))
        for (changed in listOf(any, any.copy(assetId = asset), any.copy(assignment = Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(10))))) {
            val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response() },
                FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), changed))
            wallet.init(); wallet.unlock()
            try {
                if (changed == any) assertEquals(response(), wallet.onchainReceive(OnchainReceiveRequest(duration = DurationSeconds.of(600))))
                else assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(OnchainReceiveRequest(duration = DurationSeconds.of(600))) }
            } finally { wallet.destroy() }
        }
    }

    @Test
    fun `decoder permits inspecting expired invoice while receive rejects expired destination`(): Unit = runBlocking {
        val old = decoded().copy(expirationTimestamp = UnixSeconds.of(TEST_NOW - 1))
        val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response().copy(expirationTimestamp = old.expirationTimestamp) },
            FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), old))
        wallet.init(); wallet.unlock()
        try {
            assertEquals(old, wallet.decodeRgbInvoice(invoice))
            assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(request.copy(duration = null)) }
        } finally { wallet.destroy() }
    }

    @Test
    fun `receive binds raw encoded state instead of lossy compatibility assignment projection`(): Unit = runBlocking {
        val omitted = decoded().copy(assignment = Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(0)))
        val cases = listOf(
            Triple(omitted, InvoiceEncodedState.OMITTED, null),
            Triple(omitted.copy(assignment = Assignment(AssignmentType.ANY)), InvoiceEncodedState.AMOUNT, AssetAmount.of(10)),
            Triple(omitted.copy(assignment = Assignment(AssignmentType.ANY)), InvoiceEncodedState.NON_FUNGIBLE, null),
            Triple(omitted.copy(assignmentName = "inflationAllowance"), InvoiceEncodedState.OMITTED, null),
        )
        for ((index, case) in cases.withIndex()) {
            val signer = FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), case.first).apply {
                rawInvoiceState = case.second; rawInvoiceAmount = case.third
            }
            val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response() }, signer)
            wallet.init(); wallet.unlock()
            try {
                if (index == 0) assertEquals(response(), wallet.onchainReceive(request.copy(amount = null)))
                else assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(request.copy(amount = null)) }
            } finally { wallet.destroy() }
        }
        val restricted = decoded().copy(assetId = null, assetSchema = "Nia", assignment = Assignment(AssignmentType.ANY))
        val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response() },
            FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), restricted))
        wallet.init(); wallet.unlock()
        try { assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(OnchainReceiveRequest()) } }
        finally { wallet.destroy() }
    }

    @Test
    fun `default duration and response evidence cannot be omitted or silently substituted`(): Unit = runBlocking {
        val valid = decoded().copy(expirationTimestamp = UnixSeconds.of(TEST_NOW + 86_400))
        val validResponse = response().copy(expirationTimestamp = valid.expirationTimestamp)
        for (returned in listOf(validResponse, validResponse.copy(recipientId = null), validResponse.copy(expirationTimestamp = null))) {
            val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = returned },
                FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), valid))
            wallet.init(); wallet.unlock()
            try {
                if (returned.recipientId != null) assertEquals(returned, wallet.onchainReceive(request.copy(duration = null)))
                else assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(request.copy(duration = null)) }
            } finally { wallet.destroy() }
        }
        for (expiration in listOf(null, UnixSeconds.of(TEST_NOW + 86_099), UnixSeconds.of(TEST_NOW + 86_701))) {
            val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response().copy(expirationTimestamp = expiration) },
                FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), decoded().copy(expirationTimestamp = expiration)))
            wallet.init(); wallet.unlock()
            try { assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.onchainReceive(request.copy(duration = null)) } }
            finally { wallet.destroy() }
        }
    }

    @Test
    fun `logical Utexo accepts wire Signet but never another Bitcoin network`(): Unit = runBlocking {
        for (network in listOf(Network.SIGNET, Network.TESTNET, Network.TESTNET4, Network.REGTEST, Network.MAINNET)) {
            val decoded = decoded().copy(network = network)
            val signer = FakeSigningEngine(testCapabilities(rgbInvoiceParsing = true), decoded).apply { identityNetwork = Network.UTEXO }
            val wallet = testWallet(FakeBackend().apply { onchainReceiveResponse = response() }, signer,
                configuration = testConfiguration().copy(network = Network.UTEXO))
            wallet.init(); wallet.unlock()
            try {
                if (network == Network.SIGNET) {
                    assertEquals(response(), wallet.onchainReceive(request))
                    assertEquals(Network.SIGNET, wallet.decodeRGBInvoice(invoice).network)
                } else {
                    assertFailsWith<SdkException.WrongNetwork> { wallet.onchainReceive(request) }
                    assertFailsWith<SdkException.WrongNetwork> { wallet.decodeRGBInvoice(invoice) }
                }
            } finally { wallet.destroy() }
        }
    }
}

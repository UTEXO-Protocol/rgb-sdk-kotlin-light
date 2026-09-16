package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.LocalSigningEngineFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WalletLifecycleTest {
    @Test
    fun `lifecycle is idempotent restartable and does not swallow disposal`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val backend = FakeBackend(events)
        val signer = FakeSigningEngine(events = events)
        val wallet = testWallet(backend, signer)

        assertFailsWith<SdkException.WalletLocked> {
            wallet.sendBtc(
                SendBtcRequest(
                    TEST_ADDRESS,
                    Satoshis.of(1_000),
                    idempotencyKey = TEST_IDEMPOTENCY_KEY,
                ),
            )
        }
        wallet.init()
        wallet.initialize()
        assertEquals(1, backend.initializeCalls)
        wallet.unlock()
        wallet.unlock()
        assertEquals(1, backend.registrationCalls)
        assertTrue(wallet.capabilities.localTransactionSigning)

        wallet.shutdown()
        assertTrue(signer.closed)
        assertFalse(wallet.isDisposed())

        val params = WalletUnlockParams.none()
        try {
            wallet.reinit(params)
        } finally {
            params.close()
        }
        assertEquals(2, backend.initializeCalls)
        assertEquals(2, backend.registrationCalls)

        wallet.destroy()
        wallet.dispose()
        assertTrue(wallet.isDisposed())
        assertEquals(2, backend.shutdownCalls)
        assertEquals(Network.SIGNET, wallet.getNetwork())
        assertFailsWith<SdkException.WalletDisposed> { wallet.init() }
    }

    @Test
    fun `wrong backend network fails initialization`(): Unit = runBlocking {
        val backend = object : WalletBackend {
            override suspend fun preflightOperation(operation: OperationKind): Unit = Unit

            override suspend fun initialize(request: BackendInitializeRequest): BackendSession =
                BackendSession("session", 1, Network.MAINNET)

            override suspend fun registerWallet(request: RegisterWalletRequest): Unit = Unit
            override suspend fun shutdown(session: BackendSession): Unit = Unit
        }
        val signer = FakeSigningEngine()
        val wallet = UTEXOWallet(
            configuration = testConfiguration(),
            backend = backend,
            keyProvider = WalletKeyProvider { SecretSeed.fromBytes(ByteArray(64)) },
            operationJournal = InMemoryOperationJournal(),
            signingEngineFactory = LocalSigningEngineFactory { _, _ -> signer },
            epochSecondsSource = EpochSecondsSource { TEST_NOW },
        )

        assertFailsWith<SdkException.WrongNetwork> { wallet.init() }
    }
}

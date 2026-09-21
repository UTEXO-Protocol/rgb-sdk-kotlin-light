package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.withOwnedDispatcherResult
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

internal class OwnedDispatcherResultTest {
    @Test
    fun `resource-close failure also destroys successfully computed undelivered secret`(): Unit = runBlocking {
        val seed = SecretSeed.fromBytes(ByteArray(64) { 0x42 })
        assertFailsWith<IllegalStateException> {
            withOwnedDispatcherResult(Dispatchers.Unconfined) { ownership ->
                AutoCloseable { error("storage-close failure") }.use {
                    ownership.retain(seed)
                }
            }
        }
        assertFailsWith<SdkException.InvalidState> { seed.copyForNative() }
    }
    @Test
    fun `prompt cancellation destroys each undelivered secret result`(): Unit = runBlocking {
        val seed = SecretSeed.fromBytes(ByteArray(64) { 0x42 })
        val key = WalletAPIKey.fromCharacters("test-only-credential".toCharArray())
        val mnemonic = SecretMnemonic.fromWords("test only mnemonic".toCharArray())
        val privateKey = SecretExtendedPrivateKey.fromCharacters("test-only-xpriv".toCharArray())
        val keys = GeneratedKeys(mnemonic, "xpub", "vanilla", "colored", "deadbeef", privateKey)
        for (secret in listOf(seed, key, keys)) {
            val caller = QueuedDispatcher()
            val worker = QueuedDispatcher()
            var delivered: AutoCloseable? = null
            val job = CoroutineScope(caller + Job()).launch {
                delivered = withOwnedDispatcherResult(worker) { secret }
            }
            caller.runNext() // Enter withContext and dispatch acquisition.
            worker.runNext() // Acquire secret; enqueue delivery to caller.
            job.cancel() // Cancel exactly between successful acquisition and delivery.
            caller.drain()
            job.join()
            assertNull(delivered)
        }
        assertFailsWith<SdkException.InvalidState> { seed.copyForNative() }
        assertFailsWith<SdkException.InvalidState> { key.authorizationValue() }
        assertFailsWith<SdkException.InvalidState> { mnemonic.useWords { Unit } }
        assertFailsWith<SdkException.InvalidState> { privateKey.copyAsString() }
    }

    @Test
    fun `successful delivery transfers ownership without premature close`(): Unit = runBlocking {
        var closes = 0
        val resource = AutoCloseable { closes++ }
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        var delivered: AutoCloseable? = null
        val job = CoroutineScope(caller + Job()).launch {
            delivered = withOwnedDispatcherResult(worker) { resource }
        }
        caller.runNext()
        worker.runNext()
        caller.drain()
        job.join()
        assertEquals(resource, delivered)
        assertEquals(0, closes)
        delivered?.close()
        assertEquals(1, closes)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable): Unit {
            tasks.addLast(block)
        }
        fun runNext(): Unit = tasks.removeFirst().run()
        fun drain(): Unit { while (tasks.isNotEmpty()) runNext() }
    }
}

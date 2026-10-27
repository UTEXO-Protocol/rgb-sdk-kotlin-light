package com.utexo.rgb.sdk

import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.LocalSigningEngineFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EncryptedOperationJournalTest {
    @TempDir
    lateinit var directory: Path
    private val cipher = JournalTestCipher()
    private fun file(
        aad: String = "package\u0000wallet-1\u0000signet",
        sync: (File) -> Unit = {},
    ): ProtectedWalletFile = ProtectedWalletFile(
        directory.toFile(), aad.toByteArray(), cipher, protectRollback = true, syncDirectory = sync,
    )
    private val target: File get() = directory.resolve("state.bin").toFile()

    @Test
    fun `consumer durably retains exact request before send and reconstructs it for two restarts`(): Unit = runBlocking {
        // This distinct app-owned record models a committed pending-payment database row.
        // It deliberately holds only public request/identity fields, never a PSBT or key.
        val requestCipher = JournalTestCipher()
        fun consumerStore() = ProtectedWalletFile(
            directory.resolve("consumer").toFile(), "consumer-payment-v1".toByteArray(), requestCipher,
            protectRollback = true, syncDirectory = {},
        )
        fun encode(request: SendBtcRequest): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1)
                out.writeUTF(testConfiguration().walletId.value)
                out.writeUTF(testConfiguration().network.wireValue)
                out.writeUTF("https://gateway.example")
                out.writeUTF("public-fixture-app-account")
                out.writeUTF(request.address.value)
                out.writeLong(request.amount.value)
                out.writeLong(request.feeRate.milliSatsPerVbyte)
                out.writeBoolean(request.skipSync)
                out.writeUTF(request.idempotencyKey.value)
            }
        }.toByteArray()
        suspend fun readRequest(): SendBtcRequest = consumerStore().open().use { store ->
            val bytes = requireNotNull(store.read())
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    check(input.readInt() == 1)
                    check(input.readUTF() == testConfiguration().walletId.value)
                    check(input.readUTF() == testConfiguration().network.wireValue)
                    check(input.readUTF() == "https://gateway.example")
                    check(input.readUTF() == "public-fixture-app-account")
                    SendBtcRequest(
                        address = BitcoinAddress.of(input.readUTF()),
                        amount = Satoshis.of(input.readLong()),
                        feeRate = FeeRate.milliSatsPerVbyte(input.readLong()),
                        skipSync = input.readBoolean(),
                        idempotencyKey = IdempotencyKey.of(input.readUTF()),
                    ).also { check(input.available() == 0) }
                }
            } finally { bytes.fill(0) }
        }
        // One new key per deliberately created payment, generated and durably saved BEFORE send.
        consumerStore().open().use { store ->
            assertNull(store.read())
            val bytes = encode(SendBtcRequest(TEST_ADDRESS, Satoshis.of(10_000), FeeRate.satPerVbyte("5.001")))
            try { store.write(bytes) } finally { bytes.fill(0) }
        }
        val appRecord = directory.resolve("consumer/state.bin").toFile().readBytes()
        val firstBackend = FakeBackend().apply { submitFailure = IOException("lost submit response") }
        val firstSigner = FakeSigningEngine()
        val first = testWallet(firstBackend, firstSigner, EncryptedOperationJournal.open(testConfiguration(), file()))
        first.init(); first.unlock()
        assertFailsWith<SdkException.OperationAmbiguous> { first.sendBtc(readRequest()) }
        assertEquals(1, firstBackend.prepareSendCalls)
        assertEquals(1, firstBackend.submitCalls)
        assertEquals(1, firstBackend.statusCalls)
        assertEquals(1, firstSigner.signCalls)
        first.destroy()

        // Discard the first wallet, journal and request objects. Only persisted rows/keys survive.
        val restartedBackend = FakeBackend(onPreflight = { throw SdkException.Unsupported("fresh send disabled") }).apply {
            status = BackendOperationStatus.Succeeded(TEST_OPERATION_ID, BackendOperationResult.SendBtc(TEST_OPERATION_ID, TEST_TXID))
        }
        val restartedSigner = FakeSigningEngine().apply { retainedValidationFailure = SdkException.PolicyRejected("no re-signing") }
        val restarted = testWallet(restartedBackend, restartedSigner, EncryptedOperationJournal.open(testConfiguration(), file()))
        restarted.init(); restarted.unlock()
        assertEquals(TEST_TXID, restarted.sendBtc(readRequest()))
        assertEquals(1, restartedBackend.statusCalls)
        assertEquals(0, restartedBackend.prepareSendCalls)
        assertEquals(0, restartedBackend.submitCalls)
        assertEquals(0, restartedSigner.signCalls)
        assertEquals(0, restartedSigner.retainedValidationCalls)
        assertFailsWith<SdkException.OperationConflict> {
            restarted.sendBtc(readRequest().copy(amount = Satoshis.of(10_001)))
        }
        restarted.destroy()

        // A crash after the SDK receipt commit but before the app marks success is also recoverable.
        val replayBackend = FakeBackend(onPreflight = { throw SdkException.Unsupported("fresh send disabled") })
        val replay = testWallet(replayBackend, FakeSigningEngine(), EncryptedOperationJournal.open(testConfiguration(), file()))
        replay.init(); replay.unlock()
        assertEquals(TEST_TXID, replay.sendBtc(readRequest()))
        assertEquals(0, replayBackend.prepareSendCalls)
        assertEquals(0, replayBackend.submitCalls)
        assertEquals(0, replayBackend.statusCalls)
        replay.destroy()
        assertContentEquals(appRecord, directory.resolve("consumer/state.bin").toFile().readBytes())
    }

    @Test
    fun `partial disk write poisons session and never replaces the last authenticated checkpoint`(): Unit = runBlocking {
        val original = "persisted-before-submit".toByteArray()
        file().open().use { it.write(original) }
        val failing = ProtectedWalletFile(
            directory.toFile(), "package\u0000wallet-1\u0000signet".toByteArray(), cipher,
            protectRollback = true,
            writeAndSync = { candidate, encoded ->
                candidate.outputStream().use { it.write(encoded, 0, encoded.size / 2) }
                throw IOException("ENOSPC fixture with private details")
            },
        ).open()
        val failure = assertFailsWith<SdkException.OperationJournalUnavailable> { failing.write("next-phase".toByteArray()) }
        assertNull(failure.cause)
        val read = assertFailsWith<SdkException.OperationJournalUnavailable> { failing.read() }
        val rewrite = assertFailsWith<SdkException.OperationJournalUnavailable> { failing.write("replacement".toByteArray()) }
        for (error in listOf(failure, read, rewrite)) {
            assertFalse(error.retryable)
            assertEquals("reopen_required", error.details["reason"])
        }
        failing.close()
        file().open().use {
            assertContentEquals(original, it.read())
            it.write("deliberate-next-checkpoint".toByteArray())
        }
        file().open().use { assertContentEquals("deliberate-next-checkpoint".toByteArray(), it.read()) }
    }

    @Test
    fun `full record capacity preserves oldest unresolved identity rejects new records and permits exact updates`(): Unit = runBlocking {
        val identity = testIdentity()
        val digest = MessageDigest.getInstance("SHA-256").digest(listOf(
            identity.network.wireValue, identity.chainNetwork, identity.derivationProfile,
            identity.masterFingerprint, identity.xpub, identity.accountXpubVanilla, identity.accountXpubColored,
        ).joinToString("\u0000").toByteArray())
        fun checkpoint(index: Int): OperationCheckpoint = OperationCheckpoint(
            testConfiguration().walletId, IdempotencyKey.of("capacity-$index"),
            RequestHash.ofLowercaseHex("d".repeat(64)), OperationKind.SEND_BTC, OperationPhase.CREATED,
        )
        val snapshot = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x5554584a); output.writeInt(2)
                output.writeByte(1); output.write(digest); output.writeByte(0); output.writeInt(100_000)
                repeat(100_000) { index ->
                    val encoded = OperationCheckpointCodec.encode(checkpoint(index))
                    output.writeInt(encoded.size); output.write(encoded)
                }
            }
        }.toByteArray()
        file().open().use { it.write(snapshot) }
        snapshot.fill(0)
        val started = System.nanoTime()
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindIdentity(identity)
        val initialBytes = target.readBytes()
        assertFailsWith<SdkException.OperationJournalUnavailable> { journal.save(checkpoint(100_000)) }
        assertContentEquals(initialBytes, target.readBytes())
        assertEquals(checkpoint(0), journal.load(testConfiguration().walletId, IdempotencyKey.of("capacity-0")))
        journal.save(checkpoint(99_999))
        journal.close()
        val reopened = EncryptedOperationJournal.open(testConfiguration(), file())
        reopened.bindIdentity(identity)
        assertEquals(checkpoint(99_999), reopened.load(testConfiguration().walletId, IdempotencyKey.of("capacity-99999")))
        reopened.close()
        println("journal-capacity-probe records=100000 bytes=${target.length()} elapsedMillis=${(System.nanoTime() - started) / 1_000_000}; host stress, not device SLA")
    }

    @Test
    fun `encrypted size limit rejects oversized sparse file before decoding`(): Unit = runBlocking {
        file().open().use { it.write("old".toByteArray()) }
        java.io.RandomAccessFile(target, "rw").use { it.setLength(ProtectedWalletFile.MAX_BYTES.toLong() + 1) }
        assertFailsWith<SdkException.OperationJournalUnavailable> { file().open() }
        assertEquals(ProtectedWalletFile.MAX_BYTES.toLong() + 1, target.length())
    }

    @Test
    fun `terminal receipt cannot acquire a validation report after completion`(): Unit = runBlocking {
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindIdentity(testIdentity())
        val receipt = signedCheckpoint().copy(
            phase = OperationPhase.TERMINAL,
            signedPayload = null,
            terminalOutcome = OperationTerminalOutcome.Expired(TEST_OPERATION_ID),
        ).compactTerminal()
        journal.save(receipt)
        assertFailsWith<SdkException.InternalInvariantViolation> {
            journal.save(receipt.copy(signedPayload = SignedOperationPayload("no-new-evidence", testValidation()).terminalReceipt()))
        }
        assertNull(journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)!!.signedPayload)
        journal.close()
    }

    @Test
    fun `facade replays compact success after restart without network mutation or signer validation`(): Unit = runBlocking {
        val request = SendBtcRequest(TEST_ADDRESS, Satoshis.of(10_000), FeeRate.satPerVbyte(5.0), idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val firstJournal = EncryptedOperationJournal.open(testConfiguration(), file())
        val first = testWallet(FakeBackend(), FakeSigningEngine(), firstJournal)
        first.init()
        first.unlock()
        assertEquals(TEST_TXID, first.sendBtc(request))
        first.destroy()
        val reopenedJournal = EncryptedOperationJournal.open(testConfiguration(), file())
        val backend = FakeBackend(onPreflight = { throw SdkException.PolicyRejected("disabled") })
        val signer = FakeSigningEngine().apply { retainedValidationFailure = SdkException.PolicyRejected("disabled") }
        val reopened = testWallet(backend, signer, reopenedJournal)
        reopened.init()
        reopened.unlock()
        assertEquals(TEST_TXID, reopened.sendBtc(request))
        assertEquals(0, backend.prepareSendCalls)
        assertEquals(0, backend.submitCalls)
        assertEquals(0, backend.statusCalls)
        assertEquals(0, signer.signCalls)
        assertEquals(0, signer.retainedValidationCalls)
        assertFailsWith<SdkException.OperationConflict> { reopened.sendBtc(request.copy(amount = Satoshis.of(10_001))) }
        reopened.destroy()
    }

    @Test
    fun `sustained terminal history remains compact without evicting old keys`(): Unit = runBlocking {
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindIdentity(testIdentity())
        val source = signedCheckpoint()
        val started = System.nanoTime()
        repeat(500) { index ->
            val key = IdempotencyKey.of("lifetime-$index")
            val operation = OperationId.of("operation-$index")
            val prepared = PreparedTransaction(
                operation, key, source.requestHash, Network.SIGNET, OperationKind.SEND_BTC,
                "p".repeat(20_000), UnixSeconds.of(TEST_NOW + 1000),
            )
            journal.save(OperationCheckpoint(
                source.walletId, key, source.requestHash, source.operationKind, OperationPhase.TERMINAL,
                operation, prepared, SignedOperationPayload("s".repeat(20_000), testValidation()),
                OperationTerminalOutcome.Succeeded(BackendOperationResult.SendBtc(operation, TEST_TXID)),
            ))
        }
        val bytes = target.length()
        assertTrue(bytes < 400_000, "500 receipts must not retain 20 MB of PSBT payloads")
        val first = journal.load(source.walletId, IdempotencyKey.of("lifetime-0"))!!
        val last = journal.load(source.walletId, IdempotencyKey.of("lifetime-499"))!!
        assertEquals(OperationPhase.TERMINAL, first.phase)
        assertEquals(OperationPhase.TERMINAL, last.phase)
        println("journal-lifetime-probe records=500 encryptedBytes=$bytes elapsedMillis=${(System.nanoTime() - started) / 1_000_000}; JVM test cipher, not device SLA")
        journal.close()
    }

    @Test
    fun `encrypted journal restores exact checkpoints after reopening`(): Unit = runBlocking {
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindServiceIdentity("https://gateway.example", "user-1")
        journal.bindIdentity(testIdentity())
        val checkpoint = signedCheckpoint()
        journal.save(checkpoint)
        assertFalse(target.readBytes().toString(Charsets.UTF_8).contains("signed-psbt-secret"))
        journal.close()
        val restored = EncryptedOperationJournal.open(testConfiguration(), file())
        restored.bindServiceIdentity("https://gateway.example", "user-1")
        restored.bindIdentity(testIdentity())
        val loaded = restored.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)!!
        assertContentEquals(OperationCheckpointCodec.encode(checkpoint), OperationCheckpointCodec.encode(loaded))
        restored.close()
    }

    @Test
    fun `terminal compaction retains replay identity result and validation after reopen`(): Unit = runBlocking {
        val signed = signedCheckpoint()
        val terminal = signed.copy(
            phase = OperationPhase.TERMINAL,
            terminalOutcome = OperationTerminalOutcome.Succeeded(BackendOperationResult.SendBtc(TEST_OPERATION_ID, TEST_TXID)),
        )
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindIdentity(testIdentity())
        journal.save(signed)
        journal.save(terminal)
        journal.close()
        val reopened = EncryptedOperationJournal.open(testConfiguration(), file())
        reopened.bindIdentity(testIdentity())
        val compact = reopened.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)!!
        assertNull(compact.preparedTransaction)
        assertEquals("", compact.signedPayload?.signedPsbtBase64)
        assertEquals(testValidation(), compact.signedPayload?.validation)
        assertEquals(terminal.terminalOutcome, compact.terminalOutcome)
        assertTrue(OperationCheckpointCodec.encode(compact).size < OperationCheckpointCodec.encode(terminal).size)
        reopened.save(terminal) // Same definitive outcome remains idempotent after compacting.
        assertFailsWith<SdkException.InternalInvariantViolation> {
            reopened.save(signed) // Receipt can never become sign/submit capable.
        }
        assertFailsWith<SdkException.InternalInvariantViolation> {
            reopened.save(compact.copy(requestHash = RequestHash.ofLowercaseHex("e".repeat(64))))
        }
        reopened.close()
    }

    @Test
    fun `v1 authenticated snapshot upgrades atomically while retaining ambiguous signed bytes`(): Unit = runBlocking {
        val signed = signedCheckpoint()
        val terminal = signed.copy(
            phase = OperationPhase.TERMINAL,
            terminalOutcome = OperationTerminalOutcome.Expired(TEST_OPERATION_ID),
        )
        val identity = testIdentity()
        val digest = MessageDigest.getInstance("SHA-256").digest(listOf(
            identity.network.wireValue, identity.chainNetwork, identity.derivationProfile,
            identity.masterFingerprint, identity.xpub, identity.accountXpubVanilla, identity.accountXpubColored,
        ).joinToString("\u0000").toByteArray())
        val legacy = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x5554584a)
                output.writeInt(1)
                output.writeByte(1)
                output.write(digest)
                output.writeByte(0)
                output.writeInt(1)
                val encoded = OperationCheckpointCodec.encode(terminal)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }.toByteArray()
        file().open().use { it.write(legacy) }
        val upgraded = EncryptedOperationJournal.open(testConfiguration(), file())
        upgraded.bindIdentity(identity)
        val key = IdempotencyKey.of("still-ambiguous")
        val pending = signed.copy(
            idempotencyKey = key, phase = OperationPhase.SUBMITTING,
            preparedTransaction = signed.preparedTransaction!!.let {
                PreparedTransaction(it.operationId, key, it.requestHash, it.network, it.operation, it.psbtBase64, it.expiresAt)
            },
        )
        upgraded.save(pending)
        upgraded.close()
        val reopened = EncryptedOperationJournal.open(testConfiguration(), file())
        reopened.bindIdentity(identity)
        assertNull(reopened.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)!!.preparedTransaction)
        assertContentEquals(OperationCheckpointCodec.encode(pending), OperationCheckpointCodec.encode(reopened.load(testConfiguration().walletId, key)!!))
        reopened.close()
    }

    @Test
    fun `concurrent instance fails while ownership survives until close`(): Unit = runBlocking {
        val first = file().open()
        val error = assertFailsWith<SdkException.InvalidState> { file().open() }
        assertEquals(SdkErrorCode.INVALID_STATE, error.code)
        assertFalse(error.retryable)
        first.close()
        file().open().close()
    }

    @Test
    fun `rejected same-process instance never drops the live cross-process lock`(): Unit = runBlocking {
        val lockFile = directory.resolve("session.lock").toFile()
        val first = file().open()
        try {
            assertFailsWith<SdkException.InvalidState> { file().open() }
            // Closing any second descriptor would release this process's POSIX lock.
            assertFalse(otherProcessCanLock(lockFile))
            descriptorsFor(lockFile)?.let { assertEquals(1, it) }
        } finally { first.close() }
        assertTrue(otherProcessCanLock(lockFile))
    }

    @Test
    fun `same-process lock overlap is invalid state and closes its probe handle`(): Unit = runBlocking {
        val lockFile = directory.resolve("session.lock").toFile()
        java.io.RandomAccessFile(lockFile, "rw").use { foreign ->
            foreign.channel.lock().use {
                val error = assertFailsWith<SdkException.InvalidState> { file().open() }
                assertFalse(error.retryable)
                descriptorsFor(lockFile)?.let { assertEquals(1, it) }
            }
        }
        file().open().close() // The failed attempt did not leave a stale in-process owner.
    }

    @Test
    fun `failed terminal write requires reopen and destroy plus create recovers the same key`(): Unit = runBlocking {
        val backend = FakeBackend().apply {
            status = BackendOperationStatus.Succeeded(TEST_OPERATION_ID, BackendOperationResult.SendBtc(TEST_OPERATION_ID, TEST_TXID))
        }
        val failing = ProtectedWalletFile(
            directory.toFile(), "package\u0000wallet-1\u0000signet".toByteArray(), cipher, protectRollback = true,
            writeAndSync = { candidate, encoded ->
                if (backend.submitCalls > 0) throw IOException("EIO fixture") // the post-broadcast TERMINAL write
                candidate.writeBytes(encoded)
            },
        )
        val request = SendBtcRequest(TEST_ADDRESS, Satoshis.of(10_000), FeeRate.satPerVbyte(5.0), idempotencyKey = TEST_IDEMPOTENCY_KEY)
        val first = testWallet(backend, FakeSigningEngine(), EncryptedOperationJournal.open(testConfiguration(), failing))
        first.init(); first.unlock()
        repeat(2) { // The failing write itself and every later same-instance retry say the same thing.
            val error = assertFailsWith<SdkException.OperationJournalUnavailable> { first.sendBtc(request) }
            assertEquals(SdkErrorCode.OPERATION_JOURNAL_UNAVAILABLE, error.code)
            assertFalse(error.retryable)
            assertEquals(mapOf("reason" to "reopen_required"), error.details)
            assertNull(error.cause)
        }
        assertEquals(1, backend.submitCalls)
        assertEquals(0, backend.statusCalls)
        first.destroy()

        val reopened = testWallet(backend, FakeSigningEngine(), EncryptedOperationJournal.open(testConfiguration(), file()))
        reopened.init(); reopened.unlock()
        assertEquals(TEST_TXID, reopened.sendBtc(request))
        assertEquals(1, backend.submitCalls)
        assertEquals(1, backend.statusCalls)
        reopened.destroy()
    }

    @Test
    fun `failure before any file change stays retryable on the same session`(): Unit = runBlocking {
        file().open().use { session ->
            session.write("old".toByteArray())
            cipher.failAfterEncryption = true
            val transient = assertFailsWith<SdkException.OperationJournalUnavailable> { session.write("new".toByteArray()) }
            assertTrue(transient.retryable)
            assertTrue(transient.details.isEmpty())
            cipher.failAfterEncryption = false
            session.write("new".toByteArray())
            assertContentEquals("new".toByteArray(), session.read())
        }
    }

    @Test
    fun `destroy releases storage ownership even when called from a canceled task`(): Unit = runBlocking {
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        val wallet = UTEXOWallet(
            testConfiguration(), FakeBackend(), WalletKeyProvider { SecretSeed.fromBytes(ByteArray(64)) },
            journal, LocalSigningEngineFactory { _, _ -> FakeSigningEngine() }, EpochSecondsSource { TEST_NOW },
        )
        assertFailsWith<CancellationException> {
            withContext(Job()) {
                coroutineContext.cancel()
                wallet.destroy()
            }
        }
        assertEquals(true, wallet.isDisposed())
        file().open().close()
    }

    @Test
    fun `wallet signer service account and origin bindings survive restart`(): Unit = runBlocking {
        EncryptedOperationJournal.open(testConfiguration(), file()).let { journal ->
            journal.bindServiceIdentity("https://gateway.example", "user-1")
            journal.bindIdentity(testIdentity())
            journal.close()
        }
        val reopened = EncryptedOperationJournal.open(testConfiguration(), file())
        assertFailsWith<SdkException.InvalidState> {
            reopened.bindIdentity(testIdentity().copy(accountXpubVanilla = "different-xpub"))
        }
        assertFailsWith<SdkException.InvalidState> { reopened.bindServiceIdentity("https://other.example", "user-1") }
        assertFailsWith<SdkException.InvalidState> { reopened.bindServiceIdentity("https://gateway.example", "user-2") }
        reopened.bindIdentity(testIdentity())
        assertFailsWith<SdkException.OperationJournalUnavailable> {
            reopened.load(WalletId.of("another-wallet"), TEST_IDEMPOTENCY_KEY)
        }
        reopened.close()
    }

    @Test
    fun `tampering with ciphertext or using another AAD fails closed`(): Unit = runBlocking {
        file().open().use { it.write("sensitive".toByteArray()) }
        assertFailsWith<SdkException.OperationJournalUnavailable> { file(aad = "wrong-network").open() }
        target.writeBytes(target.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertFailsWith<SdkException.OperationJournalUnavailable> { file().open() }
    }

    @Test
    fun `removal of wrapping key never provisions a replacement`(): Unit = runBlocking {
        file().open().use { it.write("sensitive".toByteArray()) }
        cipher.key = null
        val creations = cipher.keyCreations
        assertFailsWith<SdkException.OperationJournalUnavailable> { file().open() }
        assertEquals(creations, cipher.keyCreations)
    }

    @Test
    fun `removal of ciphertext is not treated as a new wallet`(): Unit = runBlocking {
        file().open().use { it.write("sensitive".toByteArray()) }
        target.delete()
        assertFailsWith<SdkException.OperationJournalUnavailable> { file().open() }
    }

    @Test
    fun `valid old signed snapshot cannot replace committed submitting snapshot`(): Unit = runBlocking {
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindIdentity(testIdentity())
        val checkpoint = signedCheckpoint()
        journal.save(checkpoint)
        val signedSnapshot = target.readBytes()
        journal.save(checkpoint.copy(phase = OperationPhase.SUBMITTING))
        journal.close()
        target.writeBytes(signedSnapshot)
        assertFailsWith<SdkException.OperationJournalUnavailable> {
            EncryptedOperationJournal.open(testConfiguration(), file())
        }
    }

    @Test
    fun `failed directory sync poisons session and reopening recovers new durable record`(): Unit = runBlocking {
        file().open().use { it.write("old".toByteArray()) }
        var syncs = 0
        val broken = file(sync = { if (++syncs == 2) throw IOException("private file path") }).open()
        syncs = 0
        val failure = assertFailsWith<SdkException.OperationJournalUnavailable> { broken.write("new".toByteArray()) }
        assertNull(failure.cause)
        assertFailsWith<SdkException.OperationJournalUnavailable> { broken.read() }
        broken.close()
        assertEquals(2, cipher.anchors().size)
        assertFailsWith<SdkException.OperationJournalUnavailable> { file(sync = { throw IOException() }).open() }
        assertEquals(2, cipher.anchors().size)
        file().open().use { assertContentEquals("new".toByteArray(), it.read()) }
        assertEquals(1, cipher.anchors().size)
    }

    @Test
    fun `failed pending anchor leaves previous record recoverable without submitting`(): Unit = runBlocking {
        file().open().use { it.write("old".toByteArray()) }
        val broken = file().open()
        cipher.failAfterAnchor = true
        assertFailsWith<SdkException.OperationJournalUnavailable> { broken.write("new".toByteArray()) }
        broken.close()
        cipher.failAfterAnchor = false
        file().open().use { assertContentEquals("old".toByteArray(), it.read()) }
        assertEquals(1, cipher.anchors().size)
    }

    @Test
    fun `interrupted first wrapping key creation reuses the same key`(): Unit = runBlocking {
        val interrupted = file().open()
        cipher.failAfterEncryption = true
        assertFailsWith<SdkException.OperationJournalUnavailable> { interrupted.write("first".toByteArray()) }
        interrupted.close()
        cipher.failAfterEncryption = false
        val creations = cipher.keyCreations
        file().open().use { it.write("first".toByteArray()) }
        assertEquals(creations, cipher.keyCreations)
    }

    @Test
    fun `torn first temporary write before anchor reuses wrapping key without retaining corruption`(): Unit = runBlocking {
        val interrupted = file().open()
        cipher.failAfterEncryption = true
        assertFailsWith<SdkException.OperationJournalUnavailable> { interrupted.write("first".toByteArray()) }
        interrupted.close()
        cipher.failAfterEncryption = false
        directory.resolve("state.tmp").toFile().writeBytes(byteArrayOf(1, 2, 3))
        file().open().use {
            assertNull(it.read())
            it.write("first".toByteArray())
        }
        assertEquals(1, cipher.keyCreations)
    }

    @Test
    fun `corrupted anchored bootstrap candidate remains fail closed`(): Unit = runBlocking {
        val interrupted = file().open()
        cipher.failAfterAnchor = true
        assertFailsWith<SdkException.OperationJournalUnavailable> { interrupted.write("first".toByteArray()) }
        interrupted.close()
        cipher.failAfterAnchor = false
        val candidate = directory.resolve("state.tmp").toFile()
        candidate.writeBytes(byteArrayOf(1, 2, 3))
        assertFailsWith<SdkException.OperationJournalUnavailable> { file().open() }
        assertEquals(true, candidate.exists())
    }

    @Test
    fun `interrupted first pending anchor recovers exact authenticated temporary file`(): Unit = runBlocking {
        val interrupted = file().open()
        cipher.failAfterAnchor = true
        assertFailsWith<SdkException.OperationJournalUnavailable> { interrupted.write("first".toByteArray()) }
        interrupted.close()
        cipher.failAfterAnchor = false
        file().open().use { assertContentEquals("first".toByteArray(), it.read()) }
        assertEquals(1, cipher.keyCreations)
        assertEquals(1, cipher.anchors().size)
    }

    @Test
    fun `interrupted first rename recovers file and commits existing anchor`(): Unit = runBlocking {
        var syncs = 0
        val interrupted = file(sync = { if (++syncs == 2) throw IOException() }).open()
        assertFailsWith<SdkException.OperationJournalUnavailable> { interrupted.write("first".toByteArray()) }
        interrupted.close()
        file().open().use { assertContentEquals("first".toByteArray(), it.read()) }
        assertEquals(true, cipher.hasCommittedState())
    }

    @Test
    fun `phase rollback request conflict and changed signed bytes are rejected`(): Unit = runBlocking {
        val journal = EncryptedOperationJournal.open(testConfiguration(), file())
        journal.bindIdentity(testIdentity())
        val checkpoint = signedCheckpoint()
        journal.save(checkpoint)
        assertFailsWith<SdkException.InternalInvariantViolation> {
            journal.save(checkpoint.copy(phase = OperationPhase.PREPARED, signedPayload = null))
        }
        assertFailsWith<SdkException.InternalInvariantViolation> {
            journal.save(checkpoint.copy(signedPayload = SignedOperationPayload("changed", testValidation())))
        }
        journal.save(checkpoint.copy(phase = OperationPhase.SUBMITTING))
        assertEquals(OperationPhase.SUBMITTING, journal.load(testConfiguration().walletId, TEST_IDEMPOTENCY_KEY)?.phase)
        journal.close()
    }

    @Test
    fun `corrupt or oversized snapshot fails without allocation from hostile lengths`(): Unit = runBlocking {
        file().open().use { it.write(byteArrayOf(1, 2, 3)) }
        assertFailsWith<SdkException.OperationJournalUnavailable> {
            EncryptedOperationJournal.open(testConfiguration(), file())
        }
    }

    private fun signedCheckpoint(): OperationCheckpoint {
        val prepared = PreparedTransaction(
            TEST_OPERATION_ID, TEST_IDEMPOTENCY_KEY, RequestHash.ofLowercaseHex("d".repeat(64)),
            Network.SIGNET, OperationKind.SEND_BTC, "prepared-psbt", UnixSeconds.of(TEST_NOW + 1000),
        )
        return OperationCheckpoint(
            testConfiguration().walletId, TEST_IDEMPOTENCY_KEY, prepared.requestHash,
            OperationKind.SEND_BTC, OperationPhase.SIGNED, TEST_OPERATION_ID, prepared,
            SignedOperationPayload("signed-psbt-secret", testValidation()),
        )
    }
}

/** POSIX record locks are per process, so only a child process can observe whether one is held. */
internal object CrossProcessLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val acquired = java.io.RandomAccessFile(args[0], "rw").use { it.channel.tryLock() != null }
        System.exit(if (acquired) 0 else 3)
    }
}

private fun otherProcessCanLock(file: File): Boolean {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        CrossProcessLockProbe::class.java.name, file.absolutePath).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return when (val exit = process.waitFor()) {
        0 -> true
        3 -> false
        else -> error("cross-process lock probe failed ($exit): $output")
    }
}

/** Open descriptors for [file] in this process, or null where /proc is unavailable. */
private fun descriptorsFor(file: File): Int? = File("/proc/self/fd").takeIf { it.isDirectory }?.listFiles()
    ?.count { runCatching { it.canonicalPath }.getOrNull() == file.canonicalPath }

private class JournalTestCipher : ProtectedStorageCipher {
    var key: SecretKey? = null
    var keyCreations = 0
    var failAfterAnchor = false
    var failAfterEncryption = false
    private val commits = mutableSetOf<String>()
    private var initialized = false
    override fun hasCommittedState(): Boolean = initialized
    override fun markCommittedState(): Unit { initialized = true }
    override fun keyExists(): Boolean = key != null
    override fun encrypt(plaintext: ByteArray, aad: ByteArray, createKey: Boolean): EncryptedSeed {
        if (key == null && createKey) {
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            keyCreations += 1
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key ?: throw IOException())
        cipher.updateAAD(aad)
        val encrypted = EncryptedSeed(cipher.iv, cipher.doFinal(plaintext))
        if (failAfterEncryption) throw IOException()
        return encrypted
    }
    override fun decrypt(encrypted: EncryptedSeed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key ?: throw IOException(), GCMParameterSpec(128, encrypted.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.ciphertext)
    }
    override fun anchors(): Set<String> = commits.toSet()
    override fun addAnchor(hash: String): Unit {
        commits.add(hash)
        if (failAfterAnchor) throw IOException()
    }
    override fun removeAnchor(hash: String): Unit { commits.remove(hash) }
}

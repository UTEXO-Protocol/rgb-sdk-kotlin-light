package com.utexo.rgb.sdk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the wallet's lifetime storage lease. The authenticated snapshot is the source of truth. */
internal class EncryptedOperationJournal private constructor(
    private val configuration: WalletConfiguration,
    private val session: ProtectedWalletFile.Session,
) : OperationJournal, AutoCloseable {
    private val gate = Mutex()
    private var identityHash: ByteArray? = null

    suspend fun bindServiceIdentity(origin: String, userId: String): Unit = gate.withLock {
        withContext(Dispatchers.IO) {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest("$origin\u0000$userId".toByteArray(Charsets.UTF_8))
            val snapshot = readSnapshot()
            if (snapshot.serviceHash != null && !MessageDigest.isEqual(snapshot.serviceHash, hash)) {
                throw SdkException.InvalidState("initialize", "bound to a different service or account")
            }
            if (snapshot.serviceHash == null) writeSnapshot(snapshot.copy(serviceHash = hash))
        }
    }

    suspend fun bindIdentity(identity: WalletIdentity): Unit = gate.withLock {
        withContext(Dispatchers.IO) {
            val hash = identityDigest(identity)
            val snapshot = readSnapshot()
            val bound = snapshot.identityHash
            if (bound != null && !MessageDigest.isEqual(bound, hash)) {
                throw SdkException.InvalidState("unlock", "bound to different wallet key material")
            }
            if (bound == null) writeSnapshot(snapshot.copy(identityHash = hash))
            identityHash = hash
        }
    }

    override suspend fun load(walletId: WalletId, idempotencyKey: IdempotencyKey): OperationCheckpoint? =
        gate.withLock {
            withContext(Dispatchers.IO) {
                requireWallet(walletId)
                val snapshot = readBoundSnapshot()
                snapshot.checkpoints[idempotencyKey]
            }
        }

    override suspend fun save(checkpoint: OperationCheckpoint): Unit = gate.withLock {
        withContext(Dispatchers.IO) {
            requireWallet(checkpoint.walletId)
            if (checkpoint.preparedTransaction?.network?.let { it != configuration.network } == true) {
                throw SdkException.OperationJournalUnavailable()
            }
            val snapshot = readBoundSnapshot()
            // Reuse the same immutable identity/phase/signed-byte invariants as the tested journal.
            val validator = InMemoryOperationJournal()
            snapshot.checkpoints[checkpoint.idempotencyKey]?.let { validator.save(it) }
            validator.save(checkpoint)
            writeSnapshot(snapshot.copy(checkpoints = snapshot.checkpoints + (checkpoint.idempotencyKey to checkpoint.compactTerminal())))
        }
    }

    private fun readBoundSnapshot(): Snapshot {
        val expected = identityHash ?: throw SdkException.InvalidState("use journal", "wallet key identity is unbound")
        val snapshot = readSnapshot()
        if (snapshot.identityHash == null || !MessageDigest.isEqual(expected, snapshot.identityHash)) {
            throw SdkException.OperationJournalUnavailable()
        }
        return snapshot
    }

    private fun readSnapshot(): Snapshot = storageBoundary {
        val plaintext = session.read() ?: throw IOException()
        try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() !in 1..2) throw IOException()
                val identity = when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> ByteArray(32).also(input::readFully)
                    else -> throw IOException()
                }
                val service = when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> ByteArray(32).also(input::readFully)
                    else -> throw IOException()
                }
                val count = input.readInt()
                if (count !in 0..MAX_RECORDS || (identity == null && count > 0)) throw IOException()
                val checkpoints = linkedMapOf<IdempotencyKey, OperationCheckpoint>()
                repeat(count) {
                    val length = input.readInt()
                    if (length !in 1..OperationCheckpointCodec.MAX_ENCODED_BYTES || length > input.available()) {
                        throw IOException()
                    }
                    val bytes = ByteArray(length).also(input::readFully)
                    val checkpoint = try { OperationCheckpointCodec.decode(bytes) } finally { bytes.fill(0) }
                    requireWallet(checkpoint.walletId)
                    if (checkpoint.preparedTransaction?.network?.let { it != configuration.network } == true ||
                        checkpoints.put(checkpoint.idempotencyKey, checkpoint) != null
                    ) throw IOException()
                }
                if (input.available() != 0) throw IOException()
                Snapshot(identity, service, checkpoints)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeSnapshot(snapshot: Snapshot): Unit = storageBoundary {
        if (snapshot.checkpoints.size > MAX_RECORDS) throw IOException()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(2)
            data.writeByte(if (snapshot.identityHash == null) 0 else 1)
            snapshot.identityHash?.let(data::write)
            data.writeByte(if (snapshot.serviceHash == null) 0 else 1)
            snapshot.serviceHash?.let(data::write)
            data.writeInt(snapshot.checkpoints.size)
            snapshot.checkpoints.toSortedMap(compareBy { it.value }).values.forEach { checkpoint ->
                val encoded = OperationCheckpointCodec.encode(checkpoint.compactTerminal())
                try {
                    if (output.size().toLong() + encoded.size + 4 > ProtectedWalletFile.MAX_BYTES - 40) throw IOException()
                    data.writeInt(encoded.size)
                    data.write(encoded)
                } finally {
                    encoded.fill(0)
                }
            }
        }
        val plaintext = output.toByteArray()
        try { session.write(plaintext) } finally { plaintext.fill(0) }
    }

    private fun requireWallet(walletId: WalletId): Unit {
        if (walletId != configuration.walletId) throw SdkException.OperationJournalUnavailable()
    }

    override fun close(): Unit = storageBoundary { session.close() }

    private data class Snapshot(
        val identityHash: ByteArray?,
        val serviceHash: ByteArray?,
        val checkpoints: Map<IdempotencyKey, OperationCheckpoint>,
    )

    internal companion object {
        private const val MAGIC = 0x5554584a
        private const val MAX_RECORDS = 100_000

        suspend fun open(configuration: WalletConfiguration, file: ProtectedWalletFile): EncryptedOperationJournal {
            var acquired: ProtectedWalletFile.Session? = null
            return try {
                withContext(Dispatchers.IO) {
                    val session = file.open().also { acquired = it }
                    try {
                        val journal = EncryptedOperationJournal(configuration, session)
                        val previous = session.read()
                        if (previous == null) journal.writeSnapshot(Snapshot(null, null, emptyMap())) else previous.fill(0)
                        journal.readSnapshot()
                        journal
                    } catch (failure: Exception) {
                        session.close()
                        throw failure
                    }
                }
            } catch (failure: Exception) {
                runCatching { acquired?.close() }
                throw failure
            }
        }

        private fun identityDigest(identity: WalletIdentity): ByteArray {
            val bytes = listOf(
                identity.network.wireValue, identity.chainNetwork, identity.derivationProfile,
                identity.masterFingerprint, identity.xpub, identity.accountXpubVanilla, identity.accountXpubColored,
            ).joinToString("\u0000").toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes)
        }
    }
}

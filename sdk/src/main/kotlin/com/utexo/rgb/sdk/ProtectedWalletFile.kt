package com.utexo.rgb.sdk

import android.content.Context
import com.utexo.rgb.sdk.internal.withOwnedDispatcherResult
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separate from ciphertext so a restored older journal cannot replay a pre-submission phase. */
internal interface ProtectedStorageCipher {
    fun keyExists(): Boolean
    fun encrypt(plaintext: ByteArray, aad: ByteArray, createKey: Boolean): EncryptedSeed
    fun decrypt(encrypted: EncryptedSeed, aad: ByteArray): ByteArray
    fun anchors(): Set<String>
    fun addAnchor(hash: String)
    fun removeAnchor(hash: String)
    fun hasCommittedState(): Boolean
    fun markCommittedState()
}

internal class AndroidProtectedStorageCipher(private val alias: String) : ProtectedStorageCipher {
    private val cipher = AndroidKeystoreSeedCipher(alias, AndroidKeystoreSeedStoreConfiguration())
    private val anchorPrefix = "$alias.commit."
    private val initializedAlias = "$alias.initialized"
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun keyExists(): Boolean = cipher.keyExists()
    override fun encrypt(plaintext: ByteArray, aad: ByteArray, createKey: Boolean): EncryptedSeed =
        if (createKey) cipher.encrypt(plaintext, aad) else cipher.encryptWithExistingKey(plaintext, aad)
    override fun decrypt(encrypted: EncryptedSeed, aad: ByteArray): ByteArray = cipher.decrypt(encrypted, aad)
    override fun anchors(): Set<String> = keyStore().aliases().toList()
        .filter { it.startsWith(anchorPrefix) }.map { it.removePrefix(anchorPrefix) }.toSet()
    override fun addAnchor(hash: String): Unit = createMarker(anchorPrefix + hash)
    override fun hasCommittedState(): Boolean = keyStore().containsAlias(initializedAlias)
    override fun markCommittedState(): Unit {
        if (!hasCommittedState()) createMarker(initializedAlias)
    }
    private fun createMarker(markerAlias: String): Unit {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(markerAlias, KeyProperties.PURPOSE_ENCRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
        )
        generator.generateKey()
    }
    override fun removeAnchor(hash: String): Unit = keyStore().deleteEntry(anchorPrefix + hash)
}

/** Bounded authenticated file with an exclusive cross-process lease and atomic durable replacement. */
internal class ProtectedWalletFile(
    private val directory: File,
    private val identity: ByteArray,
    private val cipher: ProtectedStorageCipher,
    private val protectRollback: Boolean = false,
    private val secureFile: (File) -> Unit = {},
    private val secureDirectory: (File) -> Unit = {},
    private val validateFile: (File) -> Unit = {},
    private val syncDirectory: (File) -> Unit = {},
    private val writeAndSync: (File, ByteArray) -> Unit = { file, bytes ->
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    },
) {
    private val target = File(directory, "state.bin")
    private val temporary = File(directory, "state.tmp")

    suspend fun open(): Session {
        var acquired: Session? = null
        return try {
            withContext(Dispatchers.IO) {
                storageBoundary {
                    if (!directory.exists() && !directory.mkdirs()) throw IOException()
                    if (!directory.isDirectory) throw IOException()
                    secureDirectory(directory)
                    val lockFile = File(directory, "session.lock")
                    // Check in-process owners before opening a descriptor: closing any descriptor
                    // for this file drops the process's POSIX lock, even one a live session holds.
                    val owner = lockFile.canonicalPath
                    if (!OWNED_LOCKS.add(owner)) {
                        throw SdkException.InvalidState("open wallet storage", "already owned by another session")
                    }
                    var transferred = false
                    try {
                        validateFile(lockFile)
                        val handle = RandomAccessFile(lockFile, "rw")
                        try {
                            validateFile(lockFile)
                            secureFile(lockFile)
                            // A same-JVM overlap throws instead of returning null; both mean another owner.
                            val lock = try {
                                handle.channel.tryLock()
                            } catch (_: OverlappingFileLockException) {
                                null
                            } ?: throw SdkException.InvalidState(
                                "open wallet storage", "already owned by another session",
                            )
                            Session(handle, lock, owner).also { session ->
                                transferred = true
                                acquired = session
                                try {
                                    session.validateState()
                                } catch (failure: Exception) {
                                    session.close()
                                    throw failure
                                }
                            }
                        } catch (failure: Exception) {
                            handle.close()
                            throw failure
                        }
                    } finally {
                        if (!transferred) OWNED_LOCKS.remove(owner)
                    }
                }
            }
        } catch (failure: Exception) {
            // withContext has prompt cancellation on return; release even when its successful
            // result cannot be delivered to the caller.
            runCatching { acquired?.close() }
            throw failure
        }
    }

    suspend fun <T> access(block: (Session) -> T): T = withOwnedDispatcherResult(Dispatchers.IO) { ownership ->
        open().use { session -> storageBoundary { ownership.retain(block(session)) } }
    }

    internal inner class Session(
        private val handle: RandomAccessFile,
        private val lock: FileLock,
        private val owner: String,
    ) : AutoCloseable {
        private var closed = false
        private var poisoned = false

        fun validateState(): Unit {
            validateFile(target)
            validateFile(temporary)
            if (!target.exists()) {
                if (cipher.hasCommittedState()) throw IOException()
                if (temporary.exists()) {
                    // Interrupted first write: finish only the exact authenticated candidate.
                    if (!cipher.keyExists()) throw IOException()
                    val anchors = cipher.anchors()
                    val encoded = try {
                        readBounded(temporary).also { candidate ->
                            try { decodeEnvelope(candidate).fill(0) } catch (failure: Exception) {
                                candidate.fill(0)
                                throw failure
                            }
                        }
                    } catch (failure: Exception) {
                        // A torn first write before any commit anchor cannot contain a previously
                        // authorized operation. Reuse its original key for a deliberate fresh write.
                        if (anchors.isNotEmpty()) throw failure
                        if (!temporary.delete()) throw IOException()
                        syncDirectory(directory)
                        return
                    }
                    try {
                        val hash = digest(encoded)
                        if (anchors.isNotEmpty() && anchors != setOf(hash)) throw IOException()
                        if (protectRollback && anchors.isEmpty()) cipher.addAnchor(hash)
                        if (!temporary.renameTo(target)) throw IOException()
                        syncDirectory(directory)
                        cipher.markCommittedState()
                    } finally {
                        encoded.fill(0)
                    }
                } else if (cipher.anchors().isNotEmpty()) {
                    throw IOException() // pending/committed data was removed
                }
                // An orphan wrapping key with no data, marker, or pending anchor can only be
                // an interrupted bootstrap. Reuse it; never generate a replacement key.
            } else {
                if (!target.isFile || !cipher.keyExists()) throw IOException()
                // Recovering a renamed candidate must make its directory entry durable before
                // read retires the previous commit anchor, including after an earlier fsync error.
                syncDirectory(directory)
                read()?.fill(0)
                cipher.markCommittedState()
            }
        }

        fun read(): ByteArray? = storageBoundary {
            checkOpen()
            validateFile(target)
            if (!target.exists()) {
                if (cipher.hasCommittedState() || cipher.anchors().isNotEmpty() || temporary.exists()) throw IOException()
                return@storageBoundary null
            }
            val encoded = readBounded(target)
            try {
                val hash = digest(encoded)
                val anchors = if (protectRollback) cipher.anchors() else emptySet()
                if (protectRollback && (hash !in anchors || anchors.size > 2)) throw IOException()
                val plaintext = decodeEnvelope(encoded)
                try {
                    if (protectRollback) anchors.filter { it != hash }.forEach(cipher::removeAnchor)
                    plaintext
                } catch (failure: Exception) {
                    plaintext.fill(0)
                    throw failure
                }
            } finally {
                encoded.fill(0)
            }
        }

        fun write(plaintext: ByteArray): Unit = storageBoundary {
            checkOpen()
            if (plaintext.isEmpty() || plaintext.size > MAX_BYTES - 40) throw IOException()
            validateFile(target)
            val existing = target.exists()
            if (existing && !cipher.keyExists()) throw IOException()
            if (!existing && (cipher.hasCommittedState() || cipher.anchors().isNotEmpty())) throw IOException()
            val encrypted = cipher.encrypt(plaintext, identity, createKey = !cipher.keyExists())
            val encoded = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(1)
                    output.writeInt(encrypted.iv.size)
                    output.write(encrypted.iv)
                    output.write(encrypted.ciphertext)
                }
                buffer.toByteArray()
            }
            try {
                val hash = digest(encoded)
                // Persist pending commit anchor after the candidate file. Retire the previous anchor only
                // after file + parent directory are durable. A crash can recover either valid side.
                validateFile(temporary)
                if (temporary.exists() && !temporary.delete()) throw IOException()
                if (!temporary.createNewFile()) throw IOException()
                validateFile(temporary)
                secureFile(temporary)
                writeAndSync(temporary, encoded)
                syncDirectory(directory)
                if (protectRollback) cipher.addAnchor(hash)
                if (target.exists()) validateFile(target)
                if (!temporary.renameTo(target)) throw IOException()
                syncDirectory(directory)
                cipher.markCommittedState()
                if (protectRollback) cipher.anchors().filter { it != hash }.forEach(cipher::removeAnchor)
            } catch (_: Exception) {
                // The write outcome may be uncertain; no more payments can use this instance.
                // Only a reopen re-validates anchors and the directory, so a same-instance retry never helps.
                poisoned = true
                throw SdkException.OperationJournalUnavailable(reopenRequired = true)
            } finally {
                encoded.fill(0)
            }
        }

        private fun decodeEnvelope(encoded: ByteArray): ByteArray =
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != 1 || input.readInt() != 12) {
                    throw IOException()
                }
                val iv = ByteArray(12)
                input.readFully(iv)
                val ciphertext = ByteArray(input.available())
                input.readFully(ciphertext)
                cipher.decrypt(EncryptedSeed(iv, ciphertext), identity)
            }

        private fun readBounded(file: File): ByteArray {
            validateFile(file)
            val length = file.length()
            if (length !in 40L..MAX_BYTES.toLong()) throw IOException()
            return FileInputStream(file).use { input ->
                val bytes = ByteArray(length.toInt())
                DataInputStream(input).readFully(bytes)
                if (input.read() != -1) throw IOException()
                bytes
            }
        }

        private fun checkOpen(): Unit {
            if (closed || !lock.isValid) throw IOException()
            if (poisoned) throw SdkException.OperationJournalUnavailable(reopenRequired = true)
        }

        override fun close(): Unit {
            if (closed) return
            closed = true
            try {
                lock.release()
            } finally {
                try {
                    handle.close()
                } finally {
                    // Only after the descriptor is closed may a successor open its own.
                    OWNED_LOCKS.remove(owner)
                }
            }
        }
    }

    internal companion object {
        const val MAX_BYTES = 64 * 1_024 * 1_024
        private const val MAGIC = 0x55545845
        /** Canonical lock paths owned by live sessions in this process. */
        private val OWNED_LOCKS: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun forAndroid(
            context: Context,
            walletId: WalletId,
            network: Network,
            purpose: String,
            protectRollback: Boolean = false,
            authenticatedServiceOrigin: String? = null,
        ): ProtectedWalletFile {
            val application = context.applicationContext ?: context
            val identity = listOf("utexo.android.$purpose.v1", application.packageName, walletId.value, network.wireValue)
                .joinToString("\u0000").toByteArray(Charsets.UTF_8)
            val identifier = digest(identity)
            // Keep the storage/key identity stable across origin changes so an existing wallet
            // fails authentication before a credential provider or HTTP request can run.
            val authenticatedIdentity = if (authenticatedServiceOrigin == null) identity else
                identity + byteArrayOf(0) + authenticatedServiceOrigin.toByteArray(Charsets.UTF_8)
            return ProtectedWalletFile(
                File(application.noBackupFilesDir.canonicalFile, "utexo-rgb-sdk/$purpose-v1-$identifier"),
                authenticatedIdentity,
                AndroidProtectedStorageCipher("com.utexo.rgb.sdk.$purpose.v1.$identifier"),
                protectRollback,
                secureFile = { Os.chmod(it.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR) },
                secureDirectory = {
                    listOf(it.parentFile, it).forEach { directory ->
                        val status = Os.lstat(directory.absolutePath)
                        if (!OsConstants.S_ISDIR(status.st_mode) || status.st_uid != Os.getuid()) throw IOException()
                        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
                    }
                },
                validateFile = {
                    val status = try { Os.lstat(it.absolutePath) } catch (failure: ErrnoException) {
                        if (failure.errno != OsConstants.ENOENT) throw failure
                        null
                    }
                    if (status != null) {
                        if (!OsConstants.S_ISREG(status.st_mode) || status.st_nlink != 1L || status.st_uid != Os.getuid()) {
                            throw IOException()
                        }
                    }
                },
                syncDirectory = { directory ->
                    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
                    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
                },
            )
        }

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal inline fun <T> storageBoundary(block: () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (sdk: SdkException) {
    throw sdk
} catch (_: Exception) {
    throw SdkException.OperationJournalUnavailable()
}

package com.utexo.rgb.sdk

import androidx.annotation.RequiresApi
import com.utexo.rgb.sdk.internal.withOwnedDispatcherResult
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
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
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit hardware/fallback behavior for Android Keystore key generation. */
public enum class StrongBoxPolicy(public val wireValue: String) {
    DISABLED("disabled"),
    REQUIRED("required"),
    PREFERRED_WITH_KEYSTORE_FALLBACK("preferred-with-keystore-fallback"),
}

/** User-authentication behavior applied to the wrapping key. */
public enum class AndroidKeyAuthentication(public val wireValue: String) {
    NONE("none"),
    /** Strong biometric or device credential, with a bounded validity window. */
    BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL("biometric-strong-or-device-credential"),
}

/** Immutable creation policy. Changing it intentionally selects a different key alias. */
public data class AndroidKeystoreSeedStoreConfiguration(
    public val strongBoxPolicy: StrongBoxPolicy = StrongBoxPolicy.DISABLED,
    public val authentication: AndroidKeyAuthentication = AndroidKeyAuthentication.NONE,
    public val authenticationValiditySeconds: Int = 0,
    public val unlockedDeviceRequired: Boolean = false,
) {
    init {
        when (authentication) {
            AndroidKeyAuthentication.NONE -> if (authenticationValiditySeconds != 0) {
                throw SdkException.InvalidArgument(
                    "authenticationValiditySeconds",
                    "must be zero when authentication is disabled",
                )
            }
            AndroidKeyAuthentication.BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL ->
                if (authenticationValiditySeconds !in 1..86_400) {
                    throw SdkException.InvalidArgument(
                        "authenticationValiditySeconds",
                        "must be between 1 and 86400",
                    )
                }
        }
    }

    internal fun policyId(): String = listOf(
        strongBoxPolicy.wireValue,
        authentication.wireValue,
        authenticationValiditySeconds.toString(),
        unlockedDeviceRequired.toString(),
    ).joinToString(separator = ":")
}

/**
 * Application-owned authorization gate for destructive seed-store recovery.
 *
 * Implementations should require a freshly authenticated user and confirm that
 * a mnemonic/seed backup exists. Returning true authorizes irreversible local
 * deletion; it does not prove ownership to the SDK by itself.
 */
public fun interface SeedStoreRecoveryAuthorizer {
    public suspend fun authorizeDestructiveRecovery(): Boolean
}

/**
 * AES-256-GCM seed custody backed by Android Keystore.
 *
 * Ciphertext is stored below [Context.getNoBackupFilesDir]. AAD binds the
 * application package, wallet ID, logical network, schema, and immutable key
 * policy. [provision] is create-only: it never overwrites ciphertext or
 * silently deletes/replaces an existing Keystore key.
 *
 * Authentication uses a validity window because [WalletKeyProvider] is not a
 * BiometricPrompt `CryptoObject` API. Authenticate the user immediately before
 * [UTEXOWallet.unlock] when authentication is enabled.
 */
public class AndroidKeystoreSeedStore private constructor(
    private val repository: SeedEnvelopeRepository,
) : WalletKeyProvider {
    public constructor(
        context: Context,
        walletId: WalletId,
        network: Network,
        configuration: AndroidKeystoreSeedStoreConfiguration =
            AndroidKeystoreSeedStoreConfiguration(),
    ) : this(createRepository(context, walletId, network, configuration))

    /** Encrypts and atomically stores a seed; refuses to replace an existing envelope. */
    public suspend fun provision(seed: SecretSeed): Unit = redactedKeyAccess {
        repository.provision(seed)
    }

    /** Derives locally and provisions without returning raw seed bytes to application code. */
    public suspend fun provisionFromMnemonic(
        mnemonic: SecretMnemonic,
        passphrase: CharArray = charArrayOf(),
    ): Unit {
        deriveSeedFromMnemonic(mnemonic, passphrase).use { seed -> provision(seed) }
    }

    /**
     * Irreversibly deletes the encrypted seed envelope and its wrapping key.
     *
     * This is the explicit recovery path after permanent Keystore invalidation
     * or envelope corruption. It never runs without a positive application
     * authorization callback and never provisions replacement material. Call
     * [provision] separately from a verified backup after this succeeds.
     */
    public suspend fun resetForRecovery(authorizer: SeedStoreRecoveryAuthorizer): Unit {
        val authorized = try {
            authorizer.authorizeDestructiveRecovery()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw SdkException.KeyAccessFailed(retryable = false)
        }
        if (!authorized) throw SdkException.KeyAccessFailed(retryable = false)
        redactedKeyAccess { repository.resetForRecovery() }
    }

    override suspend fun loadSeed(params: WalletUnlockParams): SecretSeed {
        params.usePassword { password ->
            if (password != null) {
                throw SdkException.InvalidArgument(
                    "password",
                    "Android Keystore custody does not accept SDK passwords",
                )
            }
        }
        return redactedKeyAccess { repository.load() }
    }

    private suspend fun <T> redactedKeyAccess(block: suspend () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (sdk: SdkException) {
        throw sdk
    } catch (_: UserNotAuthenticatedException) {
        throw SdkException.KeyAccessFailed(retryable = true)
    } catch (_: KeyPermanentlyInvalidatedException) {
        throw SdkException.KeyAccessFailed(retryable = false)
    } catch (_: AEADBadTagException) {
        throw SdkException.KeyAccessFailed(retryable = false)
    } catch (_: MissingSeedEnvelopeException) {
        throw SdkException.KeyAccessFailed(retryable = false)
    } catch (_: Exception) {
        // Do not attach provider/file exceptions: their messages may contain a
        // private alias or application path.
        throw SdkException.KeyAccessFailed(retryable = true)
    }

    private companion object {
        private fun createRepository(
            context: Context,
            walletId: WalletId,
            network: Network,
            configuration: AndroidKeystoreSeedStoreConfiguration,
        ): SeedEnvelopeRepository {
            val application = context.applicationContext ?: context
            val applicationId = requireBoundedText(
                "applicationId",
                application.packageName,
                255,
            )
            val identity = SeedStoreIdentity(
                applicationId = applicationId,
                walletId = walletId.value,
                network = network.wireValue,
                keyPolicy = configuration.policyId(),
            )
            val directory = File(application.noBackupFilesDir.canonicalFile, "utexo-rgb-sdk")
            val storage = AtomicSeedEnvelopeFile(
                directory = directory,
                fileName = identity.fileName(),
                secureFile = ::secureOwnerOnly,
                syncDirectory = ::syncDirectory,
                validateFile = ::validateRegularOwnerOnly,
                secureDirectory = ::secureDirectoryOwnerOnly,
            )
            return SeedEnvelopeRepository(
                identity = identity,
                storage = storage,
                cipher = AndroidKeystoreSeedCipher(identity.keyAlias(), configuration),
            )
        }

        private fun secureOwnerOnly(file: File): Unit {
            Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        }

        private fun validateRegularOwnerOnly(file: File): Unit {
            val status = try { Os.lstat(file.absolutePath) } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.ENOENT) throw failure
                null
            }
            if (status != null && (!OsConstants.S_ISREG(status.st_mode) ||
                    status.st_nlink != 1L || status.st_uid != Os.getuid())) throw IOException()
        }

        private fun secureDirectoryOwnerOnly(directory: File): Unit {
            val status = Os.lstat(directory.absolutePath)
            if (!OsConstants.S_ISDIR(status.st_mode) || status.st_uid != Os.getuid()) throw IOException()
            Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        }

        private fun syncDirectory(directory: File): Unit {
            val descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}

internal data class SeedStoreIdentity(
    val applicationId: String,
    val walletId: String,
    val network: String,
    val keyPolicy: String,
) {
    fun aad(): ByteArray = canonicalBytes(includeKeyPolicy = true)

    fun fileName(): String = "seed-v1-${sha256(canonicalBytes(includeKeyPolicy = false)).hex()}.bin"

    fun keyAlias(): String = "com.utexo.rgb.sdk.seed.v1.${sha256(aad()).hex()}"

    private fun canonicalBytes(includeKeyPolicy: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            writeField(data, "utexo.android.seed-envelope.v1")
            data.writeInt(SeedEnvelopeCodec.SCHEMA_VERSION)
            writeField(data, applicationId)
            writeField(data, walletId)
            writeField(data, network)
            if (includeKeyPolicy) writeField(data, keyPolicy)
        }
        return output.toByteArray()
    }

    private fun writeField(output: DataOutputStream, value: String): Unit {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}

internal data class EncryptedSeed(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object SeedEnvelopeCodec {
    const val SCHEMA_VERSION: Int = 1
    private const val IV_BYTES: Int = 12
    private const val CIPHERTEXT_BYTES: Int = SecretSeed.BIP39_SEED_LENGTH + 16
    private val magic: ByteArray = "UTXSEED1".toByteArray(StandardCharsets.US_ASCII)

    fun encode(encrypted: EncryptedSeed): ByteArray {
        if (encrypted.iv.size != IV_BYTES || encrypted.ciphertext.size != CIPHERTEXT_BYTES) {
            throw IOException("invalid seed envelope dimensions")
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(magic)
            data.writeInt(SCHEMA_VERSION)
            data.writeInt(encrypted.iv.size)
            data.writeInt(encrypted.ciphertext.size)
            data.write(encrypted.iv)
            data.write(encrypted.ciphertext)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): EncryptedSeed {
        if (bytes.size > AtomicSeedEnvelopeFile.MAX_ENVELOPE_BYTES) {
            throw IOException("seed envelope is too large")
        }
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val actualMagic = ByteArray(magic.size)
            data.readFully(actualMagic)
            if (!actualMagic.contentEquals(magic)) throw IOException("invalid seed envelope")
            if (data.readInt() != SCHEMA_VERSION) throw IOException("unsupported seed envelope")
            if (data.readInt() != IV_BYTES) throw IOException("invalid seed envelope IV")
            if (data.readInt() != CIPHERTEXT_BYTES) {
                throw IOException("invalid seed envelope ciphertext")
            }
            val iv = ByteArray(IV_BYTES)
            val ciphertext = ByteArray(CIPHERTEXT_BYTES)
            data.readFully(iv)
            data.readFully(ciphertext)
            if (data.available() != 0) throw IOException("trailing seed envelope data")
            return EncryptedSeed(iv, ciphertext)
        }
    }
}

internal interface SeedEnvelopeCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSeed

    fun decrypt(encrypted: EncryptedSeed, aad: ByteArray): ByteArray

    fun deleteKey(): Unit
}

internal class SeedEnvelopeRepository(
    private val identity: SeedStoreIdentity,
    private val storage: AtomicSeedEnvelopeFile,
    private val cipher: SeedEnvelopeCipher,
) {
    suspend fun provision(seed: SecretSeed): Unit = storage.exclusive {
        if (storage.exists()) {
            throw SdkException.InvalidState("provision seed", "already provisioned")
        }
        val plaintext = seed.copyForNative()
        val aad = identity.aad()
        try {
            val encrypted = cipher.encrypt(plaintext, aad)
            storage.writeNew(SeedEnvelopeCodec.encode(encrypted))
        } finally {
            Arrays.fill(plaintext, 0.toByte())
            Arrays.fill(aad, 0.toByte())
        }
    }

    suspend fun load(): SecretSeed = storage.exclusive {
        if (!storage.exists()) throw MissingSeedEnvelopeException()
        val envelopeBytes = storage.read()
        val aad = identity.aad()
        var plaintext: ByteArray? = null
        try {
            plaintext = cipher.decrypt(SeedEnvelopeCodec.decode(envelopeBytes), aad)
            if (plaintext.size != SecretSeed.BIP39_SEED_LENGTH) {
                throw IOException("invalid decrypted seed length")
            }
            SecretSeed.fromBytes(plaintext)
        } finally {
            plaintext?.let { Arrays.fill(it, 0.toByte()) }
            Arrays.fill(envelopeBytes, 0.toByte())
            Arrays.fill(aad, 0.toByte())
        }
    }

    suspend fun resetForRecovery(): Unit = storage.exclusive {
        // Remove ciphertext first. If key deletion then fails, retrying this
        // method is safe and provisioning remains blocked until it succeeds.
        storage.deleteIfPresent()
        cipher.deleteKey()
    }
}

internal class AtomicSeedEnvelopeFile(
    private val directory: File,
    private val fileName: String,
    private val secureFile: (File) -> Unit = {},
    private val syncDirectory: (File) -> Unit = {},
    private val validateFile: (File) -> Unit = {},
    private val secureDirectory: (File) -> Unit = {},
) {
    private val target: File = File(directory, fileName)
    private val lockFile: File = File(directory, "$fileName.lock")
    private val processMutex: Mutex = locks.computeIfAbsent(target.absolutePath) { Mutex() }

    suspend fun <T> exclusive(block: () -> T): T = withOwnedDispatcherResult(Dispatchers.IO) { ownership ->
        processMutex.withLock {
            ensureDirectory()
            validateFile(lockFile)
            RandomAccessFile(lockFile, "rw").use { randomAccess ->
                validateFile(lockFile)
                secureFile(lockFile)
                randomAccess.channel.lock().use { ownership.retain(block()) }
            }
        }
    }

    fun exists(): Boolean {
        validateFile(target)
        return target.isFile
    }

    fun read(): ByteArray {
        validateFile(target)
        val length = target.length()
        if (length <= 0L || length > MAX_ENVELOPE_BYTES) {
            throw IOException("invalid seed envelope size")
        }
        return FileInputStream(target).use { input ->
            val bytes = ByteArray(length.toInt())
            try {
                DataInputStream(input).readFully(bytes)
                if (input.read() != -1) throw IOException("seed envelope changed")
                bytes
            } catch (failure: Exception) {
                bytes.fill(0)
                throw failure
            }
        }
    }

    fun writeNew(bytes: ByteArray): Unit {
        if (bytes.isEmpty() || bytes.size > MAX_ENVELOPE_BYTES) {
            throw IOException("invalid seed envelope size")
        }
        validateFile(target)
        if (target.exists()) {
            throw SdkException.InvalidState("provision seed", "already provisioned")
        }
        val temporary = File(directory, "$fileName.tmp")
        validateFile(temporary)
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("stale temporary envelope cannot be removed")
        }
        try {
            if (!temporary.createNewFile()) throw IOException("temporary envelope already exists")
            validateFile(temporary)
            secureFile(temporary)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            validateFile(target)
            if (target.exists() || !temporary.renameTo(target)) {
                throw IOException("seed envelope atomic rename failed")
            }
            secureFile(target)
            syncDirectory(directory)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun deleteIfPresent(): Unit {
        validateFile(target)
        if (target.exists() && !target.delete()) {
            throw IOException("seed envelope could not be deleted")
        }
        syncDirectory(directory)
    }

    private fun ensureDirectory(): Unit {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("seed directory cannot be created")
        }
        if (!directory.isDirectory) throw IOException("seed directory is invalid")
        secureDirectory(directory)
    }

    internal companion object {
        const val MAX_ENVELOPE_BYTES: Int = 4_096
        private val locks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()
    }
}

internal class AndroidKeystoreSeedCipher(
    private val alias: String,
    private val configuration: AndroidKeystoreSeedStoreConfiguration,
) : SeedEnvelopeCipher {
    fun keyExists(): Boolean = keyStore.containsAlias(alias)

    /** Used for durable state: an existing envelope must never acquire a replacement key. */
    fun encryptWithExistingKey(plaintext: ByteArray, aad: ByteArray): EncryptedSeed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadExistingKey())
        cipher.updateAAD(aad)
        return EncryptedSeed(cipher.iv.copyOf(), cipher.doFinal(plaintext))
    }

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSeed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(aad)
        return EncryptedSeed(
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override fun decrypt(encrypted: EncryptedSeed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadExistingKey(),
            GCMParameterSpec(GCM_TAG_BITS, encrypted.iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.ciphertext)
    }

    override fun deleteKey(): Unit {
        val store = keyStore
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun loadExistingKey(): SecretKey {
        val loaded = keyStore.getKey(alias, null)
            ?: throw MissingSeedEnvelopeException()
        return loaded as? SecretKey ?: throw IOException("invalid Keystore entry")
    }

    private fun loadOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(alias, null)
        if (existing != null) {
            return existing as? SecretKey ?: throw IOException("invalid Keystore entry")
        }
        return when (configuration.strongBoxPolicy) {
            StrongBoxPolicy.DISABLED -> generateKey()
            StrongBoxPolicy.REQUIRED -> {
                if (Build.VERSION.SDK_INT < 28) {
                    throw SdkException.Unsupported("StrongBox requires Android API 28")
                }
                Api28.generateStrongBox(::newKeyBuilder)
                    ?: throw SdkException.KeyAccessFailed(retryable = false)
            }
            StrongBoxPolicy.PREFERRED_WITH_KEYSTORE_FALLBACK -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    Api28.generateStrongBox(::newKeyBuilder) ?: generateKey()
                } else {
                    generateKey()
                }
            }
        }
    }

    private fun generateKey(): SecretKey {
        val builder = newKeyBuilder()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun newKeyBuilder(): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        when (configuration.authentication) {
            AndroidKeyAuthentication.NONE -> Unit
            AndroidKeyAuthentication.BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL -> {
                builder.setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= 30) {
                    Api30.setAuthentication(
                        builder,
                        configuration.authenticationValiditySeconds,
                    )
                } else {
                    builder.setUserAuthenticationValidityDurationSeconds(
                        configuration.authenticationValiditySeconds,
                    )
                }
            }
        }
        if (configuration.unlockedDeviceRequired) {
            if (Build.VERSION.SDK_INT < 28) {
                throw SdkException.Unsupported("unlocked-device keys require Android API 28")
            }
            Api28.requireUnlockedDevice(builder)
        }
        return builder
    }

    private companion object {
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS: Int = 128
    }

    @RequiresApi(28)
    private object Api28 {
        fun enableStrongBox(builder: KeyGenParameterSpec.Builder): Unit {
            builder.setIsStrongBoxBacked(true)
        }

        fun requireUnlockedDevice(builder: KeyGenParameterSpec.Builder): Unit {
            builder.setUnlockedDeviceRequired(true)
        }

        fun generateStrongBox(
            builderFactory: () -> KeyGenParameterSpec.Builder,
        ): SecretKey? = try {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val builder = builderFactory()
            enableStrongBox(builder)
            generator.init(builder.build())
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            null
        }
    }

    @RequiresApi(30)
    private object Api30 {
        fun setAuthentication(
            builder: KeyGenParameterSpec.Builder,
            validitySeconds: Int,
        ): Unit {
            builder.setUserAuthenticationParameters(
                validitySeconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        }
    }
}

private class MissingSeedEnvelopeException : IOException()

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun ByteArray.hex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

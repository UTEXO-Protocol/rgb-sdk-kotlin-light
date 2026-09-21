package com.utexo.rgb.sdk

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

internal class AndroidKeystoreSeedStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `seed envelope reads remain bounded and reject truncated or oversized storage`(): Unit = runBlocking {
        val path = directory.resolve("seed.bin")
        val storage = AtomicSeedEnvelopeFile(directory.toFile(), "seed.bin")
        Files.write(path, ByteArray(AtomicSeedEnvelopeFile.MAX_ENVELOPE_BYTES) { 7 })
        storage.exclusive { assertEquals(AtomicSeedEnvelopeFile.MAX_ENVELOPE_BYTES, storage.read().size) }
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(1024L * 1024 * 1024) }
        storage.exclusive { assertFailsWith<IOException> { storage.read() } }
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(0) }
        storage.exclusive { assertFailsWith<IOException> { storage.read() } }
    }

    @Test
    fun `seed target lock and temporary path validation occurs before reads writes or recovery deletion`(): Unit = runBlocking {
        for (suffix in listOf("", ".lock", ".tmp")) {
            val caseDirectory = Files.createDirectory(directory.resolve("case-${suffix.ifEmpty { "target" }.removePrefix(".")}"))
            val external = Files.write(caseDirectory.resolve("untouched.bin"), byteArrayOf(1, 2, 3))
            val link = Files.createSymbolicLink(caseDirectory.resolve("seed.bin$suffix"), external)
            val storage = AtomicSeedEnvelopeFile(caseDirectory.toFile(), "seed.bin", validateFile = { file ->
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath()))) {
                    throw IOException("unsafe fixture")
                }
            })
            assertFailsWith<IOException> { storage.exclusive { storage.writeNew(byteArrayOf(5)) } }
            if (suffix.isEmpty()) {
                assertFailsWith<IOException> { storage.exclusive { storage.read() } }
                assertFailsWith<IOException> { storage.exclusive { storage.deleteIfPresent() } }
            }
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(external))
            assertEquals(true, Files.isSymbolicLink(link))
        }
    }

    @Test
    fun `seed directory validation precedes lock creation and no cipher operation is performed`(): Unit = runBlocking {
        val storage = AtomicSeedEnvelopeFile(directory.toFile(), "seed.bin", secureDirectory = { throw IOException() })
        var entered = false
        assertFailsWith<IOException> { storage.exclusive { entered = true } }
        assertEquals(false, entered)
        assertEquals(false, Files.exists(directory.resolve("seed.bin.lock")))
    }

    @Test
    fun `AAD binds package wallet network schema and key policy`(): Unit {
        val baseline = identity().aad()
        assertNotEquals(baseline.toList(), identity(applicationId = "org.example.other").aad().toList())
        assertNotEquals(baseline.toList(), identity(walletId = "wallet-2").aad().toList())
        assertNotEquals(baseline.toList(), identity(network = "mainnet").aad().toList())
        assertNotEquals(baseline.toList(), identity(keyPolicy = "required:none:0:false").aad().toList())
    }

    @Test
    fun `repository encrypts atomically restores and refuses replacement`(): Unit = runBlocking {
        val identity = identity()
        val storage = AtomicSeedEnvelopeFile(directory.toFile(), identity.fileName())
        val repository = SeedEnvelopeRepository(identity, storage, JvmAesGcmCipher())
        val expected = ByteArray(64) { index -> index.toByte() }
        val seed = SecretSeed.fromBytes(expected)
        try {
            repository.provision(seed)
            assertFailsWith<SdkException.InvalidState> { repository.provision(seed) }

            repository.load().use { restored ->
                val actual = restored.copyForNative()
                try {
                    assertContentEquals(expected, actual)
                } finally {
                    actual.fill(0.toByte())
                }
            }
        } finally {
            seed.close()
            expected.fill(0.toByte())
        }
    }

    @Test
    fun `ciphertext cannot be opened under another bound identity`(): Unit = runBlocking {
        val originalIdentity = identity()
        val storage = AtomicSeedEnvelopeFile(directory.toFile(), originalIdentity.fileName())
        val cipher = JvmAesGcmCipher()
        val seed = SecretSeed.fromBytes(ByteArray(64) { 9 })
        try {
            SeedEnvelopeRepository(originalIdentity, storage, cipher).provision(seed)
            val wrongIdentity = identity(network = "mainnet")
            assertFailsWith<Exception> {
                SeedEnvelopeRepository(wrongIdentity, storage, cipher).load()
            }
        } finally {
            seed.close()
        }
    }

    @Test
    fun `envelope codec rejects trailing data`(): Unit {
        val encoded = SeedEnvelopeCodec.encode(
            EncryptedSeed(ByteArray(12) { 1 }, ByteArray(80) { 2 }),
        )
        assertFailsWith<Exception> { SeedEnvelopeCodec.decode(encoded + byteArrayOf(3)) }
    }

    @Test
    fun `authentication configuration cannot silently broaden`(): Unit {
        assertFailsWith<SdkException.InvalidArgument> {
            AndroidKeystoreSeedStoreConfiguration(authenticationValiditySeconds = 30)
        }
        assertFailsWith<SdkException.InvalidArgument> {
            AndroidKeystoreSeedStoreConfiguration(
                authentication = AndroidKeyAuthentication.BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL,
                authenticationValiditySeconds = 0,
            )
        }
    }

    @Test
    fun `explicit recovery reset removes envelope and permits deliberate reprovision`(): Unit =
        runBlocking {
            val identity = identity()
            val storage = AtomicSeedEnvelopeFile(directory.toFile(), identity.fileName())
            val cipher = JvmAesGcmCipher()
            val repository = SeedEnvelopeRepository(identity, storage, cipher)
            val first = SecretSeed.fromBytes(ByteArray(64) { 1 })
            val replacement = SecretSeed.fromBytes(ByteArray(64) { 2 })
            try {
                repository.provision(first)
                repository.resetForRecovery()
                assertEquals(1, cipher.deletedKeys)
                assertFailsWith<Exception> { repository.load() }

                repository.provision(replacement)
                repository.load().use { restored ->
                    val bytes = restored.copyForNative()
                    try {
                        assertContentEquals(ByteArray(64) { 2 }, bytes)
                    } finally {
                        bytes.fill(0.toByte())
                    }
                }
            } finally {
                first.close()
                replacement.close()
            }
        }

    private fun identity(
        applicationId: String = "org.example.wallet",
        walletId: String = "wallet-1",
        network: String = "signet",
        keyPolicy: String = "disabled:none:0:false",
    ): SeedStoreIdentity = SeedStoreIdentity(applicationId, walletId, network, keyPolicy)
}

/** Real JVM AES-GCM used only to test repository/storage semantics. */
private class JvmAesGcmCipher : SeedEnvelopeCipher {
    private var key: SecretKey = newKey()
    var deletedKeys: Int = 0

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSeed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return EncryptedSeed(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: EncryptedSeed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.ciphertext)
    }

    override fun deleteKey(): Unit {
        deletedKeys += 1
        key = newKey()
    }

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

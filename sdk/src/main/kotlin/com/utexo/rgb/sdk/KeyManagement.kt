@file:JvmName("RGBSDK")

package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.toFfi
import com.utexo.rgb.sdk.internal.NativeRuntime
import com.utexo.rgb.sdk.internal.toSdkException
import com.utexo.rgb.sdk.internal.withOwnedDispatcherResult
import com.utexo.rgb.sdk.internal.ffi.GeneratedKeys as FfiGeneratedKeys
import com.utexo.rgb.sdk.internal.ffi.SignerException as FfiSignerException
import com.utexo.rgb.sdk.internal.ffi.deriveKeysFromMnemonic as ffiDeriveKeysFromMnemonic
import com.utexo.rgb.sdk.internal.ffi.deriveKeysFromSeed as ffiDeriveKeysFromSeed
import com.utexo.rgb.sdk.internal.ffi.deriveKeysFromXpriv as ffiDeriveKeysFromXpriv
import com.utexo.rgb.sdk.internal.ffi.deriveSeedFromMnemonic as ffiDeriveSeedFromMnemonic
import com.utexo.rgb.sdk.internal.ffi.generateKeys as ffiGenerateKeys
import com.utexo.rgb.sdk.internal.ffi.getXpubFromXpriv as ffiGetXpubFromXpriv
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

/**
 * Redacting, closeable BIP39 mnemonic container.
 *
 * A JVM cannot guarantee that every temporary copy is erased. Keep instances
 * short-lived, never log them, and close them after importing into encrypted
 * application storage.
 */
public class SecretMnemonic private constructor(words: CharArray) : AutoCloseable {
    private val lock: Any = Any()
    private var characters: CharArray? = null

    init {
        if (words.size > MAX_CHARACTERS) {
            throw SdkException.InvalidArgument("mnemonic", "is too long")
        }
        if (words.isEmpty() || words.all(Char::isWhitespace)) {
            throw SdkException.InvalidArgument("mnemonic", "must not be blank")
        }
        characters = words.copyOf()
    }

    internal fun copyUtf8(): ByteArray = synchronized(lock) {
        val current = characters
            ?: throw SdkException.InvalidState("read mnemonic", "closed")
        current.copyUtf8("mnemonic")
    }

    /** Supplies a temporary copy and clears that copy when [block] returns. */
    public fun <T> useWords(block: (CharArray) -> T): T {
        val copy = synchronized(lock) {
            characters?.copyOf()
                ?: throw SdkException.InvalidState("read mnemonic", "closed")
        }
        return try {
            block(copy)
        } finally {
            Arrays.fill(copy, '\u0000')
        }
    }

    override fun close(): Unit = synchronized(lock) {
        characters?.let { Arrays.fill(it, '\u0000') }
        characters = null
    }

    override fun toString(): String = "SecretMnemonic([REDACTED])"

    public companion object {
        private const val MAX_CHARACTERS: Int = 512

        /** The caller should clear [words] after this method returns. */
        public fun fromWords(words: CharArray): SecretMnemonic = SecretMnemonic(words)

        /**
         * Compatibility entry point for callers that already hold an immutable
         * string. Prefer [fromWords], because a String cannot be zeroized.
         */
        @Deprecated(
            message = "Immutable Strings cannot be zeroized; use fromWords(CharArray)",
            level = DeprecationLevel.WARNING,
        )
        public fun dangerouslyFromString(words: String): SecretMnemonic {
            val copy = words.toCharArray()
            return try {
                SecretMnemonic(copy)
            } finally {
                Arrays.fill(copy, '\u0000')
            }
        }
    }
}

/** Redacting container for a root BIP32 extended private key. */
public class SecretExtendedPrivateKey private constructor(value: CharArray) : AutoCloseable {
    private val lock: Any = Any()
    private var characters: CharArray? = null

    init {
        if (value.isEmpty() || value.size > MAX_CHARACTERS) {
            throw SdkException.InvalidArgument("xpriv", "must be a bounded non-empty key")
        }
        characters = value.copyOf()
    }

    internal fun copyAsString(): String = synchronized(lock) {
        val current = characters
            ?: throw SdkException.InvalidState("read extended private key", "closed")
        String(current)
    }

    /**
     * Supplies a temporary character copy. Root private-key handling remains a
     * discouraged compatibility path; the copy is cleared after [block].
     */
    @Deprecated(
        message = "Root extended private-key export is dangerous; store an encrypted seed instead",
        level = DeprecationLevel.WARNING,
    )
    public fun <T> useCharacters(block: (CharArray) -> T): T {
        val copy = synchronized(lock) {
            characters?.copyOf()
                ?: throw SdkException.InvalidState("read extended private key", "closed")
        }
        return try {
            block(copy)
        } finally {
            Arrays.fill(copy, '\u0000')
        }
    }

    /** Raw compatibility export. The returned immutable String cannot be erased. */
    @Deprecated(
        message = "Immutable root private-key Strings cannot be zeroized; store an encrypted seed instead",
        level = DeprecationLevel.WARNING,
    )
    public fun dangerouslyExportToString(): String = copyAsString()

    override fun close(): Unit = synchronized(lock) {
        characters?.let { Arrays.fill(it, '\u0000') }
        characters = null
    }

    override fun toString(): String = "SecretExtendedPrivateKey([REDACTED])"

    public companion object {
        private const val MAX_CHARACTERS: Int = 256

        /** The caller should clear [value] after this method returns. */
        public fun fromCharacters(value: CharArray): SecretExtendedPrivateKey =
            SecretExtendedPrivateKey(value)

        /** Compatibility import for an existing String that cannot be zeroized. */
        @Deprecated(
            message = "Immutable Strings cannot be zeroized; use fromCharacters(CharArray)",
            level = DeprecationLevel.WARNING,
        )
        public fun dangerouslyFromString(value: String): SecretExtendedPrivateKey {
            val copy = value.toCharArray()
            return try {
                SecretExtendedPrivateKey(copy)
            } finally {
                Arrays.fill(copy, '\u0000')
            }
        }
    }
}

/** Public, watch-only account keys used by the backend and application. */
public data class AccountXpubs(
    public val vanilla: String,
    public val colored: String,
)

/**
 * RN-compatible key-factory result with secret-bearing fields wrapped and
 * redacted. Call [close] as soon as mnemonic/private-key persistence finishes.
 */
public class GeneratedKeys internal constructor(
    public val mnemonic: SecretMnemonic?,
    public val xpub: String,
    public val accountXpubVanilla: String,
    public val accountXpubColored: String,
    public val masterFingerprint: String,
    public val xpriv: SecretExtendedPrivateKey,
) : AutoCloseable {
    public val accountXpubs: AccountXpubs
        get() = AccountXpubs(accountXpubVanilla, accountXpubColored)

    override fun close(): Unit {
        mnemonic?.close()
        xpriv.close()
    }

    override fun toString(): String =
        "GeneratedKeys(mnemonic=[REDACTED], xpub=$xpub, " +
            "accountXpubVanilla=$accountXpubVanilla, " +
            "accountXpubColored=$accountXpubColored, " +
            "masterFingerprint=$masterFingerprint, xpriv=[REDACTED])"
}

/** Generates a 12-word English BIP39 wallet using operating-system entropy. */
public suspend fun generateKeys(network: Network): GeneratedKeys = nativeKeyCall {
    ffiGenerateKeys(network.toFfi()).toPublic(expectMnemonic = true)
}

/** RN compatibility alias for [generateKeys]. */
public suspend fun createWallet(network: Network): GeneratedKeys = generateKeys(network)

/** Restores RN-compatible account keys from an English BIP39 mnemonic. */
public suspend fun deriveKeysFromMnemonic(
    network: Network,
    mnemonic: SecretMnemonic,
    passphrase: CharArray = charArrayOf(),
): GeneratedKeys {
    val mnemonicBytes = mnemonic.copyUtf8()
    var passphraseBytes: ByteArray? = null
    return try {
        val encodedPassphrase = passphrase.copyUtf8("passphrase").also { passphraseBytes = it }
        nativeKeyCall {
            ffiDeriveKeysFromMnemonic(
                network.toFfi(),
                mnemonicBytes,
                encodedPassphrase,
            ).toPublic(expectMnemonic = true)
        }
    } finally {
        Arrays.fill(mnemonicBytes, 0.toByte())
        passphraseBytes?.fill(0)
    }
}

/** Restores account keys from an exact 64-byte BIP39 seed. */
public suspend fun deriveKeysFromSeed(
    network: Network,
    seed: SecretSeed,
): GeneratedKeys {
    val seedBytes = seed.copyForNative()
    return try {
        nativeKeyCall {
            ffiDeriveKeysFromSeed(network.toFfi(), seedBytes).toPublic(expectMnemonic = false)
        }
    } finally {
        Arrays.fill(seedBytes, 0.toByte())
    }
}

/** Derives the exact 64-byte BIP39 seed into a redacting, closeable container. */
public suspend fun deriveSeedFromMnemonic(
    mnemonic: SecretMnemonic,
    passphrase: CharArray = charArrayOf(),
): SecretSeed {
    val mnemonicBytes = mnemonic.copyUtf8()
    var passphraseBytes: ByteArray? = null
    return try {
        val encodedPassphrase = passphrase.copyUtf8("passphrase").also { passphraseBytes = it }
        nativeKeyCall {
            val derived = ffiDeriveSeedFromMnemonic(mnemonicBytes, encodedPassphrase)
            try {
                SecretSeed.fromBytes(derived)
            } finally {
                Arrays.fill(derived, 0.toByte())
            }
        }
    } finally {
        Arrays.fill(mnemonicBytes, 0.toByte())
        passphraseBytes?.fill(0)
    }
}

/**
 * RN-shaped restore helper. Exactly one of [mnemonic] and [seed] must be
 * supplied; ambiguous or empty selection fails before native code is called.
 */
public suspend fun deriveKeysFromMnemonicOrSeed(
    network: Network,
    mnemonic: SecretMnemonic? = null,
    seed: SecretSeed? = null,
    passphrase: CharArray = charArrayOf(),
): GeneratedKeys = when {
    mnemonic != null && seed == null -> deriveKeysFromMnemonic(network, mnemonic, passphrase)
    mnemonic == null && seed != null -> {
        if (passphrase.isNotEmpty()) {
            throw SdkException.InvalidArgument(
                "passphrase",
                "can only be supplied with a mnemonic",
            )
        }
        deriveKeysFromSeed(network, seed)
    }
    else -> throw SdkException.InvalidArgument(
        "mnemonicOrSeed",
        "exactly one source must be supplied",
    )
}

/** RN compatibility alias for [deriveKeysFromMnemonicOrSeed]. */
public suspend fun restoreKeys(
    network: Network,
    mnemonic: SecretMnemonic? = null,
    seed: SecretSeed? = null,
    passphrase: CharArray = charArrayOf(),
): GeneratedKeys = deriveKeysFromMnemonicOrSeed(network, mnemonic, seed, passphrase)

/** Derives only the two watch-only BIP86 account xpubs. */
public suspend fun accountXpubsFromMnemonic(
    network: Network,
    mnemonic: SecretMnemonic,
    passphrase: CharArray = charArrayOf(),
): AccountXpubs {
    val keys = deriveKeysFromMnemonic(network, mnemonic, passphrase)
    return try {
        keys.accountXpubs
    } finally {
        keys.close()
    }
}

/**
 * Dangerous RN compatibility export. Prefer encrypted seed storage and
 * [deriveKeysFromMnemonic]; root xpriv material is easy to exfiltrate.
 */
@Deprecated(
    message = "Root extended private-key export is dangerous; store an encrypted seed instead",
    level = DeprecationLevel.WARNING,
)
public suspend fun getXprivFromMnemonic(
    network: Network,
    mnemonic: SecretMnemonic,
    passphrase: CharArray = charArrayOf(),
): SecretExtendedPrivateKey {
    val mnemonicBytes = mnemonic.copyUtf8()
    var passphraseBytes: ByteArray? = null
    return try {
        val encodedPassphrase = passphrase.copyUtf8("passphrase").also { passphraseBytes = it }
        nativeKeyCall {
            val generated = ffiDeriveKeysFromMnemonic(
                network.toFfi(),
                mnemonicBytes,
                encodedPassphrase,
            )
            generated.xpriv.toCharArray().toSecretExtendedPrivateKey()
        }
    } finally {
        Arrays.fill(mnemonicBytes, 0.toByte())
        passphraseBytes?.fill(0)
    }
}

/** Derives the corresponding watch-only extended public key without children. */
public suspend fun getXpubFromXpriv(xpriv: SecretExtendedPrivateKey): String = nativeKeyCall {
    ffiGetXpubFromXpriv(xpriv.copyAsString())
}

/**
 * Dangerous RN compatibility import from a root extended private key. Prefer
 * restoring from encrypted mnemonic/seed material.
 */
@Deprecated(
    message = "Root extended private-key import is dangerous; restore from an encrypted seed instead",
    level = DeprecationLevel.WARNING,
)
public suspend fun deriveKeysFromXpriv(
    network: Network,
    xpriv: SecretExtendedPrivateKey,
): GeneratedKeys = nativeKeyCall {
    ffiDeriveKeysFromXpriv(network.toFfi(), xpriv.copyAsString())
        .toPublic(expectMnemonic = false)
}

private suspend fun <T> nativeKeyCall(block: () -> T): T = withOwnedDispatcherResult(Dispatchers.Default) {
    try {
        NativeRuntime.ensureInitialized()
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (sdk: SdkException) {
        throw sdk
    } catch (linkage: LinkageError) {
        throw SdkException.Unsupported("native utexo-local-signer library")
    } catch (exception: FfiSignerException) {
        throw exception.toSdkException()
    } catch (_: RuntimeException) {
        throw SdkException.InternalInvariantViolation("native key derivation failed")
    }
}

private fun FfiGeneratedKeys.toPublic(expectMnemonic: Boolean): GeneratedKeys {
    validateNativePublicKeyField("xpub", xpub)
    validateNativePublicKeyField("accountXpubVanilla", accountXpubVanilla)
    validateNativePublicKeyField("accountXpubColored", accountXpubColored)
    if (masterFingerprint.length != 8 || !masterFingerprint.all(Char::isHexDigit)) {
        throw SdkException.InternalInvariantViolation("native signer returned an invalid fingerprint")
    }
    if (xpriv.isBlank() || xpriv.length > 256) {
        throw SdkException.InternalInvariantViolation("native signer returned an invalid xpriv")
    }
    if (expectMnemonic && mnemonic.isBlank()) {
        throw SdkException.InternalInvariantViolation("native signer omitted the mnemonic")
    }
    if (!expectMnemonic && mnemonic.isNotEmpty()) {
        throw SdkException.InternalInvariantViolation("native signer unexpectedly returned a mnemonic")
    }
    return GeneratedKeys(
        mnemonic = mnemonic.takeIf(String::isNotEmpty)?.toCharArray()?.toSecretMnemonic(),
        xpub = xpub,
        accountXpubVanilla = accountXpubVanilla,
        accountXpubColored = accountXpubColored,
        masterFingerprint = masterFingerprint,
        xpriv = xpriv.toCharArray().toSecretExtendedPrivateKey(),
    )
}

private fun validateNativePublicKeyField(field: String, value: String): Unit {
    if (value.isBlank() || value.length > 256) {
        throw SdkException.InternalInvariantViolation("native signer returned an invalid $field")
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun CharArray.copyUtf8(field: String): ByteArray {
    val encoded = try {
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(this))
    } catch (exception: CharacterCodingException) {
        throw SdkException.InvalidArgument(field, "contains invalid Unicode")
    }
    return try {
        ByteArray(encoded.remaining()).also(encoded::get)
    } finally {
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), 0.toByte())
        }
    }
}

private fun CharArray.toSecretMnemonic(): SecretMnemonic = try {
    SecretMnemonic.fromWords(this)
} finally {
    Arrays.fill(this, '\u0000')
}

private fun CharArray.toSecretExtendedPrivateKey(): SecretExtendedPrivateKey = try {
    SecretExtendedPrivateKey.fromCharacters(this)
} finally {
    Arrays.fill(this, '\u0000')
}

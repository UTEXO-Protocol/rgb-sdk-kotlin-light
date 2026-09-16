package com.utexo.rgb.sdk

import java.util.Arrays

/**
 * Short-lived seed container with defensive copies and redacted string output.
 * The provider should decrypt a BIP39 seed only when [WalletKeyProvider.loadSeed]
 * is called. The SDK closes this object immediately after opening its signer.
 */
public class SecretSeed private constructor(seed: ByteArray) : AutoCloseable {
    private val lock: Any = Any()
    private var bytes: ByteArray? = seed.copyOf()

    init {
        if (seed.size != BIP39_SEED_LENGTH) {
            throw SdkException.InvalidArgument(
                "seed",
                "must be a 64-byte BIP39 seed",
            )
        }
    }

    internal fun copyForNative(): ByteArray = synchronized(lock) {
        bytes?.copyOf() ?: throw SdkException.InvalidState("read seed", "closed")
    }

    override fun close(): Unit = synchronized(lock) {
        bytes?.let { Arrays.fill(it, 0.toByte()) }
        bytes = null
    }

    override fun toString(): String = "SecretSeed([REDACTED])"

    public companion object {
        public const val BIP39_SEED_LENGTH: Int = 64

        public fun fromBytes(seed: ByteArray): SecretSeed = SecretSeed(seed)
    }
}

/**
 * Unlock data is locally consumed and is never forwarded to [WalletBackend].
 * Use [usePassword] to avoid retaining another password copy.
 */
public class WalletUnlockParams private constructor(password: CharArray?) : AutoCloseable {
    private val lock: Any = Any()
    private var password: CharArray? = password?.copyOf()
    private var closed: Boolean = false

    public fun <T> usePassword(block: (CharArray?) -> T): T {
        val copy = synchronized(lock) {
            if (closed) throw SdkException.InvalidState("read unlock parameters", "closed")
            password?.copyOf()
        }
        return try {
            block(copy)
        } finally {
            copy?.let { Arrays.fill(it, '\u0000') }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        password?.let { Arrays.fill(it, '\u0000') }
        password = null
        closed = true
    }

    override fun toString(): String = "WalletUnlockParams(password=[REDACTED])"

    public companion object {
        public fun none(): WalletUnlockParams = WalletUnlockParams(null)

        public fun password(password: CharArray): WalletUnlockParams {
            if (password.isEmpty()) {
                throw SdkException.InvalidArgument("password", "must not be empty")
            }
            return WalletUnlockParams(password)
        }
    }
}

/** Supplies locally protected key material; implementations should use Android Keystore wrapping. */
@WalletInfrastructureApi
public fun interface WalletKeyProvider {
    public suspend fun loadSeed(params: WalletUnlockParams): SecretSeed
}

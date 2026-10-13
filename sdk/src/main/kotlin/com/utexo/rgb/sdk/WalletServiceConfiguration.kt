package com.utexo.rgb.sdk

import android.content.Context
import java.util.Arrays
import kotlinx.coroutines.CancellationException

/** A request-scoped API credential. String output is always redacted. */
public class WalletAPIKey private constructor(value: CharArray) : AutoCloseable {
    private var value: CharArray? = null

    init {
        if (value.isEmpty() || value.size > 512 || value.any { it.code !in 0x21..0x7e }) {
            throw SdkException.InvalidArgument("apiKey", "must contain 1 to 512 visible ASCII characters")
        }
        this.value = value.copyOf()
    }

    @Synchronized
    internal fun authorizationValue(): String = value?.concatToString()
        ?: throw SdkException.InvalidState("read API key", "closed")

    @Synchronized
    override fun close(): Unit {
        value?.let { Arrays.fill(it, '\u0000') }
        value = null
    }

    override fun toString(): String = "WalletAPIKey([REDACTED])"

    public companion object {
        public fun fromCharacters(value: CharArray): WalletAPIKey = WalletAPIKey(value)
    }
}

/**
 * Loads a fresh credential for each HTTP request, so rotation applies without recreating the wallet.
 * The SDK closes each returned object. Provider failures are redacted and requests are not retried.
 */
public fun interface WalletAPIKeyProvider {
    public suspend fun loadAPIKey(): WalletAPIKey
}

/** Chain tip obtained independently of a gateway's proposed transaction. */
public data class WalletChainContext(
    public val network: Network,
    public val tipHeight: Int,
    public val observedAt: UnixSeconds,
) {
    init {
        if (tipHeight !in 0..499_999_999) {
            throw SdkException.InvalidArgument("tipHeight", "must be a Bitcoin block height below 500000000")
        }
    }
}

/** Supply a trusted node/indexer tip, never a height copied from the prepared PSBT. */
public fun interface WalletChainContextProvider {
    public suspend fun loadChainContext(network: Network): WalletChainContext
}

/** Public service configuration shared by the lightweight and future full wallet entry point. */
public class WalletServiceConfiguration(
    public val baseUrl: String,
    public val apiKeyProvider: WalletAPIKeyProvider,
    public val connectTimeoutMillis: Long = 10_000L,
    public val readTimeoutMillis: Long = 30_000L,
    public val callTimeoutMillis: Long = 45_000L,
    public val allowInsecureLocalDevelopment: Boolean = false,
    /** Required for new spending; read-only use and submitted-operation recovery do not need it. */
    public val chainContextProvider: WalletChainContextProvider? = null,
) {
    init {
        // Reuse the transport's exact validation, without loading any credential or making requests.
        gatewayConfiguration(Network.SIGNET)
    }

    internal fun gatewayConfiguration(network: Network): RgbLightningNodeGatewayConfiguration =
        RgbLightningNodeGatewayConfiguration(
            baseUrl = baseUrl,
            expectedNetwork = network,
            bearerToken = "provider-managed",
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            callTimeoutMillis = callTimeoutMillis,
            allowInsecureLocalDevelopment = allowInsecureLocalDevelopment,
            allowUnverifiedPsbtProfile = true,
        )

    override fun toString(): String = "WalletServiceConfiguration(baseUrl=$baseUrl, apiKeyProvider=[REDACTED])"
}

/** Android storage policy. All SDK files are internal, private, and excluded from Android backup. */
public data class WalletStorageConfiguration(
    public val seedStoreConfiguration: AndroidKeystoreSeedStoreConfiguration =
        AndroidKeystoreSeedStoreConfiguration(),
)

/**
 * Android Keystore protected API-key storage. [store] deliberately provisions or rotates a key;
 * loading never creates a key or substitutes credentials. This store uses a separate encryption key
 * from the wallet seed and operation journal. Call [loadAPIKey] only through the provider contract.
 */
public class AndroidKeystoreAPIKeyStore(
    context: Context,
    walletId: WalletId,
    network: Network,
) : WalletAPIKeyProvider {
    private val storage: ProtectedWalletFile = ProtectedWalletFile.forAndroid(
        context, walletId, network, "api-key",
    )

    public suspend fun store(apiKey: WalletAPIKey): Unit = storage.access { session ->
        val bytes = apiKey.authorizationValue().toByteArray(Charsets.US_ASCII)
        try {
            session.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun loadAPIKey(): WalletAPIKey = try {
        storage.access { session ->
            val bytes = session.read() ?: throw SdkException.KeyAccessFailed(retryable = false)
            try {
                if (bytes.isEmpty() || bytes.size > 512 || bytes.any { it.toInt() !in 0x21..0x7e }) {
                    throw SdkException.KeyAccessFailed(retryable = false)
                }
                val characters = CharArray(bytes.size) { bytes[it].toInt().toChar() }
                try {
                    WalletAPIKey.fromCharacters(characters)
                } finally {
                    characters.fill('\u0000')
                }
            } finally {
                bytes.fill(0)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        throw SdkException.KeyAccessFailed(retryable = false)
    }

    override fun toString(): String = "AndroidKeystoreAPIKeyStore([REDACTED])"
}

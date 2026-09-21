package com.utexo.rgb.sdk.releasesmoke

import android.content.Context
import com.utexo.rgb.sdk.AndroidKeystoreAPIKeyStore
import com.utexo.rgb.sdk.UTEXOWallet
import com.utexo.rgb.sdk.WalletAPIKey
import com.utexo.rgb.sdk.WalletAPIKeyProvider
import com.utexo.rgb.sdk.WalletConfiguration
import com.utexo.rgb.sdk.WalletId
import com.utexo.rgb.sdk.WalletServiceConfiguration
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.SecretMnemonic
import com.utexo.rgb.sdk.deriveKeysFromMnemonic
import com.utexo.rgb.sdk.AndroidKeystoreSeedStore
import com.utexo.rgb.sdk.AndroidKeystoreSeedStoreConfiguration
import com.utexo.rgb.sdk.SecretSeed
import com.utexo.rgb.sdk.WalletUnlockParams
import com.utexo.rgb.sdk.StrongBoxPolicy
import com.utexo.rgb.sdk.deriveKeysFromSeed
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * App-side reachability anchor for the release instrumentation smoke test.
 *
 * Keeping this one consumer class makes R8 retain only the public SDK path the
 * test exercises; all implementation retention still comes from the AAR's
 * consumer rules.
 */
public object ReleaseSignerSmoke {
    public fun seedCustodyRoundTripsAndDoesNotSilentlyChangePolicy(context: Context): Boolean = runBlocking {
        val walletId = WalletId.of("release-seed-${UUID.randomUUID()}")
        val store = AndroidKeystoreSeedStore(context, walletId, Network.REGTEST)
        SecretSeed.fromBytes(ByteArray(64) { 23 }).use { seed ->
            val expected = deriveKeysFromSeed(Network.REGTEST, seed).use { it.masterFingerprint }
            store.provision(seed)
            try {
                var replacementRejected = false
                try { store.provision(seed) } catch (_: SdkException.InvalidState) { replacementRejected = true }
                check(replacementRejected)
                val changed = AndroidKeystoreSeedStore(context, walletId, Network.REGTEST,
                    AndroidKeystoreSeedStoreConfiguration(strongBoxPolicy = StrongBoxPolicy.PREFERRED_WITH_KEYSTORE_FALLBACK))
                var policyRejected = false
                WalletUnlockParams.none().use { params ->
                    try { changed.loadSeed(params).close() } catch (_: SdkException.KeyAccessFailed) { policyRejected = true }
                }
                check(policyRejected)
                val reopened = AndroidKeystoreSeedStore(context, walletId, Network.REGTEST)
                WalletUnlockParams.none().use { params ->
                    reopened.loadSeed(params).use { restored ->
                        deriveKeysFromSeed(Network.REGTEST, restored).use { check(it.masterFingerprint == expected) }
                    }
                }
            } finally {
                // Public fixed test seed only, no funds or application wallet data.
                store.resetForRecovery { true }
            }
        }
        true
    }
    /** Compiles without infrastructure opt-in, then exercises real device Keystore and session locks. */
    public fun ordinaryFactoryOpensAndReopens(context: Context): Boolean = runBlocking {
        val configuration = WalletConfiguration(WalletId.of("release-factory-smoke-v2"), Network.REGTEST)
        val credentials = AndroidKeystoreAPIKeyStore(context, configuration.walletId, configuration.network)
        WalletAPIKey.fromCharacters("test-only-api-key".toCharArray()).use { credentials.store(it) }
        val service = WalletServiceConfiguration("https://gateway.invalid", credentials)
        val first = UTEXOWallet.create(context, configuration, service)
        try {
            check(!first.isDisposed())
            first.shutdown()
            var secondSessionRejected = false
            try {
                UTEXOWallet.create(context, configuration, service).destroy()
            } catch (_: SdkException.OperationJournalUnavailable) {
                secondSessionRejected = true
            }
            check(secondSessionRejected)
            WalletAPIKey.fromCharacters("rotated-test-only-api-key".toCharArray()).use { credentials.store(it) }
            credentials.loadAPIKey().close()
        } finally {
            first.destroy()
        }
        var unexpectedCredentialLoads = 0
        var changedOriginRejected = false
        val changedOrigin = WalletServiceConfiguration("https://another.invalid", WalletAPIKeyProvider {
            unexpectedCredentialLoads += 1
            credentials.loadAPIKey()
        })
        try {
            UTEXOWallet.create(context, configuration, changedOrigin).destroy()
        } catch (_: SdkException.OperationJournalUnavailable) {
            changedOriginRejected = true
        }
        check(changedOriginRejected && unexpectedCredentialLoads == 0)
        val second = UTEXOWallet.create(context, configuration, service)
        second.destroy()
        second.isDisposed()
    }

    public fun matchesRnKeyDerivationVector(): Boolean = runBlocking {
        val mnemonic = SecretMnemonic.fromWords(
            "flight seminar tray bulb level embody switch enhance august deny scene dismiss"
                .toCharArray(),
        )
        try {
            deriveKeysFromMnemonic(Network.TESTNET, mnemonic).use { keys ->
                keys.masterFingerprint == "42424232" &&
                    keys.xpub ==
                    "tpubD6NzVbkrYhZ4WLw9njYmLvMEWp5nqVmjfkgAZfj7uivNXCFS7Ls9DyDZM82P4cDpobCDGAZpNsR9Gs9wuvv5yYCNFsqxcbsDn6VU4nU3ZcW" &&
                    keys.accountXpubVanilla ==
                    "tpubDDTg3fRGqAvEvshRUUwuS8brXkewNsE6y6Jbb9xZcuzBbmxAWa3UCmwyQG4peM9RwjgY8BDwuRVRU5KGtvRby5kiv1dk13YHU8z39o18QJK" &&
                    keys.accountXpubColored ==
                    "tpubDDqoEexZxewBLjXmZ7kxSHgZgmdAtej6DrXLBMiSSuPCdGQqjkTvyETLLVY7qNpxNRGUivvxDj8sswHHihT5effNNDmsbMUX2Lrpy4zjRcS"
            }
        } finally {
            mnemonic.close()
        }
    }
}

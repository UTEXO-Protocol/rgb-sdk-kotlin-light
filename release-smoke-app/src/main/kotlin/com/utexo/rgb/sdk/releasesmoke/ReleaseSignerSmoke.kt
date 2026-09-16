package com.utexo.rgb.sdk.releasesmoke

import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.SecretMnemonic
import com.utexo.rgb.sdk.deriveKeysFromMnemonic
import kotlinx.coroutines.runBlocking

/**
 * App-side reachability anchor for the release instrumentation smoke test.
 *
 * Keeping this one consumer class makes R8 retain only the public SDK path the
 * test exercises; all implementation retention still comes from the AAR's
 * consumer rules.
 */
public object ReleaseSignerSmoke {
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

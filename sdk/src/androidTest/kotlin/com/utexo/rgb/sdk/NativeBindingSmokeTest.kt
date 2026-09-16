package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class NativeBindingSmokeTest {
    @Test
    public fun generatedBindingLoadsPackagedLibraryAndMatchesRnVector(): Unit = runBlocking {
        val mnemonic = SecretMnemonic.fromWords(
            "flight seminar tray bulb level embody switch enhance august deny scene dismiss"
                .toCharArray(),
        )
        try {
            deriveKeysFromMnemonic(Network.TESTNET, mnemonic).use { keys ->
                assertEquals("42424232", keys.masterFingerprint)
                assertEquals(
                    "tpubD6NzVbkrYhZ4WLw9njYmLvMEWp5nqVmjfkgAZfj7uivNXCFS7Ls9DyDZM82P4cDpobCDGAZpNsR9Gs9wuvv5yYCNFsqxcbsDn6VU4nU3ZcW",
                    keys.xpub,
                )
                assertEquals(
                    "tpubDDTg3fRGqAvEvshRUUwuS8brXkewNsE6y6Jbb9xZcuzBbmxAWa3UCmwyQG4peM9RwjgY8BDwuRVRU5KGtvRby5kiv1dk13YHU8z39o18QJK",
                    keys.accountXpubVanilla,
                )
                assertEquals(
                    "tpubDDqoEexZxewBLjXmZ7kxSHgZgmdAtej6DrXLBMiSSuPCdGQqjkTvyETLLVY7qNpxNRGUivvxDj8sswHHihT5effNNDmsbMUX2Lrpy4zjRcS",
                    keys.accountXpubColored,
                )
            }
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                val bytes = seed.copyForNative()
                try {
                    assertEquals(64, bytes.size)
                } finally {
                    bytes.fill(0.toByte())
                }
            }
        } finally {
            mnemonic.close()
        }
    }
}

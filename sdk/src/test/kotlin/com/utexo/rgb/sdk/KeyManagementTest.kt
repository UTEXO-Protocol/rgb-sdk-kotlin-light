package com.utexo.rgb.sdk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class KeyManagementTest {
    @Test
    fun `secret key material is defensively copied redacted and closeable`(): Unit {
        val words = "abandon ability able about above absent absorb abstract absurd abuse access accident"
            .toCharArray()
        val mnemonic = SecretMnemonic.fromWords(words)
        words.fill('x')

        assertEquals(
            "abandon ability able about above absent absorb abstract absurd abuse access accident",
            mnemonic.useWords(::String),
        )
        assertEquals("SecretMnemonic([REDACTED])", mnemonic.toString())
        mnemonic.close()
        assertFailsWith<SdkException.InvalidState> { mnemonic.useWords { Unit } }

        val xprivText = "tprv8ZgxMBicQKsPe-fake-test-only".toCharArray()
        val xpriv = SecretExtendedPrivateKey.fromCharacters(xprivText)
        xprivText.fill('x')
        assertEquals("SecretExtendedPrivateKey([REDACTED])", xpriv.toString())
        xpriv.close()
        assertFailsWith<SdkException.InvalidState> { xpriv.copyAsString() }
    }

    @Test
    fun `generated key descriptions never contain mnemonic or xpriv`(): Unit {
        val mnemonic = SecretMnemonic.fromWords("alpha beta gamma".toCharArray())
        val xpriv = SecretExtendedPrivateKey.fromCharacters("private-test-value".toCharArray())
        val keys = GeneratedKeys(
            mnemonic = mnemonic,
            xpub = "xpub-test",
            accountXpubVanilla = "xpub-vanilla",
            accountXpubColored = "xpub-colored",
            masterFingerprint = "00112233",
            xpriv = xpriv,
        )

        val description = keys.toString()
        assertFalse(description.contains("alpha"))
        assertFalse(description.contains("private-test-value"))
        keys.close()
        assertFailsWith<SdkException.InvalidState> { mnemonic.useWords { Unit } }
        assertFailsWith<SdkException.InvalidState> { xpriv.copyAsString() }
    }

    @Test
    fun `restore helper requires exactly one secret source`(): Unit = runBlocking {
        val mnemonic = SecretMnemonic.fromWords("alpha beta gamma".toCharArray())
        val seed = SecretSeed.fromBytes(ByteArray(64))
        try {
            assertFailsWith<SdkException.InvalidArgument> {
                deriveKeysFromMnemonicOrSeed(Network.SIGNET)
            }
            assertFailsWith<SdkException.InvalidArgument> {
                deriveKeysFromMnemonicOrSeed(
                    network = Network.SIGNET,
                    mnemonic = mnemonic,
                    seed = seed,
                )
            }
            assertFailsWith<SdkException.InvalidArgument> {
                deriveKeysFromMnemonicOrSeed(
                    network = Network.SIGNET,
                    seed = seed,
                    passphrase = charArrayOf('n', 'o'),
                )
            }
        } finally {
            mnemonic.close()
            seed.close()
        }
    }

    @Test
    fun `invalid unicode is rejected before native key derivation`(): Unit = runBlocking {
        val mnemonic = SecretMnemonic.fromWords("alpha beta gamma".toCharArray())
        try {
            assertFailsWith<SdkException.InvalidArgument> {
                deriveSeedFromMnemonic(mnemonic, charArrayOf('\uD800'))
            }
            @Suppress("DEPRECATION")
            assertFailsWith<SdkException.InvalidArgument> {
                getXprivFromMnemonic(Network.SIGNET, mnemonic, charArrayOf('\uD800'))
            }
            assertFailsWith<SdkException.InvalidArgument> {
                deriveKeysFromMnemonic(
                    network = Network.SIGNET,
                    mnemonic = mnemonic,
                    passphrase = charArrayOf('\uD800'),
                )
            }
        } finally {
            mnemonic.close()
        }
    }
}

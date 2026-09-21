package com.utexo.rgb.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class WalletServiceConfigurationTest {
    private val provider = WalletAPIKeyProvider { WalletAPIKey.fromCharacters("token".toCharArray()) }

    @Test
    fun `ordinary configuration validates transport without loading credentials and keeps profile disabled`(): Unit {
        val config = WalletServiceConfiguration("https://gateway.example", provider)
        assertFalse(config.gatewayConfiguration(Network.SIGNET).allowUnverifiedPsbtProfile)
        assertFailsWith<SdkException.InvalidArgument> { WalletServiceConfiguration("http://gateway.example", provider) }
        assertFailsWith<SdkException.InvalidArgument> {
            WalletServiceConfiguration("https://gateway.example", provider, callTimeoutMillis = 0)
        }
        assertFailsWith<SdkException.InvalidArgument> { WalletServiceConfiguration("https://user:pass@example.com", provider) }
    }

    @Test
    fun `API key copies caller memory redacts strings and clears on close`(): Unit {
        val source = "secret-value".toCharArray()
        val key = WalletAPIKey.fromCharacters(source)
        source.fill('x')
        assertEquals("secret-value", key.authorizationValue())
        assertFalse(key.toString().contains("secret-value"))
        key.close()
        key.close()
        assertFailsWith<SdkException.InvalidState> { key.authorizationValue() }
        assertFailsWith<SdkException.InvalidArgument> { WalletAPIKey.fromCharacters("abc\r\nheader".toCharArray()) }
        assertFailsWith<SdkException.InvalidArgument> { WalletAPIKey.fromCharacters(charArrayOf()) }
        assertFailsWith<SdkException.InvalidArgument> { WalletAPIKey.fromCharacters(CharArray(513) { 'a' }) }
    }
}

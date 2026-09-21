package com.utexo.rgb.sdk.mavenconsumer

import android.content.Context
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.UTEXOWallet
import com.utexo.rgb.sdk.WalletConfiguration
import com.utexo.rgb.sdk.WalletServiceConfiguration
import com.utexo.rgb.sdk.generateKeys

/** Compiles against public SDK declarations with no project dependency or runtime overrides. */
public object PackagedConsumer {
    public suspend fun open(
        context: Context,
        configuration: WalletConfiguration,
        service: WalletServiceConfiguration,
    ): UTEXOWallet = UTEXOWallet.create(context, configuration, service)

    public suspend fun keyFingerprint(): String = generateKeys(Network.SIGNET).use { it.masterFingerprint }
}

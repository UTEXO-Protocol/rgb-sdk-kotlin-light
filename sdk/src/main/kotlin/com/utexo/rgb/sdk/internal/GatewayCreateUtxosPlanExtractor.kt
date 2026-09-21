package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.AccountKind
import com.utexo.rgb.sdk.CreateUtxosOutputPlan
import com.utexo.rgb.sdk.ExpectedWalletOutput
import com.utexo.rgb.sdk.Keychain
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.Satoshis
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.internal.ffi.AccountKind as FfiAccountKind
import com.utexo.rgb.sdk.internal.ffi.Keychain as FfiKeychain
import com.utexo.rgb.sdk.internal.ffi.Network as FfiNetwork
import com.utexo.rgb.sdk.internal.ffi.SignerException as FfiSignerException
import com.utexo.rgb.sdk.internal.ffi.extractGatewayCreateUtxosOutputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Parses untrusted output hints; it never substitutes for local seed-based signing validation. */
internal object GatewayCreateUtxosPlanExtractor {
    suspend fun extract(psbtBase64: String, network: Network): CreateUtxosOutputPlan = withContext(Dispatchers.Default) {
        NativeRuntime.ensureInitialized()
        val ffiNetwork = when (network) {
            Network.MAINNET -> FfiNetwork.MAINNET
            Network.TESTNET -> FfiNetwork.TESTNET
            Network.SIGNET -> FfiNetwork.SIGNET
            Network.REGTEST -> FfiNetwork.REGTEST
            else -> throw SdkException.Unsupported("gateway create-UTXOs network")
        }
        try {
            CreateUtxosOutputPlan(extractGatewayCreateUtxosOutputs(psbtBase64, ffiNetwork).map {
                if (it.account != FfiAccountKind.COLORED || it.keychain != FfiKeychain.EXTERNAL ||
                    it.index > Int.MAX_VALUE.toUInt() || it.amountSats > Satoshis.MAX_VALUE.toULong()) {
                    throw SdkException.BackendProtocolMismatch("gateway PSBT contains an invalid create output hint")
                }
                ExpectedWalletOutput(AccountKind.COLORED, Keychain.EXTERNAL, it.index.toInt(), Satoshis.of(it.amountSats.toLong()))
            })
        } catch (_: FfiSignerException) {
            throw SdkException.BackendProtocolMismatch("gateway PSBT create output hints could not be parsed")
        } catch (_: LinkageError) {
            throw SdkException.Unsupported("compatible native utexo-local-signer library")
        } catch (_: SdkException.InvalidArgument) {
            throw SdkException.BackendProtocolMismatch("gateway PSBT contains invalid create output hints")
        }
    }
}

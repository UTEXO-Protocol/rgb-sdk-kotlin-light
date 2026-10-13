package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.AssetId
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.RgbInvoice
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.internal.ffi.Network as FfiNetwork
import com.utexo.rgb.sdk.internal.ffi.RgbInvoiceRecipientType as FfiInvoiceRecipientType
import com.utexo.rgb.sdk.internal.ffi.SignerException as FfiSignerException
import com.utexo.rgb.sdk.internal.ffi.decodeRgbInvoice as ffiDecodeRgbInvoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The parts of an RGB invoice the gateway's send-asset request needs.
 *
 * The gateway is asked for a transfer by `recipientId`, not by invoice, so the
 * recipient has to be read out of the invoice somewhere. Doing it locally with
 * the native parser — rather than letting the caller pass a recipient id, or
 * trusting one echoed back by the server — keeps the authority with the
 * invoice the caller actually holds.
 *
 * This is NOT validation. The signer independently re-decodes the same invoice
 * and binds its raw encoded state, asset, amount and witness payment when it
 * authorizes the transaction; nothing here substitutes for that.
 */
internal data class GatewayRgbRecipient(
    val recipientId: String,
    val assetId: AssetId?,
    val transportEndpoints: List<String>,
    val witness: Boolean,
    val network: Network,
)

internal object GatewayRgbInvoiceDecoder {
    suspend fun decode(invoice: RgbInvoice): GatewayRgbRecipient = withContext(Dispatchers.Default) {
        NativeRuntime.ensureInitialized()
        try {
            val decoded = ffiDecodeRgbInvoice(invoice.value)
            GatewayRgbRecipient(
                recipientId = decoded.recipientId,
                assetId = decoded.assetId?.let(AssetId::of),
                transportEndpoints = decoded.transportEndpoints,
                witness = decoded.recipientType == FfiInvoiceRecipientType.WITNESS,
                network = when (decoded.network) {
                    FfiNetwork.MAINNET -> Network.MAINNET
                    FfiNetwork.TESTNET -> Network.TESTNET
                    FfiNetwork.SIGNET -> Network.SIGNET
                    FfiNetwork.REGTEST -> Network.REGTEST
                    // The gateway adapter already refuses these networks, so an
                    // invoice naming one cannot belong to this session.
                    else -> throw SdkException.Unsupported("gateway RGB invoice network")
                },
            )
        } catch (_: FfiSignerException) {
            throw SdkException.InvalidArgument("invoice", "is not a decodable RGB invoice")
        } catch (_: LinkageError) {
            throw SdkException.Unsupported("compatible native utexo-local-signer library")
        }
    }
}

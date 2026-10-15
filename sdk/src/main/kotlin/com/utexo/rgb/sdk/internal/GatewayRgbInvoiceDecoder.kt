package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.RgbInvoice
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.internal.ffi.RgbInvoiceRecipientType
import com.utexo.rgb.sdk.internal.ffi.SignerException
import com.utexo.rgb.sdk.internal.ffi.decodeRgbInvoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Routing projection only; the facade and Bitcoin signer still validate the full invoice intent. */
internal data class GatewayRgbInvoice(
    val network: Network,
    val assetId: String?,
    val recipientId: String,
    val witness: Boolean,
    val transportEndpoints: List<String>,
)

internal object GatewayRgbInvoiceDecoder {
    suspend fun decode(invoice: RgbInvoice): GatewayRgbInvoice = withContext(Dispatchers.Default) {
        try {
            NativeRuntime.ensureInitialized()
            val value = decodeRgbInvoice(invoice.value)
            GatewayRgbInvoice(Network.valueOf(value.network.name), value.assetId, value.recipientId,
                value.recipientType == RgbInvoiceRecipientType.WITNESS, value.transportEndpoints)
        } catch (_: SignerException) {
            throw SdkException.InvalidArgument("invoice", "must be a supported canonical RGB invoice")
        } catch (_: LinkageError) {
            throw SdkException.Unsupported("compatible native RGB invoice decoder")
        }
    }
}

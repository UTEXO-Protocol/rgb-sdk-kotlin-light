package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.CreateUtxosRequest
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.OnchainSendRequest
import com.utexo.rgb.sdk.RequestHash
import com.utexo.rgb.sdk.SendBtcRequest
import java.nio.ByteBuffer
import java.security.MessageDigest

internal object RequestHasher {
    private const val DOMAIN = "utexo.wallet.operation-intent.v1"

    fun sendBtc(network: Network, request: SendBtcRequest): RequestHash = digest(
        "operation" to "sendBtc",
        "network" to network.wireValue,
        "address" to request.address.value,
        "amountSats" to request.amount.value.toString(),
        "feeRateMsatPerVbyte" to request.feeRate.milliSatsPerVbyte.toString(),
        "skipSync" to request.skipSync.toString(),
    )

    fun createUtxos(network: Network, request: CreateUtxosRequest): RequestHash = digest(
        "operation" to "createUtxos",
        "network" to network.wireValue,
        "upTo" to request.upTo.toString(),
        "num" to (request.num?.toString() ?: "null"),
        "sizeSats" to (request.size?.value?.toString() ?: "null"),
        "feeRateMsatPerVbyte" to request.feeRate.milliSatsPerVbyte.toString(),
        "skipSync" to request.skipSync.toString(),
    )

    fun onchainSend(network: Network, request: OnchainSendRequest): RequestHash = digest(
        "operation" to "onchainSend",
        "network" to network.wireValue,
        "invoice" to request.invoice.value,
        "assetId" to (request.assetId?.value ?: "null"),
        "amount" to request.amount.decimal,
        "witnessAmountSats" to (request.witnessData?.amountSat?.value?.toString() ?: "null"),
        "witnessBlinding" to (request.witnessData?.blinding?.toString() ?: "null"),
        "donation" to request.donation.toString(),
        "feeRateMsatPerVbyte" to request.feeRate.milliSatsPerVbyte.toString(),
        "minConfirmations" to request.minConfirmations.value.toString(),
        "skipSync" to request.skipSync.toString(),
    )

    private fun digest(vararg fields: Pair<String, String>): RequestHash {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, DOMAIN)
        for ((name, value) in fields) {
            update(digest, name)
            update(digest, value)
        }
        return RequestHash(digest.digest().joinToString(separator = "") { "%02x".format(it) })
    }

    private fun update(digest: MessageDigest, value: String): Unit {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
}

package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.AccountKind
import com.utexo.rgb.sdk.CreateUtxosOutputPlan
import com.utexo.rgb.sdk.CreateUtxosRequest
import com.utexo.rgb.sdk.LocalSigningPolicy
import com.utexo.rgb.sdk.LocalValidationReport
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.InvoiceData
import com.utexo.rgb.sdk.RgbInvoice
import com.utexo.rgb.sdk.OnchainSendRequest
import com.utexo.rgb.sdk.PreparedTransaction
import com.utexo.rgb.sdk.ReservedAddress
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.SecretSeed
import com.utexo.rgb.sdk.SendBtcRequest
import com.utexo.rgb.sdk.WalletCapabilities
import com.utexo.rgb.sdk.WalletIdentity

internal interface LocalSigningEngine : AutoCloseable {
    val capabilities: WalletCapabilities

    suspend fun identity(): WalletIdentity

    suspend fun verifyReservedAddress(reservation: ReservedAddress): Unit

    suspend fun decodeRgbInvoice(invoice: RgbInvoice): InvoiceData

    suspend fun validateAndSign(
        prepared: PreparedTransaction,
        intent: LocalOperationIntent,
    ): LocalSigningResult
}

internal sealed interface LocalOperationIntent {
    val network: Network
    val policy: LocalSigningPolicy

    data class SendBtc(
        override val network: Network,
        override val policy: LocalSigningPolicy,
        val request: SendBtcRequest,
    ) : LocalOperationIntent

    data class CreateUtxos(
        override val network: Network,
        override val policy: LocalSigningPolicy,
        val request: CreateUtxosRequest,
        val exactOutputPlan: CreateUtxosOutputPlan,
    ) : LocalOperationIntent

    data class OnchainSend(
        override val network: Network,
        override val policy: LocalSigningPolicy,
        val request: OnchainSendRequest,
    ) : LocalOperationIntent
}

internal data class LocalSigningResult(
    val signedPsbtBase64: String,
    val validation: LocalValidationReport,
)

internal fun interface LocalSigningEngineFactory {
    suspend fun open(network: Network, seed: SecretSeed): LocalSigningEngine
}

internal fun requireColoredExternalOutputs(plan: CreateUtxosOutputPlan): Unit {
    if (plan.outputs.any { it.account != AccountKind.COLORED }) {
        throw SdkException.PolicyRejected("createUtxos output plan contains a non-colored output")
    }
}

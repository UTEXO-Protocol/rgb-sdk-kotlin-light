package com.utexo.rgb.sdk.internal

import com.utexo.rgb.sdk.LocalSigningPolicy
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.OnchainSendRequest
import com.utexo.rgb.sdk.PreparedTransaction
import com.utexo.rgb.sdk.RgbSendEvidence
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.TransactionId
import com.utexo.rgb.sdk.internal.ffi.verifier.ExistingOutputProof as FfiExistingOutputProof
import com.utexo.rgb.sdk.internal.ffi.verifier.Network as FfiVerifierNetwork
import com.utexo.rgb.sdk.internal.ffi.verifier.RgbSendRequest as FfiRgbSendRequest
import com.utexo.rgb.sdk.internal.ffi.verifier.VerifierException as FfiVerifierException
import com.utexo.rgb.sdk.internal.ffi.verifier.WalletAccounts as FfiWalletAccounts
import com.utexo.rgb.sdk.internal.ffi.verifier.verifyRgbSend as ffiVerifyRgbSend
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex

/**
 * Pre-sign RGB verification through `utexo-rgb-verifier`. Spending a colored input
 * authorizes whatever RGB transition the transaction commits to, and the Bitcoin signer
 * cannot see that commitment. The verifier proves that every allocation the signature
 * moves goes to the invoice's beneficiary, in exactly the approved asset and amount, or
 * back to this wallet's colored account. It does not validate RGB history.
 */
internal object RgbSendVerification {
    /** The verdict format this SDK reads; a different library build is rejected. */
    const val PROFILE: String = "utexo.rgb-send.v1"

    /**
     * Returns the evidence to verify, or null only when [policy] explicitly allows signing
     * an RGB send blind. Missing evidence is never a silent downgrade: a hostile backend
     * could otherwise skip verification by leaving it out.
     */
    fun requireEvidence(prepared: PreparedTransaction, policy: LocalSigningPolicy): RgbSendEvidence? =
        prepared.rgbSendEvidence ?: if (policy.allowUnverifiedRgbSends) null else {
            throw SdkException.PolicyRejected("RGB send preparation has no RGB send evidence to verify")
        }

    /** Returns the unsigned txid the verdict covers. Call before any signature exists. */
    fun verify(
        network: Network,
        accounts: FfiWalletAccounts,
        prepared: PreparedTransaction,
        evidence: RgbSendEvidence,
        request: OnchainSendRequest,
        assetId: String,
        policy: LocalSigningPolicy,
    ): TransactionId {
        val fascia = evidence.fasciaBase64.decodeBase64()?.toByteArray()
            ?: throw SdkException.PolicyRejected("RGB fascia is not Base64")
        val verdict = try {
            NativeRuntime.ensureVerifierInitialized()
            ffiVerifyRgbSend(
                FfiRgbSendRequest(
                    network = network.toVerifierFfi(),
                    accounts = accounts,
                    psbtBase64 = prepared.psbtBase64,
                    fascia = fascia,
                    mpcEntropy = evidence.mpcEntropy,
                    invoice = request.invoice.value,
                    assetId = assetId,
                    amount = request.amount.decimal.toULong(),
                    existingOutputProofs = evidence.existingOutputProofs.map {
                        FfiExistingOutputProof(it.transactionHex.decodeHex().toByteArray(),
                            it.vout.toUInt(), it.derivationIndex.toUInt())
                    },
                    maxDerivationIndex = policy.maximumChildIndex.toUInt(),
                ),
            )
        } catch (sdk: SdkException) {
            throw sdk
        } catch (rejected: FfiVerifierException) {
            throw rejected.toSdkException()
        } catch (_: LinkageError) {
            throw SdkException.Unsupported("native utexo-rgb-verifier library")
        } catch (_: Exception) {
            // UniFFI's InternalException is not a RuntimeException; never surface its text.
            throw SdkException.InternalInvariantViolation("native RGB send verifier call failed")
        }
        if (verdict.profile != PROFILE) {
            throw SdkException.Unsupported("RGB send verifier profile ${verdict.profile}")
        }
        return TransactionId.of(verdict.txid)
    }
}

/**
 * Verifier reasons name output indexes, counts and asset ids only, never keys, PSBTs,
 * fascia bytes or invoices, so a rejection reason is safe to carry in the message.
 */
internal fun FfiVerifierException.toSdkException(): SdkException = when (this) {
    is FfiVerifierException.Rejected -> SdkException.PolicyRejected("RGB send verification failed: $reason")
    is FfiVerifierException.InvalidPsbt -> SdkException.PolicyRejected("transaction encoding is invalid")
    is FfiVerifierException.InvalidFascia -> SdkException.PolicyRejected("RGB fascia is invalid: $reason")
    is FfiVerifierException.InvalidRgbInvoice -> SdkException.InvalidArgument("rgbInvoice", "is invalid or contradicts the request")
    // Also raised for malformed backend proofs, so it is a refusal to sign, not a local fault.
    is FfiVerifierException.InvalidArgument -> SdkException.PolicyRejected("RGB send verification input is invalid: $reason")
}

private fun Network.toVerifierFfi(): FfiVerifierNetwork = when (this) {
    Network.MAINNET -> FfiVerifierNetwork.MAINNET
    Network.TESTNET -> FfiVerifierNetwork.TESTNET
    Network.TESTNET4 -> FfiVerifierNetwork.TESTNET4
    Network.SIGNET -> FfiVerifierNetwork.SIGNET
    Network.REGTEST -> FfiVerifierNetwork.REGTEST
    Network.UTEXO -> FfiVerifierNetwork.UTEXO
}

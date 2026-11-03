package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.RgbSendVerification
import com.utexo.rgb.sdk.internal.ffi.verifier.VerifierException
import com.utexo.rgb.sdk.internal.toSdkException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class RgbSendVerificationTest {
    private val fascia = "ZmFzY2lhLWJ5dGVzLWZvci10ZXN0cw=="

    @Test
    fun `an RGB send without evidence is refused unless the policy explicitly allows blind signing`(): Unit {
        val blind = prepared(null)
        assertFailsWith<SdkException.PolicyRejected> { RgbSendVerification.requireEvidence(blind, LocalSigningPolicy()) }
        assertEquals(null, RgbSendVerification.requireEvidence(blind, LocalSigningPolicy(allowUnverifiedRgbSends = true)))
        // Evidence that arrives is verified whatever the policy allows.
        val evidence = RgbSendEvidence(fascia, 7uL)
        assertSame(evidence, RgbSendVerification.requireEvidence(prepared(evidence),
            LocalSigningPolicy(allowUnverifiedRgbSends = true)))
    }

    @Test
    fun `verifier failures refuse signing and keep only the verifier's safe reason`(): Unit {
        val reason = "an RGB assignment goes to output 2, which is neither the wallet's nor the invoice's"
        val rejected = VerifierException.Rejected(reason).toSdkException()
        assertEquals(SdkErrorCode.POLICY_REJECTED, rejected.code)
        assertTrue(rejected.message.orEmpty().endsWith(reason))
        for (failure in listOf(VerifierException.InvalidArgument("existing-output proof transaction does not parse"),
            VerifierException.InvalidPsbt("binary PSBT parsing failed"), VerifierException.InvalidFascia("strict decoding failed"))) {
            assertEquals(SdkErrorCode.POLICY_REJECTED, failure.toSdkException().code)
            assertEquals(null, failure.toSdkException().cause)
        }
        assertEquals(SdkErrorCode.INVALID_ARGUMENT,
            VerifierException.InvalidRgbInvoice("invoice is for another network").toSdkException().code)
    }

    @Test
    fun `evidence is confined to RGB sends, bounded and redacted from strings`(): Unit {
        val evidence = RgbSendEvidence(fascia, 7uL, listOf(RgbExistingOutputProof("02000000", 1, 7)))
        assertFailsWith<SdkException.InvalidArgument> {
            PreparedTransaction(TEST_OPERATION_ID, TEST_IDEMPOTENCY_KEY, RequestHash.ofLowercaseHex("d".repeat(64)),
                Network.SIGNET, OperationKind.SEND_BTC, "prepared-psbt", UnixSeconds.of(TEST_NOW + 1_000),
                rgbSendEvidence = evidence)
        }
        assertFalse(evidence.toString().contains(fascia))
        assertFalse(evidence.existingOutputProofs.single().toString().contains("02000000"))
        for (invalid in listOf({ RgbExistingOutputProof("02AB", 0, 0) }, { RgbExistingOutputProof("020", 0, 0) },
            { RgbExistingOutputProof("", 0, 0) }, { RgbExistingOutputProof("02", -1, 0) },
            { RgbExistingOutputProof("02", 0, -1) }, { RgbSendEvidence(" $fascia", 7uL) },
            { RgbSendEvidence(fascia, 7uL, List(RgbSendEvidence.MAX_EXISTING_OUTPUT_PROOFS + 1) { evidence.existingOutputProofs[0] }) })) {
            assertFailsWith<SdkException.InvalidArgument> { invalid() }
        }
    }

    private fun prepared(evidence: RgbSendEvidence?): PreparedTransaction = PreparedTransaction(TEST_OPERATION_ID,
        TEST_IDEMPOTENCY_KEY, RequestHash.ofLowercaseHex("d".repeat(64)), Network.SIGNET, OperationKind.ONCHAIN_SEND,
        "prepared-psbt", UnixSeconds.of(TEST_NOW + 1_000), rgbSendEvidence = evidence)
}

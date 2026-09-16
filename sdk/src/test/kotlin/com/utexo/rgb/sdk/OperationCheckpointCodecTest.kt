package com.utexo.rgb.sdk

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal class OperationCheckpointCodecTest {
    @Test
    fun `created checkpoint encoding matches the cross-platform golden vector`(): Unit {
        val checkpoint = OperationCheckpoint(
            walletId = WalletId.of("wallet-1"),
            idempotencyKey = IdempotencyKey.of("idempotency-1"),
            requestHash = RequestHash.ofLowercaseHex("d".repeat(64)),
            operationKind = OperationKind.SEND_BTC,
            phase = OperationPhase.CREATED,
        )
        val expected = Base64.getDecoder().decode(
            "VVRYT1BDSEsAAAABAAAACHdhbGxldC0xAAAADWlkZW1wb3RlbmN5LTEAAABAZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZGRkZAAAAAdzZW5kQnRjAAAAB2NyZWF0ZWQAAAAAc1KCjkjFxqbzAdbex02UCkGyvtsberBuwcR4gJro8Ug=",
        )

        assertContentEquals(expected, OperationCheckpointCodec.encode(checkpoint))
        assertEquals(checkpoint, OperationCheckpointCodec.decode(expected))
    }

    @Test
    fun `codec round trips exact signed bytes and successful outcome canonically`(): Unit {
        val checkpoint = signedCheckpoint(
            OperationTerminalOutcome.Succeeded(
                BackendOperationResult.SendBtc(TEST_OPERATION_ID, TEST_TXID),
            ),
        )

        val encoded = OperationCheckpointCodec.encode(checkpoint)
        val decoded = OperationCheckpointCodec.decode(encoded)

        assertEquals(OperationCheckpointCodec.SCHEMA_VERSION, 1)
        assertEquals(OperationPhase.TERMINAL, decoded.phase)
        assertEquals("prepared-psbt", decoded.preparedTransaction?.psbtBase64)
        assertEquals("signed-psbt", decoded.signedPayload?.signedPsbtBase64)
        assertEquals(TEST_TXID, decoded.terminalResult?.let { resultTxid(it) })
        assertContentEquals(encoded, OperationCheckpointCodec.encode(decoded))
    }

    @Test
    fun `codec round trips durable failure and expiry outcomes`(): Unit {
        val failed = OperationTerminalOutcome.Failed(
            operationId = TEST_OPERATION_ID,
            safeMessage = "transaction rejected",
            retryable = false,
            backendCode = "policy_rejected",
            correlationId = "correlation-1",
        )
        val decodedFailure = OperationCheckpointCodec.decode(
            OperationCheckpointCodec.encode(signedCheckpoint(failed)),
        )
        assertEquals(failed, assertIs<OperationTerminalOutcome.Failed>(decodedFailure.terminalOutcome))

        val expired = OperationTerminalOutcome.Expired(TEST_OPERATION_ID)
        val decodedExpiry = OperationCheckpointCodec.decode(
            OperationCheckpointCodec.encode(signedCheckpoint(expired)),
        )
        assertEquals(expired, assertIs<OperationTerminalOutcome.Expired>(decodedExpiry.terminalOutcome))
    }

    @Test
    fun `codec rejects tampering truncation and oversized input with a redacted error`(): Unit {
        val encoded = OperationCheckpointCodec.encode(
            signedCheckpoint(
                OperationTerminalOutcome.Succeeded(
                    BackendOperationResult.SendBtc(TEST_OPERATION_ID, TEST_TXID),
                ),
            ),
        )
        val tampered = encoded.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }

        listOf(tampered, encoded.copyOf(encoded.size - 1)).forEach { invalid ->
            val error = assertFailsWith<SdkException.OperationJournalUnavailable> {
                OperationCheckpointCodec.decode(invalid)
            }
            assertEquals(null, error.cause)
            assertEquals(false, error.message.orEmpty().contains("psbt"))
        }
        assertFailsWith<SdkException.OperationJournalUnavailable> {
            OperationCheckpointCodec.decode(
                ByteArray(OperationCheckpointCodec.MAX_ENCODED_BYTES + 1),
            )
        }
    }

    private fun signedCheckpoint(outcome: OperationTerminalOutcome): OperationCheckpoint {
        val prepared = PreparedTransaction(
            operationId = TEST_OPERATION_ID,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("d".repeat(64)),
            network = Network.SIGNET,
            operation = OperationKind.SEND_BTC,
            psbtBase64 = "prepared-psbt",
            expiresAt = UnixSeconds.of(TEST_NOW + 1_000),
        )
        return OperationCheckpoint(
            walletId = testConfiguration().walletId,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = prepared.requestHash,
            operationKind = OperationKind.SEND_BTC,
            phase = OperationPhase.TERMINAL,
            operationId = TEST_OPERATION_ID,
            preparedTransaction = prepared,
            signedPayload = SignedOperationPayload("signed-psbt", testValidation()),
            terminalOutcome = outcome,
        )
    }

    private fun resultTxid(result: BackendOperationResult): TransactionId = when (result) {
        is BackendOperationResult.SendBtc -> result.txid
        is BackendOperationResult.CreateUtxos -> result.result.txid
        is BackendOperationResult.OnchainSend -> result.result.txid
    }
}

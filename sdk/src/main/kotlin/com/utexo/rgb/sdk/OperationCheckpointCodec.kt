package com.utexo.rgb.sdk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical, versioned persistence encoding for [OperationCheckpoint].
 *
 * The result contains signed transaction material and is not encrypted. A
 * production [OperationJournal] must protect the returned bytes at rest and
 * atomically replace its record. The trailing SHA-256 detects truncation and
 * accidental corruption; it is not an authentication mechanism.
 */
@WalletInfrastructureApi
public object OperationCheckpointCodec {
    public const val SCHEMA_VERSION: Int = 1
    public const val MAX_ENCODED_BYTES: Int = 16_000_000
    private const val COMPACT_TERMINAL_SCHEMA_VERSION: Int = 2

    private const val CHECKSUM_BYTES: Int = 32
    private val magic: ByteArray = "UTXOPCHK".toByteArray(StandardCharsets.US_ASCII)

    public fun encode(checkpoint: OperationCheckpoint): ByteArray = codecBoundary {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            val writer = CheckpointWriter(output)
            output.write(magic)
            val compactTerminal = checkpoint.phase == OperationPhase.TERMINAL && checkpoint.preparedTransaction == null
            output.writeInt(if (compactTerminal) COMPACT_TERMINAL_SCHEMA_VERSION else SCHEMA_VERSION)
            writer.string(checkpoint.walletId.value, 128)
            writer.string(checkpoint.idempotencyKey.value, 128)
            writer.string(checkpoint.requestHash.hex, 64)
            writer.string(checkpoint.operationKind.wireValue, 32)
            writer.string(checkpoint.phase.wireValue, 32)
            if (compactTerminal) {
                writer.string(requireNotNull(checkpoint.operationId).value, 128)
                writer.optional(checkpoint.signedPayload?.validation, writer::validation)
                writer.outcome(requireNotNull(checkpoint.terminalOutcome))
            } else {
                writer.optional(checkpoint.operationId) { writer.string(it.value, 128) }
                writer.optional(checkpoint.preparedTransaction, writer::prepared)
                writer.optional(checkpoint.signedPayload, writer::signed)
                writer.optional(checkpoint.terminalOutcome, writer::outcome)
            }
        }
        val body = buffer.toByteArray()
        if (body.size > MAX_ENCODED_BYTES - CHECKSUM_BYTES) {
            throw IOException("operation checkpoint exceeds size limit")
        }
        body + sha256(body)
    }

    public fun decode(encoded: ByteArray): OperationCheckpoint = codecBoundary {
        if (encoded.size <= magic.size + Int.SIZE_BYTES + CHECKSUM_BYTES ||
            encoded.size > MAX_ENCODED_BYTES
        ) {
            throw IOException("invalid operation checkpoint size")
        }
        val bodySize = encoded.size - CHECKSUM_BYTES
        val body = encoded.copyOfRange(0, bodySize)
        val expectedChecksum = encoded.copyOfRange(bodySize, encoded.size)
        if (!MessageDigest.isEqual(sha256(body), expectedChecksum)) {
            throw IOException("operation checkpoint checksum mismatch")
        }
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val actualMagic = ByteArray(magic.size)
            input.readFully(actualMagic)
            if (!actualMagic.contentEquals(magic)) {
                throw IOException("invalid operation checkpoint magic")
            }
            val schema = input.readInt()
            if (schema != SCHEMA_VERSION && schema != COMPACT_TERMINAL_SCHEMA_VERSION) {
                throw IOException("unsupported operation checkpoint schema")
            }
            val reader = CheckpointReader(input)
            val walletId = WalletId.of(reader.string(128))
            val key = IdempotencyKey.of(reader.string(128))
            val hash = RequestHash.ofLowercaseHex(reader.string(64))
            val kind = reader.operationKind()
            val phase = reader.phase()
            val checkpoint = if (schema == COMPACT_TERMINAL_SCHEMA_VERSION) {
                if (phase != OperationPhase.TERMINAL) throw IOException("compact receipt must be terminal")
                OperationCheckpoint(
                    walletId, key, hash, kind, phase,
                    operationId = OperationId.of(reader.string(128)),
                    signedPayload = reader.optional(reader::validation)?.let {
                        SignedOperationPayload("terminal-receipt", it).terminalReceipt()
                    },
                    terminalOutcome = reader.outcome(),
                )
            } else {
                OperationCheckpoint(
                    walletId, key, hash, kind, phase,
                    operationId = reader.optional { OperationId.of(reader.string(128)) },
                    preparedTransaction = reader.optional(reader::prepared),
                    signedPayload = reader.optional(reader::signed),
                    terminalOutcome = reader.optional(reader::outcome),
                ).also {
                    if (phase != OperationPhase.CREATED && it.preparedTransaction == null) {
                        throw IOException("schema v1 requires full preparation")
                    }
                }
            }
            if (input.available() != 0) throw IOException("trailing operation checkpoint data")
            checkpoint
        }
    }

    private inline fun <T> codecBoundary(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        // Decoder/encoder failures can include transaction bytes in provider
        // messages, so no underlying exception is retained or exposed.
        throw SdkException.OperationJournalUnavailable()
    }
}

private class CheckpointWriter(private val output: DataOutputStream) {
    fun string(value: String, maximumBytes: Int): Unit {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maximumBytes) throw IOException("checkpoint field exceeds size limit")
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    fun <T> optional(value: T?, write: (T) -> Unit): Unit {
        output.writeByte(if (value == null) 0 else 1)
        if (value != null) write(value)
    }

    fun prepared(value: PreparedTransaction): Unit {
        string(value.operationId.value, 128)
        string(value.idempotencyKey.value, 128)
        string(value.requestHash.hex, 64)
        string(value.network.wireValue, 32)
        string(value.operation.wireValue, 32)
        string(value.psbtBase64, 5_400_000)
        output.writeLong(value.expiresAt.value)
        optional(value.createUtxosOutputPlan, ::outputPlan)
        optional(value.correlationId) { string(it, 128) }
    }

    fun outputPlan(value: CreateUtxosOutputPlan): Unit {
        if (value.outputs.size > 256) throw IOException("too many checkpoint outputs")
        output.writeInt(value.outputs.size)
        value.outputs.forEach { item ->
            string(item.account.wireValue, 32)
            string(item.keychain.wireValue, 32)
            output.writeInt(item.index)
            output.writeLong(item.amount.value)
        }
    }

    fun signed(value: SignedOperationPayload): Unit {
        string(value.signedPsbtBase64, 5_400_000)
        validation(value.validation)
    }

    fun validation(value: LocalValidationReport): Unit {
        string(value.psbtSha256, 64)
        string(value.intentSha256, 64)
        string(value.unsignedTxid.hex, 64)
        output.writeLong(value.totalInput.value)
        output.writeLong(value.totalOutput.value)
        output.writeLong(value.fee.value)
        output.writeLong(value.estimatedVbytes)
        output.writeLong(value.feeRate.milliSatsPerVbyte)
        output.writeLong(value.externalOutput.value)
        output.writeLong(value.change.value)
        output.writeInt(value.inputCount)
        output.writeInt(value.outputCount)
        output.writeInt(value.opReturnCount)
    }

    fun outcome(value: OperationTerminalOutcome): Unit = when (value) {
        is OperationTerminalOutcome.Succeeded -> {
            output.writeByte(1)
            result(value.result)
        }
        is OperationTerminalOutcome.Failed -> {
            output.writeByte(2)
            string(value.operationId.value, 128)
            string(value.safeMessage, 512)
            output.writeByte(if (value.retryable) 1 else 0)
            optional(value.backendCode) { string(it, 128) }
            optional(value.correlationId) { string(it, 128) }
        }
        is OperationTerminalOutcome.Expired -> {
            output.writeByte(3)
            string(value.operationId.value, 128)
        }
    }

    fun result(value: BackendOperationResult): Unit = when (value) {
        is BackendOperationResult.SendBtc -> {
            output.writeByte(1)
            string(value.operationId.value, 128)
            string(value.txid.hex, 64)
        }
        is BackendOperationResult.CreateUtxos -> {
            output.writeByte(2)
            string(value.operationId.value, 128)
            string(value.result.txid.hex, 64)
            output.writeInt(value.result.createdUtxoCount)
        }
        is BackendOperationResult.OnchainSend -> {
            output.writeByte(if (value.result.batchTransferIdx == null) 4 else 3)
            string(value.operationId.value, 128)
            string(value.result.txid.hex, 64)
            value.result.batchTransferIdx?.let(output::writeLong)
            Unit
        }
    }
}

private class CheckpointReader(private val input: DataInputStream) {
    fun string(maximumBytes: Int): String {
        val length = input.readInt()
        if (length < 0 || length > maximumBytes || length > input.available()) {
            throw IOException("invalid checkpoint field length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    fun boolean(): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IOException("invalid checkpoint boolean")
    }

    fun <T> optional(read: () -> T): T? = if (boolean()) read() else null

    fun operationKind(): OperationKind {
        val wire = string(32)
        return OperationKind.entries.singleOrNull { it.wireValue == wire }
            ?: throw IOException("unknown operation kind")
    }

    fun phase(): OperationPhase {
        val wire = string(32)
        return OperationPhase.entries.singleOrNull { it.wireValue == wire }
            ?: throw IOException("unknown operation phase")
    }

    fun prepared(): PreparedTransaction = PreparedTransaction(
        operationId = OperationId.of(string(128)),
        idempotencyKey = IdempotencyKey.of(string(128)),
        requestHash = RequestHash.ofLowercaseHex(string(64)),
        network = network(),
        operation = operationKind(),
        psbtBase64 = string(5_400_000),
        expiresAt = UnixSeconds.of(input.readLong()),
        createUtxosOutputPlan = optional(::outputPlan),
        correlationId = optional { string(128) },
    )

    fun outputPlan(): CreateUtxosOutputPlan {
        val size = input.readInt()
        if (size !in 1..256) throw IOException("invalid checkpoint output count")
        return CreateUtxosOutputPlan(
            List(size) {
                ExpectedWalletOutput(
                    account = accountKind(),
                    keychain = keychain(),
                    index = input.readInt(),
                    amount = Satoshis.of(input.readLong()),
                )
            },
        )
    }

    fun signed(): SignedOperationPayload = SignedOperationPayload(
        signedPsbtBase64 = string(5_400_000),
        validation = validation(),
    )

    fun validation(): LocalValidationReport = LocalValidationReport(
        psbtSha256 = string(64),
        intentSha256 = string(64),
        unsignedTxid = TransactionId.of(string(64)),
        totalInput = Satoshis.of(input.readLong()),
        totalOutput = Satoshis.of(input.readLong()),
        fee = Satoshis.of(input.readLong()),
        estimatedVbytes = input.readLong(),
        feeRate = FeeRate.milliSatsPerVbyte(input.readLong()),
        externalOutput = Satoshis.of(input.readLong()),
        change = Satoshis.of(input.readLong()),
        inputCount = input.readInt(),
        outputCount = input.readInt(),
        opReturnCount = input.readInt(),
    )

    fun outcome(): OperationTerminalOutcome = when (input.readUnsignedByte()) {
        1 -> OperationTerminalOutcome.Succeeded(result())
        2 -> OperationTerminalOutcome.Failed(
            operationId = OperationId.of(string(128)),
            safeMessage = string(512),
            retryable = boolean(),
            backendCode = optional { string(128) },
            correlationId = optional { string(128) },
        )
        3 -> OperationTerminalOutcome.Expired(OperationId.of(string(128)))
        else -> throw IOException("unknown terminal outcome")
    }

    fun result(): BackendOperationResult = when (input.readUnsignedByte()) {
        1 -> BackendOperationResult.SendBtc(
            OperationId.of(string(128)),
            TransactionId.of(string(64)),
        )
        2 -> BackendOperationResult.CreateUtxos(
            OperationId.of(string(128)),
            CreateUtxosResult(
                txid = TransactionId.of(string(64)),
                createdUtxoCount = input.readInt(),
            ),
        )
        3 -> BackendOperationResult.OnchainSend(
            OperationId.of(string(128)),
            OnchainSendResponse(
                txid = TransactionId.of(string(64)),
                batchTransferIdx = input.readLong(),
            ),
        )
        4 -> BackendOperationResult.OnchainSend(
            OperationId.of(string(128)), OnchainSendResponse(TransactionId.of(string(64))),
        )
        else -> throw IOException("unknown operation result")
    }

    private fun network(): Network {
        val wire = string(32)
        return Network.entries.singleOrNull { it.wireValue == wire }
            ?: throw IOException("unknown network")
    }

    private fun accountKind(): AccountKind {
        val wire = string(32)
        return AccountKind.entries.singleOrNull { it.wireValue == wire }
            ?: throw IOException("unknown account kind")
    }

    private fun keychain(): Keychain {
        val wire = string(32)
        return Keychain.entries.singleOrNull { it.wireValue == wire }
            ?: throw IOException("unknown keychain")
    }
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

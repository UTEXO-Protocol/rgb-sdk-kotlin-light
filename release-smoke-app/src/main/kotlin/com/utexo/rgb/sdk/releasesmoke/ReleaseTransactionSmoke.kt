@file:OptIn(com.utexo.rgb.sdk.WalletInfrastructureApi::class)

package com.utexo.rgb.sdk.releasesmoke

import android.util.Base64
import com.utexo.rgb.sdk.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking

/** Fixed public-vector PSBT, nonexistent prevout, no transport and no broadcast. */
public object ReleaseTransactionSmoke {
    public fun signsAndFinalizesThroughPackagedFacade(): Boolean = runBlocking {
        val mnemonic = SecretMnemonic.fromWords(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".toCharArray(),
        )
        mnemonic.use {
            var submitted = false
            val backend = object : WalletBackend {
                override suspend fun preflightOperation(operation: OperationKind) { check(operation == OperationKind.SEND_BTC) }
                override suspend fun initialize(request: BackendInitializeRequest) = BackendSession("release-test", 1, Network.MAINNET)
                override suspend fun registerWallet(request: RegisterWalletRequest) { check(request.identity.masterFingerprint == "73c5da0a") }
                override suspend fun shutdown(session: BackendSession) { }
                override suspend fun prepareSendBtc(request: PrepareSendBtcRequest) = PreparedTransaction(
                    OperationId.of("op_test_001"), request.context.idempotencyKey, request.context.requestHash,
                    Network.MAINNET, OperationKind.SEND_BTC, FIXTURE_PSBT, UnixSeconds.of(2_000_000_000),
                )
                override suspend fun submitSignedTransaction(request: SignedTransactionSubmission): BackendOperationResult {
                    check(!submitted)
                    check(request.validation.fee.value == 500L)
                    check(request.validation.unsignedTxid.hex == EXPECTED_TXID)
                    val stream = ByteArrayInputStream(Base64.decode(request.signedPsbtBase64, Base64.NO_WRAP))
                    check(stream.skip(5) == 5L)
                    readMap(stream)
                    val input = readMap(stream)
                    check(input.keys == setOf(0x01, 0x08))
                    val witness = input.getValue(0x08)
                    check(witness.size == 66 && witness[0] == 1.toByte() && witness[1] == 64.toByte())
                    submitted = true
                    return BackendOperationResult.SendBtc(request.operationId, request.validation.unsignedTxid)
                }
            }
            val wallet = UTEXOWallet(
                WalletConfiguration(WalletId.of("packaged-signing-smoke"), Network.MAINNET),
                backend, WalletKeyProvider { deriveSeedFromMnemonic(mnemonic) }, InMemoryOperationJournal(),
            )
            try {
                wallet.init()
                wallet.unlock()
                val request = SendBtcRequest(
                    BitcoinAddress.of("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"),
                    Satoshis.of(30_000), FeeRate.satPerVbyte(100.0), idempotencyKey = IdempotencyKey.of("idem_test_001"),
                )
                check(wallet.sendBtc(request).hex == EXPECTED_TXID)
                check(wallet.sendBtc(request).hex == EXPECTED_TXID) // Local receipt; never a second submit.
                submitted
            } finally { wallet.destroy() }
        }
    }

    private fun readMap(input: ByteArrayInputStream): Map<Int, ByteArray> {
        fun size(): Int = when (val first = input.read()) {
            in 0..252 -> first
            253 -> input.read() or (input.read() shl 8)
            else -> error("Invalid fixed fixture")
        }
        val fields = mutableMapOf<Int, ByteArray>()
        while (true) {
            val keySize = size()
            if (keySize == 0) return fields
            val key = ByteArray(keySize).also { check(input.read(it) == keySize) }
            val valueSize = size()
            val value = ByteArray(valueSize).also { check(input.read(it) == valueSize) }
            check(fields.put(key[0].toInt() and 0xff, value) == null)
        }
    }

    private const val EXPECTED_TXID = "ec75ccb05c23b9be0b1f487b704071066e62550072ce1b36fee0576177ce336a"
    private const val FIXTURE_PSBT = "cHNidP8BAIkCAAAAAQcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHAAAAAAD9////AjB1AAAAAAAAIlEgqC8plE1luGrmteXMdeKU6tbFk5Gh7cXgFuNJjGf8e7t8DwEAAAAAACJRIIgtdOXQVy1agWzvAEGpa2wd6DL2+WdtlgXETV6al9PcAAAAAAABASughgEAAAAAACJRIKYIafDbzx3GWcnOy6+AUBNeqejNxIcFPx3GiAlJ3GhMIRbMikvGTYl73cX7wvZw96i6CzhneRBs8SI8b8XXzW/BFRkAc8XaClYAAIAAAACAAAAAgAAAAAAAAAAAARcgzIpLxk2Je93F+8L2cPeougs4Z3kQbPEiPG/F181vwRUAAAEFIDmfGy9Dk/KaGMk3hZxd2KdzUBAxV+uIDwLowIIUJ3zvIQc5nxsvQ5PymhjJN4WcXdinc1AQMVfriA8C6MCCFCd87xkAc8XaClYAAIAAAACAAAAAgAEAAAAAAAAAAA=="
}

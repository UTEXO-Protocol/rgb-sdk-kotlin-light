package com.utexo.rgb.sdk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** RN domain behavior, without reproducing its bridge truncation or placeholder bugs. */
internal class RnFacadeContractTest {
    @Test
    fun `create count respects native u8 domain independently of PSBT output limit`(): Unit {
        assertEquals(null, CreateUtxosRequest().num)
        assertEquals(1, CreateUtxosRequest(num = 1).num)
        assertEquals(255, CreateUtxosRequest(num = 255).num)
        assertEquals(256, LocalSigningPolicy().maximumOutputs)
        for (invalid in listOf(-1, 0, 256, Int.MAX_VALUE)) {
            assertFailsWith<SdkException.InvalidArgument> { CreateUtxosRequest(num = invalid) }
            assertFailsWith<SdkException.InvalidArgument> { CreateUtxosRequest(num = 255).copy(num = invalid) }
        }
        assertEquals(4_294_967_295L, CreateUtxosRequest(size = Satoshis.of(4_294_967_295L)).size?.value)
        for (invalid in listOf(0L, 4_294_967_296L, Satoshis.MAX_VALUE)) {
            assertFailsWith<SdkException.InvalidArgument> { CreateUtxosRequest(size = Satoshis.of(invalid)) }
            assertFailsWith<SdkException.InvalidArgument> { CreateUtxosRequest().copy(size = Satoshis.of(invalid)) }
        }
    }

    @Test
    fun `fee targets are bounded before backend and forwarded exactly`(): Unit = runBlocking {
        val calls = mutableListOf<Int>()
        val backend = object : FakeBackend() {
            override suspend fun estimateFeeRate(context: BackendContext, targetBlocks: BlockCount): FeeEstimate {
                calls += targetBlocks.value
                return FeeEstimate(FeeRate.satPerVbyte("1.5"), targetBlocks)
            }
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()
        try {
            for (invalid in listOf(0, 1_009, Int.MAX_VALUE)) {
                assertFailsWith<SdkException.InvalidArgument> { wallet.estimateFeeRate(BlockCount.of(invalid)) }
            }
            assertEquals(emptyList(), calls)
            for (valid in listOf(1, 1_008)) {
                val estimate = wallet.estimateFeeRate(BlockCount.of(valid))
                assertEquals(valid, estimate.targetBlocks.value)
                assertEquals(1_500L, estimate.feeRate.milliSatsPerVbyte)
            }
            assertEquals(listOf(1, 1_008), calls)
        } finally {
            wallet.destroy()
        }
    }

    @Test
    fun `fee response cannot silently substitute another target`(): Unit = runBlocking {
        val backend = object : FakeBackend() {
            override suspend fun estimateFeeRate(context: BackendContext, targetBlocks: BlockCount): FeeEstimate =
                FeeEstimate(FeeRate.satPerVbyte("2"), BlockCount.of(2))
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()
        try {
            assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.estimateFeeRate(BlockCount.of(1)) }
        } finally {
            wallet.destroy()
        }
    }

    @Test
    fun `transfer alias forwards asset and default txid filter uses complete all asset snapshot`(): Unit = runBlocking {
        val asset = AssetId.of("asset-fixture")
        val calls = mutableListOf<AssetId?>()
        val anotherTxid = TransactionId.of("b".repeat(64))
        val transfers = mutableListOf(
            transfer(1, TEST_TXID), transfer(2, null), transfer(3, anotherTxid), transfer(4, TEST_TXID),
        )
        val backend = object : FakeBackend() {
            override suspend fun listTransfers(context: BackendContext, assetId: AssetId?): List<Transfer> {
                calls += assetId
                return transfers
            }
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()
        try {
            val alias = wallet.listOnchainTransfers(asset)
            assertEquals(transfers, alias)
            val matches = wallet.listTransfersByTxid(TEST_TXID)
            assertEquals(listOf(1L, 4L), matches.map { it.idx })
            assertEquals(listOf(asset, null), calls)
            transfers.clear()
            assertEquals(4, alias.size)
            assertEquals(2, matches.size)
        } finally {
            wallet.destroy()
        }
    }

    @Test
    fun `transfer filter cannot turn absent complete history into an empty result`(): Unit = runBlocking {
        val wallet = testWallet(FakeBackend(), FakeSigningEngine())
        wallet.init()
        wallet.unlock()
        try {
            assertFailsWith<SdkException.Unsupported> { wallet.listTransfersByTxid(TEST_TXID) }
            assertFailsWith<SdkException.Unsupported> { wallet.listOnchainTransfers() }
        } finally {
            wallet.destroy()
        }
    }

    @Test
    fun `transaction default filters full synchronized history without ignoring skipSync`(): Unit = runBlocking {
        var calls = 0
        val transactions = listOf(TEST_TXID, TransactionId.of("b".repeat(64)), TEST_TXID).map {
            Transaction(TransactionType.USER, it, Satoshis.of(100), Satoshis.ZERO, Satoshis.ZERO)
        }
        val backend = object : FakeBackend() {
            override suspend fun listTransactions(context: BackendContext): List<Transaction> {
                calls++
                return transactions
            }
        }
        val wallet = testWallet(backend, FakeSigningEngine())
        wallet.init()
        wallet.unlock()
        try {
            assertEquals(listOf(transactions[0], transactions[2]), wallet.listTransactionsByTxid(TEST_TXID))
            assertEquals(1, calls)
            assertFailsWith<SdkException.Unsupported> { wallet.listTransactionsByTxid(TEST_TXID, skipSync = true) }
            assertEquals(1, calls)
        } finally {
            wallet.destroy()
        }
        val unsupportedWallet = testWallet(FakeBackend(), FakeSigningEngine())
        unsupportedWallet.init()
        unsupportedWallet.unlock()
        try {
            assertFailsWith<SdkException.Unsupported> { unsupportedWallet.listTransactionsByTxid(TEST_TXID) }
        } finally {
            unsupportedWallet.destroy()
        }
    }

    @Test
    fun `receive address only returns after correct scope and local ownership verification`(): Unit = runBlocking {
        var reservation = ReservedAddress(TEST_ADDRESS, AccountKind.VANILLA, Keychain.EXTERNAL, 0)
        val requests = mutableListOf<ReserveAddressRequest>()
        val backend = object : FakeBackend() {
            override suspend fun reserveAddress(request: ReserveAddressRequest): ReservedAddress {
                requests += request
                return reservation
            }
        }
        val signer = FakeSigningEngine()
        val wallet = testWallet(backend, signer)
        wallet.init()
        wallet.unlock()
        try {
            assertEquals(TEST_ADDRESS, wallet.getAddress())
            assertEquals(TEST_ADDRESS, wallet.rotateVanillaAddress())
            assertEquals(listOf(false, true), requests.map { it.rotate })
            assertEquals(2, signer.addressChecks)
            reservation = reservation.copy(account = AccountKind.COLORED)
            assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.getAddress() }
            reservation = reservation.copy(account = AccountKind.VANILLA, keychain = Keychain.CHANGE)
            assertFailsWith<SdkException.BackendProtocolMismatch> { wallet.getAddress() }
            assertEquals(2, signer.addressChecks)
        } finally {
            wallet.destroy()
        }
    }

    private fun transfer(idx: Long, txid: TransactionId?): Transfer = Transfer(
        idx = idx,
        batchTransferIdx = 42,
        createdAt = UnixSeconds.of(TEST_NOW),
        updatedAt = UnixSeconds.of(TEST_NOW),
        status = TransferStatus.SETTLED,
        assignments = listOf(Assignment(AssignmentType.FUNGIBLE, AssetAmount.of(10))),
        kind = TransferKind.RECEIVE_WITNESS,
        txid = txid,
    )
}

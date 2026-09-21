package com.utexo.rgb.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Base64
import com.utexo.rgb.sdk.internal.LocalOperationIntent
import com.utexo.rgb.sdk.internal.OperationCoordinator
import com.utexo.rgb.sdk.internal.RequestHasher
import com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory
import com.utexo.rgb.sdk.internal.InvoiceEncodedState
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Log
import java.security.KeyStore
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class NativeBindingSmokeTest {
    @Test
    public fun packagedInvoiceParserPreservesRawStateAndRejectsMalformedEncodings(): Unit = runBlocking {
        val recipient = "bcrt:utxob:tjVmHbI2-U0_umHn-bU4cmP6-l3VW00H-ewoi2uz-XZG6O3i-wUFBW"
        val nia = "RWhwUfTMpuP2Zfx1~j4nswCANGeJrYOqDcKelaMV4zU"
        val ifa = "IpjJhFLz3oywYKQxO3KmFgR0Aa415nlTNrNyEFqMZCE"
        SecretSeed.fromBytes(ByteArray(64) { 31 }).use { seed ->
            UniFfiLocalSigningEngineFactory.open(Network.REGTEST, seed).use { signer ->
                check(signer.capabilities.localRgbInvoiceParsing && !signer.capabilities.localRgbValidation)
                val hidden = signer.decodeRgbInvoice(RgbInvoice.of("rgb:~/~/BF/$recipient"))
                assertEquals(AssignmentType.ANY, hidden.data.assignment.type)
                assertEquals(null, hidden.data.assignment.amount)
                assertEquals(InvoiceEncodedState.AMOUNT, hidden.encodedState)
                assertEquals(AssetAmount.of(100), hidden.encodedAmount)
                check(!hidden.witness)
                val omitted = signer.decodeRgbInvoice(RgbInvoice.of("rgb:~/$nia/~/$recipient?expiry=1"))
                assertEquals(Assignment(AssignmentType.FUNGIBLE, AssetAmount.ZERO), omitted.data.assignment)
                assertEquals(InvoiceEncodedState.OMITTED, omitted.encodedState)
                assertEquals(UnixSeconds.of(1), omitted.data.expirationTimestamp) // Expired values remain inspectable.
                val maximum = signer.decodeRgbInvoice(RgbInvoice.of("rgb:~/$ifa/ZZZZZZZZZZZZY/$recipient?assignment_name=inflationAllowance"))
                assertEquals(AssetAmount.parse(AssetAmount.MAX_VALUE), maximum.data.assignment.amount)
                assertEquals(maximum.data.assignment.amount, maximum.encodedAmount)
                val valid = "rgb:~/~/~/$recipient"
                for (malformed in listOf(
                    valid.replace("rgb:", "RGB:"), valid.replace("bcrt:", "tb:"), valid.dropLast(1) + "A",
                    "$valid?expiry=-1", "$valid?expiry=1&expiry=2", "$valid?unknown=value",
                    "$valid?endpoints=ftp://example.com", "$valid?endpoints=rpc://host%20name",
                )) {
                    var rejected = false
                    try { signer.decodeRgbInvoice(RgbInvoice.of(malformed)) }
                    catch (_: SdkException.InvalidArgument) { rejected = true }
                    check(rejected)
                }
            }
        }
    }

    @Test
    public fun seedCustodyRejectsUnsafeFileLinksOrPlatformPreventsTheirCreation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "utexo-rgb-sdk").apply { check(exists() || mkdirs()) }
        for (suffix in listOf("", ".lock", ".tmp")) {
            for (hardlink in listOf(false, true)) {
                val walletId = WalletId.of("seed-path-${UUID.randomUUID()}")
                val policy = AndroidKeystoreSeedStoreConfiguration()
                val identity = SeedStoreIdentity(context.packageName, walletId.value, Network.REGTEST.wireValue, policy.policyId())
                val target = File(directory, identity.fileName() + suffix)
                val untouched = File(context.cacheDir, "seed-path-external-${UUID.randomUUID()}")
                untouched.writeBytes(byteArrayOf(1, 2, 3))
                var linkCreated = false
                try {
                    try {
                        if (hardlink) Os.link(untouched.absolutePath, target.absolutePath)
                        else Os.symlink(untouched.absolutePath, target.absolutePath)
                    } catch (failure: ErrnoException) {
                        // Some Android SELinux policies deny app-created hardlinks.
                        // That is platform prevention, not evidence that the SDK
                        // inspected a hardlink. JVM tests separately create them.
                        if (!hardlink || failure.errno !in listOf(OsConstants.EACCES, OsConstants.EPERM)) throw failure
                        check(!target.exists())
                        check(untouched.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
                        Log.i("UTEXO_SDK_TEST", "Hardlink fixture prevented by platform policy: suffix=$suffix errno=${failure.errno}")
                        continue
                    }
                    linkCreated = true
                    var rejected = false
                    SecretSeed.fromBytes(ByteArray(64) { 17 }).use { seed ->
                        try { AndroidKeystoreSeedStore(context, walletId, Network.REGTEST).provision(seed) }
                        catch (_: SdkException.KeyAccessFailed) { rejected = true }
                    }
                    check(rejected)
                    check(untouched.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
                    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    // A temporary-path validation failure occurs after encryption; the key
                    // remains for explicit recovery, never automatically regenerated/deleted.
                    if (suffix != ".tmp") check(!keys.containsAlias(identity.keyAlias()))
                    if (keys.containsAlias(identity.keyAlias())) keys.deleteEntry(identity.keyAlias())
                } finally {
                    if (linkCreated) check(target.delete()) { "Could not remove own test link: suffix=$suffix hardlink=$hardlink" }
                    check(untouched.delete())
                    File(directory, identity.fileName() + ".lock").delete()
                }
            }
        }
    }
    @Test
    public fun retainedSignatureIsRevalidatedAgainstEveryCurrentPolicyLimit(): Unit = runBlocking {
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("native-signing-multi-smoke.json").bufferedReader().use { it.readText() })
        SecretMnemonic.fromWords(fixture.getString("mnemonic").toCharArray()).use { mnemonic ->
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                UniFfiLocalSigningEngineFactory.open(Network.MAINNET, seed).use { signer ->
                    val wallet = WalletConfiguration(WalletId.of("native-retained-policy"), Network.MAINNET)
                    val request = SendBtcRequest(
                        BitcoinAddress.of(fixture.getString("recipient")), Satoshis.of(30_000),
                        FeeRate.satPerVbyte(100.0), idempotencyKey = IdempotencyKey.of("idem_test_001"),
                    )
                    val prepared = PreparedTransaction(
                        OperationId.of("op_test_001"), request.idempotencyKey,
                        RequestHasher.sendBtc(Network.MAINNET, request), Network.MAINNET, OperationKind.SEND_BTC,
                        fixture.getString("psbt"), UnixSeconds.of(2_000_000_000),
                    )
                    val signed = signer.validateAndSign(prepared, LocalOperationIntent.SendBtc(Network.MAINNET, wallet.signingPolicy, request))
                    assertEquals(fixture.getString("txid"), signed.validation.unsignedTxid.hex)
                    // The internal adapter independently binds caller context, even
                    // when invoked directly without the public coordinator's checks.
                    for ((key, hash, network) in listOf(
                        Triple(IdempotencyKey.of("different-caller-key"), prepared.requestHash, prepared.network),
                        Triple(prepared.idempotencyKey, RequestHash.ofLowercaseHex("d".repeat(64)), prepared.network),
                        Triple(prepared.idempotencyKey, prepared.requestHash, Network.REGTEST),
                    )) {
                        val mismatched = PreparedTransaction(
                            prepared.operationId, key, hash, network, prepared.operation,
                            prepared.psbtBase64, prepared.expiresAt,
                        )
                        val intent = LocalOperationIntent.SendBtc(Network.MAINNET, wallet.signingPolicy, request)
                        for (revalidate in listOf(false, true)) {
                            var rejected = false
                            try {
                                if (revalidate) signer.validateRetainedSigning(mismatched, intent)
                                else signer.validateAndSign(mismatched, intent)
                            } catch (failure: SdkException) {
                                if (network != prepared.network) {
                                    check(failure is SdkException.InternalInvariantViolation)
                                } else {
                                    check(failure is SdkException.PolicyRejected)
                                }
                                rejected = true
                            }
                            check(rejected) { "Direct native adapter accepted unrelated caller context" }
                        }
                    }
                    val retained = OperationCheckpoint(
                        wallet.walletId, request.idempotencyKey, prepared.requestHash, OperationKind.SEND_BTC,
                        OperationPhase.SIGNED, prepared.operationId, prepared,
                        SignedOperationPayload(signed.signedPsbtBase64, signed.validation),
                    )
                    var submitted = 0
                    val backend = object : WalletBackend {
                        override suspend fun preflightOperation(operation: OperationKind) { }
                        override suspend fun initialize(request: BackendInitializeRequest): BackendSession = error("not used")
                        override suspend fun registerWallet(request: RegisterWalletRequest): Unit = error("not used")
                        override suspend fun shutdown(session: BackendSession) { }
                        override suspend fun submitSignedTransaction(request: SignedTransactionSubmission): BackendOperationResult {
                            submitted++
                            assertEquals(signed.signedPsbtBase64, request.signedPsbtBase64)
                            return BackendOperationResult.SendBtc(request.operationId, request.validation.unsignedTxid)
                        }
                    }
                    val context = BackendContext(BackendSession("test", 1, Network.MAINNET), wallet.walletId, Network.MAINNET)
                    for (policy in listOf(
                        LocalSigningPolicy(maximumInputs = 1), LocalSigningPolicy(maximumOutputs = 1),
                        LocalSigningPolicy(maximumChildIndex = 1), LocalSigningPolicy(maximumAbsoluteFee = Satoshis.of(499)),
                        LocalSigningPolicy(maximumPsbtBytes = 100), LocalSigningPolicy(maximumFeeRate = FeeRate.satPerVbyte(1.0)),
                    )) {
                        val journal = InMemoryOperationJournal().also { it.save(retained) }
                        var rejected = false
                        try { OperationCoordinator(wallet.copy(signingPolicy = policy), backend, journal).sendBtc(context, signer, request) }
                        catch (_: SdkException.PolicyRejected) { rejected = true }
                        check(rejected) { "Current native policy must reject retained signature" }
                        assertEquals(retained, journal.load(wallet.walletId, request.idempotencyKey))
                    }
                    assertEquals(0, submitted)
                    val journal = InMemoryOperationJournal().also { it.save(retained) }
                    assertEquals(signed.validation.unsignedTxid, OperationCoordinator(wallet, backend, journal).sendBtc(context, signer, request))
                    assertEquals(1, submitted)
                }
            }
        }
    }
    @Test
    public fun packagedSignerProducesFinalizedTaprootWitness(): Unit = runBlocking {
        // Shared fixture native-signing-smoke.json: public BIP86 vector, nonexistent prevout.
        val mnemonic = SecretMnemonic.fromWords(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".toCharArray(),
        )
        mnemonic.use {
            deriveSeedFromMnemonic(it).use { seed ->
                UniFfiLocalSigningEngineFactory.open(Network.MAINNET, seed).use { signer ->
                    val request = SendBtcRequest(
                        BitcoinAddress.of("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"),
                        Satoshis.of(30_000), FeeRate.satPerVbyte(100.0),
                        idempotencyKey = IdempotencyKey.of("idem_test_001"),
                    )
                    val prepared = PreparedTransaction(
                        OperationId.of("op_test_001"), request.idempotencyKey,
                        RequestHasher.sendBtc(Network.MAINNET, request), Network.MAINNET, OperationKind.SEND_BTC,
                        "cHNidP8BAIkCAAAAAQcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHAAAAAAD9////AjB1AAAAAAAAIlEgqC8plE1luGrmteXMdeKU6tbFk5Gh7cXgFuNJjGf8e7t8DwEAAAAAACJRIIgtdOXQVy1agWzvAEGpa2wd6DL2+WdtlgXETV6al9PcAAAAAAABASughgEAAAAAACJRIKYIafDbzx3GWcnOy6+AUBNeqejNxIcFPx3GiAlJ3GhMIRbMikvGTYl73cX7wvZw96i6CzhneRBs8SI8b8XXzW/BFRkAc8XaClYAAIAAAACAAAAAgAAAAAAAAAAAARcgzIpLxk2Je93F+8L2cPeougs4Z3kQbPEiPG/F181vwRUAAAEFIDmfGy9Dk/KaGMk3hZxd2KdzUBAxV+uIDwLowIIUJ3zvIQc5nxsvQ5PymhjJN4WcXdinc1AQMVfriA8C6MCCFCd87xkAc8XaClYAAIAAAACAAAAAgAEAAAAAAAAAAA==",
                        UnixSeconds.of(2_000_000_000),
                    )
                    val result = signer.validateAndSign(
                        prepared, LocalOperationIntent.SendBtc(Network.MAINNET, LocalSigningPolicy(), request),
                    )
                    assertEquals("ec75ccb05c23b9be0b1f487b704071066e62550072ce1b36fee0576177ce336a", result.validation.unsignedTxid.hex)
                    assertEquals(500L, result.validation.fee.value)
                    val stream = ByteArrayInputStream(Base64.decode(result.signedPsbtBase64, Base64.NO_WRAP))
                    assertEquals(5L, stream.skip(5))
                    readPsbtMap(stream) // global map
                    val input = readPsbtMap(stream)
                    assertEquals(setOf(0x01, 0x08), input.keys)
                    val witness = input.getValue(0x08)
                    assertEquals(66, witness.size)
                    assertEquals(1, witness[0].toInt())
                    assertEquals(64, witness[1].toInt())
                }
            }
        }
    }

    private fun readPsbtMap(input: ByteArrayInputStream): Map<Int, ByteArray> {
        val fields = mutableMapOf<Int, ByteArray>()
        fun compactSize(): Int = when (val first = input.read()) {
            in 0..252 -> first
            253 -> input.read() or (input.read() shl 8)
            else -> error("Unexpected compact-size in fixed fixture")
        }
        while (true) {
            val length = compactSize()
            if (length == 0) return fields
            val key = ByteArray(length)
            assertEquals(length, input.read(key))
            val valueLength = compactSize()
            val value = ByteArray(valueLength)
            assertEquals(valueLength, input.read(value))
            fields[key[0].toInt() and 0xff] = value
        }
    }

    @Test
    public fun generatedBindingLoadsPackagedLibraryAndMatchesRnVector(): Unit = runBlocking {
        val mnemonic = SecretMnemonic.fromWords(
            "flight seminar tray bulb level embody switch enhance august deny scene dismiss"
                .toCharArray(),
        )
        try {
            deriveKeysFromMnemonic(Network.TESTNET, mnemonic).use { keys ->
                assertEquals("42424232", keys.masterFingerprint)
                assertEquals(
                    "tpubD6NzVbkrYhZ4WLw9njYmLvMEWp5nqVmjfkgAZfj7uivNXCFS7Ls9DyDZM82P4cDpobCDGAZpNsR9Gs9wuvv5yYCNFsqxcbsDn6VU4nU3ZcW",
                    keys.xpub,
                )
                assertEquals(
                    "tpubDDTg3fRGqAvEvshRUUwuS8brXkewNsE6y6Jbb9xZcuzBbmxAWa3UCmwyQG4peM9RwjgY8BDwuRVRU5KGtvRby5kiv1dk13YHU8z39o18QJK",
                    keys.accountXpubVanilla,
                )
                assertEquals(
                    "tpubDDqoEexZxewBLjXmZ7kxSHgZgmdAtej6DrXLBMiSSuPCdGQqjkTvyETLLVY7qNpxNRGUivvxDj8sswHHihT5effNNDmsbMUX2Lrpy4zjRcS",
                    keys.accountXpubColored,
                )
            }
            deriveSeedFromMnemonic(mnemonic).use { seed ->
                val bytes = seed.copyForNative()
                try {
                    assertEquals(64, bytes.size)
                } finally {
                    bytes.fill(0.toByte())
                }
            }
        } finally {
            mnemonic.close()
        }
    }
}

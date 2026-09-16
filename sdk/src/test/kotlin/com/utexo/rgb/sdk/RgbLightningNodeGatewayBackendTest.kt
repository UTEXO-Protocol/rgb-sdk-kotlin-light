package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.OperationCoordinator
import com.utexo.rgb.sdk.internal.RequestHasher
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class RgbLightningNodeGatewayBackendTest {
    @Test
    fun `configuration requires TLS except explicit local development and redacts token`(): Unit {
        assertFailsWith<SdkException.InvalidArgument> {
            gatewayConfiguration(baseUrl = "http://gateway.example")
        }
        val local = gatewayConfiguration(
            baseUrl = "http://127.0.0.1:8480",
            allowInsecureLocalDevelopment = true,
        )
        assertEquals("http://127.0.0.1:8480", local.baseUrl)
        assertFalse(local.allowUnverifiedPsbtProfile)
        assertFalse(local.toString().contains(TEST_TOKEN))
        assertTrue(local.toString().contains("[REDACTED]"))
        assertFailsWith<SdkException.Unsupported> {
            RgbLightningNodeGatewayConfiguration(
                baseUrl = "https://gateway.example",
                expectedNetwork = Network.UTEXO,
                bearerToken = TEST_TOKEN,
            )
        }
    }

    @Test
    fun `unverified PSBT profile is default off before journal and transport`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(
            interceptor = transport,
            allowUnverifiedPsbtProfile = false,
        )
        val journal = InMemoryOperationJournal()
        val context = BackendContext(
            session = BackendSession("inactive-session", 1, Network.SIGNET),
            walletId = TEST_WALLET_ID,
            network = Network.SIGNET,
        )
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        assertFailsWith<SdkException.PolicyRejected> {
            coordinator.sendBtc(
                context,
                FakeSigningEngine(),
                sendBtcRequest().copy(feeRate = FeeRate.satPerVbyte(101.0)),
            )
        }
        val error = assertFailsWith<SdkException.Unsupported> {
            coordinator.sendBtc(context, FakeSigningEngine(), sendBtcRequest())
        }
        assertEquals(
            "sendBtc requires an explicitly enabled, unverified gateway PSBT profile",
            error.details["feature"],
        )
        assertEquals(null, journal.load(TEST_WALLET_ID, TEST_IDEMPOTENCY_KEY))
        assertTrue(transport.requests.isEmpty())

        assertFailsWith<SdkException.Unsupported> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(operationContext(context), sendBtcRequest()),
            )
        }
        assertFailsWith<SdkException.Unsupported> {
            backend.submitSignedTransaction(
                signedSubmission(
                    context,
                    OperationId.of("123e4567-e89b-42d3-a456-426614174000"),
                    TEST_PSBT_BASE64,
                ),
            )
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `disabled profile leaves recovered signed checkpoint unchanged`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest().copy(
            idempotencyKey = IdempotencyKey.of("signed-profile-off"),
        )
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SIGNED)
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        assertFailsWith<SdkException.Unsupported> {
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request)
        }
        assertEquals(
            OperationPhase.SIGNED,
            journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `disabled profile still replays a terminal send checkpoint`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest()
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.TERMINAL)
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(
                maximumFeeRate = FeeRate.satPerVbyte(1.0),
            ),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        assertEquals(
            TEST_TXID,
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request),
        )
        assertEquals(OperationPhase.TERMINAL, journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `expired signed checkpoint terminalizes before disabled-profile gate`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest().copy(
            idempotencyKey = IdempotencyKey.of("expired-signed-profile-off"),
        )
        val checkpoint = persistedSendCheckpoint(
            request = request,
            phase = OperationPhase.SIGNED,
            expiresAt = UnixSeconds.of(TEST_NOW),
        )
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(
                maximumFeeRate = FeeRate.satPerVbyte(1.0),
            ),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        val error = assertFailsWith<SdkException.OperationExpired> {
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request)
        }
        assertEquals(checkpoint.operationId, error.operationId)
        assertEquals(
            OperationPhase.TERMINAL,
            journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `disabled profile reconciles submitting without another write`(): Unit = runBlocking {
        val transport = QueueInterceptor()
        val backend = testBackend(transport, allowUnverifiedPsbtProfile = false)
        val journal = InMemoryOperationJournal()
        val request = sendBtcRequest().copy(
            idempotencyKey = IdempotencyKey.of("submitting-profile-off"),
        )
        val checkpoint = persistedSendCheckpoint(request, OperationPhase.SUBMITTING)
        journal.save(checkpoint)
        val coordinator = OperationCoordinator(
            configuration = gatewayWalletConfiguration(
                maximumFeeRate = FeeRate.satPerVbyte(1.0),
            ),
            backend = backend,
            journal = journal,
            now = EpochSecondsSource { TEST_NOW },
        )

        val error = assertFailsWith<SdkException.OperationAmbiguous> {
            coordinator.sendBtc(inactiveContext(), FakeSigningEngine(), request)
        }
        assertEquals(checkpoint.operationId, error.operationId)
        assertEquals(
            OperationPhase.SUBMITTING,
            journal.load(TEST_WALLET_ID, request.idempotencyKey)?.phase,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `production backend exposes only its policy-enforcing constructor`(): Unit {
        val constructors = RgbLightningNodeGatewayBackend::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) }
        assertEquals(1, constructors.size)
        assertEquals(
            listOf(RgbLightningNodeGatewayConfiguration::class.java),
            constructors.single().parameterTypes.toList(),
        )
    }

    @Test
    fun `initialization authenticates and registration sends watch-only keys`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(
                200,
                "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234," +
                    "\"futureMeField\":{\"ignored\":true}}",
            ),
            queuedResponse(
                201,
                "{\"fingerprint\":\"deadbeef\",\"address\":\"tb1pregisteredaddress\"," +
                    "\"futureRegistrationField\":42}",
            ),
        )
        val backend = testBackend(transport)
        val session = backend.initialize(initializeRequest())
        val context = BackendContext(session, TEST_WALLET_ID, Network.SIGNET)
        backend.registerWallet(RegisterWalletRequest(context, gatewayIdentity()))

        assertEquals(2, transport.requests.size)
        assertEquals("/v1/me", transport.requests[0].url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", transport.requests[0].header("Authorization"))
        val registration = transport.requests[1]
        assertEquals("/v1/wallet/xpubs", registration.url.encodedPath)
        val body = registration.bodyUtf8()
        assertTrue(body.contains("\"vanilla\":\"${"t".repeat(111)}\""))
        assertTrue(body.contains("\"colored\":\"${"u".repeat(111)}\""))
        assertFalse(body.contains("mnemonic", ignoreCase = true))
        assertFalse(body.contains("xpriv", ignoreCase = true))
    }

    @Test
    fun `balances map exact SDK semantics and tolerate additive gateway fields`(): Unit = runBlocking {
        val balanceBody = """
            {
              "btc": {
                "vanilla": {"settled":1000,"future":1200,"spendable":900,"reserved":5},
                "colored": {"settled":2000,"future":2200,"spendable":1800,"reserved":7},
                "futureBtcField": true
              },
              "assets": [{
                "assetId":"rgb:asset-1",
                "schema":"NIA",
                "ticker":"TOK",
                "name":"Token",
                "precision":2,
                "balance":{"settled":10,"future":12,"spendable":8,"reserved":1},
                "futureAssetField":"ignored"
              }],
              "futureRootField":{"ignored":true}
            }
        """.trimIndent()
        val transport = QueueInterceptor(
            queuedResponse(
                200,
                "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234," +
                    "\"futureMeField\":true}",
            ),
            queuedResponse(200, balanceBody),
            queuedResponse(200, balanceBody),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)

        val btc = backend.getBtcBalance(context)
        assertEquals(900L, btc.vanilla.spendable.value)
        assertEquals(1_800L, btc.colored.spendable.value)
        val asset = backend.getAssetBalance(context, AssetId.of("rgb:asset-1"))
        assertEquals("10", asset.settled?.decimal)
        assertEquals("8", asset.spendable?.decimal)
    }

    @Test
    fun `send BTC validates intent and uses distinct deterministic stage keys`(): Unit = runBlocking {
        val operationId = "123e4567-e89b-42d3-a456-426614174000"
        val preparedPsbt = "cHNidP8BAHECAAAAAAAAAAAA"
        val scriptHex = "5120${"00".repeat(32)}"
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                201,
                """
                    {
                      "opId":"$operationId",
                      "psbt":"$preparedPsbt",
                      "intent":{
                        "kind":"send_btc",
                        "feeRateSatPerVb":2,
                        "recipients":[{
                          "address":"${TEST_GATEWAY_ADDRESS.value}",
                          "scriptHex":"$scriptHex",
                          "amountSat":25000
                        }],
                        "asset":null,
                        "utxos":null
                      },
                      "expiresAt":2000123
                    }
                """.trimIndent(),
            ),
            queuedResponse(
                200,
                "{\"txid\":\"${TEST_TXID.hex}\",\"futureCompleteField\":true}",
            ),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )
        val send = SendBtcRequest(
            address = TEST_GATEWAY_ADDRESS,
            amount = Satoshis.of(25_000),
            feeRate = FeeRate.satPerVbyte(2.0),
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
        )

        val prepared = backend.prepareSendBtc(PrepareSendBtcRequest(operationContext, send))
        assertEquals(OperationKind.SEND_BTC, prepared.operation)
        assertEquals(2_000L, prepared.expiresAt.value)
        assertEquals(operationContext.requestHash, prepared.requestHash)

        val result = backend.submitSignedTransaction(
            SignedTransactionSubmission(
                context = operationContext,
                operationId = prepared.operationId,
                signedPsbtBase64 = preparedPsbt,
                validation = testValidation(),
            ),
        )
        assertEquals(TEST_TXID, (result as BackendOperationResult.SendBtc).txid)

        val prepareRequest = transport.requests[1]
        val completeRequest = transport.requests[2]
        val prepareKey = prepareRequest.header("Idempotency-Key")
        val completeKey = completeRequest.header("Idempotency-Key")
        assertEquals(
            "4958bc464e91725845ab2c0403a94809b6628f2fcec1cba0ee8ac8ad96088223",
            prepareKey,
        )
        assertEquals(
            "438d6a09d58b7f501b78ffca3f93dfd701a1b54a0e70038fddc923e984d96ff9",
            completeKey,
        )
        assertNotEquals(prepareKey, completeKey)
        assertEquals("/v1/onchain/send-btc/prepare", prepareRequest.url.encodedPath)
        assertEquals("/v1/onchain/send-btc/complete", completeRequest.url.encodedPath)
    }

    @Test
    fun `fractional gateway fee fails before any prepare request`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )

        assertFailsWith<SdkException.InvalidArgument> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(
                    operationContext,
                    SendBtcRequest(
                        TEST_GATEWAY_ADDRESS,
                        Satoshis.of(25_000),
                        feeRate = FeeRate.satPerVbyte("1.5"),
                        idempotencyKey = TEST_IDEMPOTENCY_KEY,
                    ),
                ),
            )
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `skip sync send fails before any prepare request`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = operationContext(context)

        val error = assertFailsWith<SdkException.Unsupported> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(
                    operationContext,
                    sendBtcRequest(skipSync = true),
                ),
            )
        }

        assertEquals("PR #174 gateway sendBtc skipSync", error.details["feature"])
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `missing authoritative gateway contracts remain unsupported without HTTP fallback`(): Unit =
        runBlocking {
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            assertFailsWith<SdkException.Unsupported> {
                backend.getOperation(
                    context,
                    OperationId.of("123e4567-e89b-42d3-a456-426614174000"),
                )
            }
            assertFailsWith<SdkException.Unsupported> { backend.syncWallet(context) }
            assertFailsWith<SdkException.Unsupported> { backend.reserveAddress(
                ReserveAddressRequest(context, rotate = false),
            ) }
            assertFailsWith<SdkException.Unsupported> {
                backend.onchainReceive(context, OnchainReceiveRequest())
            }
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `gateway idempotency reuse reports conflict for the public logical key`(): Unit =
        runBlocking {
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
                queuedResponse(
                    409,
                    "{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REUSED\"," +
                        "\"message\":\"do-not-expose\"}}",
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            val error = assertFailsWith<SdkException.OperationConflict> {
                backend.prepareSendBtc(
                    PrepareSendBtcRequest(operationContext(context), sendBtcRequest()),
                )
            }

            assertEquals(TEST_IDEMPOTENCY_KEY.value, error.details["idempotencyKey"])
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun `transient HTTP statuses are retryable backend unavailable errors`(): Unit = runBlocking {
        listOf(
            408 to "TRANSIENT_FAILURE",
            425 to "TRANSIENT_FAILURE",
            429 to "TRANSIENT_FAILURE",
            500 to "TRANSIENT_FAILURE",
            503 to "TRANSIENT_FAILURE",
            409 to "IDEMPOTENCY_IN_FLIGHT",
        ).forEach { (statusCode, backendCode) ->
            val correlationId = "request-$statusCode"
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
                queuedResponse(
                    statusCode,
                    "{\"error\":{\"code\":\"$backendCode\"," +
                        "\"message\":\"do-not-expose\"}}",
                    headers = mapOf("x-request-id" to correlationId),
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            val error = assertFailsWith<SdkException.BackendUnavailable> {
                backend.getBtcBalance(context)
            }

            assertTrue(error.retryable)
            assertEquals(correlationId, error.correlationId)
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
        }
    }

    @Test
    fun `ambiguous completion response stays unavailable for coordinator reconciliation`(): Unit =
        runBlocking {
            val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
            val transport = QueueInterceptor(
                queuedResponse(
                    200,
                    "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}",
                ),
                queuedResponse(
                    502,
                    "{\"error\":{\"code\":\"COMPLETE_AMBIGUOUS\"," +
                        "\"message\":\"do-not-expose\"}}",
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            val error = assertFailsWith<SdkException.BackendUnavailable> {
                backend.submitSignedTransaction(
                    signedSubmission(context, operationId, TEST_PSBT_BASE64),
                )
            }

            assertTrue(error.retryable)
            assertFalse(error.message.orEmpty().contains("do-not-expose"))
        }

    @Test
    fun `operation kind mismatch is a protocol failure`(): Unit = runBlocking {
        val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                409,
                "{\"error\":{\"code\":\"OP_KIND_MISMATCH\",\"message\":\"do-not-expose\"}}",
                headers = mapOf("x-request-id" to "request-kind-mismatch"),
            ),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)

        val error = assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.submitSignedTransaction(
                signedSubmission(context, operationId, TEST_PSBT_BASE64),
            )
        }

        assertEquals(operationId, error.operationId)
        assertEquals("request-kind-mismatch", error.correlationId)
        assertFalse(error.message.orEmpty().contains("do-not-expose"))
    }

    @Test
    fun `prepared and submitted PSBTs must use canonical Base64`(): Unit = runBlocking {
        val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
        val unpaddedBase64 = "AAAAAAAAAAAAAAAAAAAAAA"
        val prepareTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                201,
                preparedSendBtcJson(
                    operationId = operationId.value,
                    expiresAtMillis = 2_000_123L,
                    psbtBase64 = unpaddedBase64,
                ),
            ),
        )
        val prepareBackend = testBackend(prepareTransport)
        val prepareContext = initializedContext(prepareBackend)

        assertFailsWith<SdkException.BackendProtocolMismatch> {
            prepareBackend.prepareSendBtc(
                PrepareSendBtcRequest(operationContext(prepareContext), sendBtcRequest()),
            )
        }
        assertEquals(2, prepareTransport.requests.size)

        val submitTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val submitBackend = testBackend(submitTransport)
        val submitContext = initializedContext(submitBackend)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            submitBackend.submitSignedTransaction(
                signedSubmission(submitContext, operationId, unpaddedBase64),
            )
        }
        assertEquals(1, submitTransport.requests.size)
    }

    @Test
    fun `backend metadata allowlists reject unsafe codes and correlation IDs`(): Unit = runBlocking {
        val unsafeTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                400,
                "{\"error\":{\"code\":\"lowercase_code\",\"message\":\"ignored\"}}",
                headers = mapOf("x-request-id" to "request/id"),
            ),
        )
        val unsafeBackend = testBackend(unsafeTransport)
        val unsafeContext = initializedContext(unsafeBackend)
        val unsafe = assertFailsWith<SdkException.BackendRejected> {
            unsafeBackend.getBtcBalance(unsafeContext)
        }
        assertTrue(unsafe.details.isEmpty())
        assertEquals(null, unsafe.correlationId)

        val safeTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                400,
                "{\"error\":{\"code\":\"INVALID_ADDRESS_2\",\"message\":\"ignored\"}}",
                headers = mapOf("x-request-id" to "request-2:retry"),
            ),
        )
        val safeBackend = testBackend(safeTransport)
        val safeContext = initializedContext(safeBackend)
        val safe = assertFailsWith<SdkException.BackendRejected> {
            safeBackend.getBtcBalance(safeContext)
        }
        assertEquals("INVALID_ADDRESS_2", safe.details["backendCode"])
        assertEquals("request-2:retry", safe.correlationId)
    }

    @Test
    fun `gateway token remains bound to one logical wallet ID`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val initial = backend.initialize(initializeRequest())
        assertEquals(initial, backend.initialize(initializeRequest()))
        assertEquals(1, transport.requests.size)
        backend.shutdown(initial)
        val restarted = backend.initialize(initializeRequest())
        assertNotEquals(initial.id, restarted.id)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.shutdown(initial)
        }
        assertEquals(restarted, backend.initialize(initializeRequest()))

        assertFailsWith<SdkException.InvalidState> {
            backend.initialize(
                BackendInitializeRequest(
                    walletId = WalletId.of("another-wallet"),
                    network = Network.SIGNET,
                    minimumApiVersion = 1,
                    maximumApiVersion = 1,
                ),
            )
        }
        backend.shutdown(restarted)
        backend.shutdown(restarted)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `gateway identity remains bound across shutdown and reinitialization`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
            queuedResponse(200, "{\"userId\":\"u_second\",\"createdAt\":1234}"),
        )
        val backend = testBackend(transport)
        val initial = backend.initialize(initializeRequest())
        backend.shutdown(initial)

        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.initialize(initializeRequest())
        }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `malformed gateway user identity is a backend protocol failure`(): Unit = runBlocking {
        val transport = QueueInterceptor(
            queuedResponse(
                200,
                "{\"userId\":\"unsafe/user\",\"createdAt\":1234}",
                headers = mapOf("x-request-id" to "request-user"),
            ),
        )
        val error = assertFailsWith<SdkException.BackendProtocolMismatch> {
            testBackend(transport).initialize(initializeRequest())
        }
        assertEquals("request-user", error.correlationId)
    }

    @Test
    fun `gateway routes require their exact success status`(): Unit = runBlocking {
        val initializeTransport = QueueInterceptor(
            queuedResponse(201, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
        )
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            testBackend(initializeTransport).initialize(initializeRequest())
        }

        val prepareTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
            queuedResponse(
                200,
                preparedSendBtcJson(
                    operationId = "123e4567-e89b-42d3-a456-426614174000",
                    expiresAtMillis = 2_000_123L,
                ),
            ),
        )
        val backend = testBackend(prepareTransport)
        val context = initializedContext(backend)
        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(operationContext(context), sendBtcRequest()),
            )
        }
    }

    @Test
    fun `malformed contradictory or noncanonical completion success is ambiguous`(): Unit =
        runBlocking {
            val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
            val responses = listOf(
                queuedResponse(200, "{\"txid\":\"not-a-txid\"}"),
                queuedResponse(200, "{\"txid\":\"${"f".repeat(64)}\"}"),
                queuedResponse(
                    200,
                    "{\"txid\":\"${TEST_TXID.hex}\"}",
                    headers = mapOf("Content-Type" to "text/plain"),
                ),
                queuedResponse(201, "{\"txid\":\"${TEST_TXID.hex}\"}"),
                queuedResponse(302, "{\"txid\":\"${TEST_TXID.hex}\"}"),
                queuedResponse(409, "{\"error\":{\"message\":\"corrupted\"}}"),
                queuedResponse(200, "x".repeat(1_500_001)),
                queuedResponse(502, "x".repeat(1_500_001)),
            )
            responses.forEach { completion ->
                val transport = QueueInterceptor(
                    queuedResponse(200, "{\"userId\":\"u_first\",\"createdAt\":1234}"),
                    completion,
                )
                val backend = testBackend(transport)
                val context = initializedContext(backend)
                val error = assertFailsWith<SdkException.OperationAmbiguous> {
                    backend.submitSignedTransaction(
                        signedSubmission(context, operationId, TEST_PSBT_BASE64),
                    )
                }
                assertEquals(operationId, error.operationId)
            }
        }

    @Test
    fun `stale or unbounded preparation expiry is rejected`(): Unit = runBlocking {
        val operationId = "123e4567-e89b-42d3-a456-426614174000"
        listOf(1_500_000L, 5_100_001L).forEach { expiresAtMillis ->
            val transport = QueueInterceptor(
                queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
                queuedResponse(
                    201,
                    preparedSendBtcJson(operationId, expiresAtMillis),
                ),
            )
            val backend = testBackend(transport)
            val context = initializedContext(backend)

            assertFailsWith<SdkException.BackendProtocolMismatch> {
                backend.prepareSendBtc(
                    PrepareSendBtcRequest(
                        operationContext(context),
                        sendBtcRequest(),
                    ),
                )
            }
        }
    }

    @Test
    fun `tampered returned intent fails closed and response message never leaks`(): Unit = runBlocking {
        val operationId = "123e4567-e89b-42d3-a456-426614174000"
        val transport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                201,
                """
                    {
                      "opId":"$operationId",
                      "psbt":"cHNidP8BAHECAAAAAAAAAAAA",
                      "intent":{
                        "kind":"send_btc",
                        "feeRateSatPerVb":2,
                        "recipients":[{
                          "address":"${TEST_GATEWAY_ADDRESS.value}",
                          "scriptHex":"512000",
                          "amountSat":25001
                        }],
                        "asset":null,
                        "utxos":null
                      },
                      "expiresAt":2000123
                    }
                """.trimIndent(),
            ),
        )
        val backend = testBackend(transport)
        val context = initializedContext(backend)
        val operationContext = OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )

        assertFailsWith<SdkException.BackendProtocolMismatch> {
            backend.prepareSendBtc(
                PrepareSendBtcRequest(
                    operationContext,
                    SendBtcRequest(
                        TEST_GATEWAY_ADDRESS,
                        Satoshis.of(25_000),
                        FeeRate.satPerVbyte(2.0),
                        idempotencyKey = TEST_IDEMPOTENCY_KEY,
                    ),
                ),
            )
        }

        val rejectedTransport = QueueInterceptor(
            queuedResponse(200, "{\"userId\":\"u_0123456789abcdef\",\"createdAt\":1234}"),
            queuedResponse(
                400,
                "{\"error\":{\"code\":\"WALLET_REQUEST_REJECTED\",\"message\":\"secret-body-marker\"}}",
            ),
        )
        val rejectedBackend = testBackend(rejectedTransport)
        val rejectedContext = initializedContext(rejectedBackend)
        val error = assertFailsWith<SdkException.BackendRejected> {
            rejectedBackend.getBtcBalance(rejectedContext)
        }
        assertFalse(error.message.orEmpty().contains("secret-body-marker"))
    }

    private suspend fun initializedContext(
        backend: RgbLightningNodeGatewayBackend,
    ): BackendContext {
        val session = backend.initialize(initializeRequest())
        return BackendContext(session, TEST_WALLET_ID, Network.SIGNET)
    }

    private fun initializeRequest(): BackendInitializeRequest = BackendInitializeRequest(
        walletId = TEST_WALLET_ID,
        network = Network.SIGNET,
        minimumApiVersion = 1,
        maximumApiVersion = 1,
    )

    private fun operationContext(context: BackendContext): OperationRequestContext =
        OperationRequestContext(
            backend = context,
            idempotencyKey = TEST_IDEMPOTENCY_KEY,
            requestHash = RequestHash.ofLowercaseHex("0".repeat(64)),
        )

    private fun inactiveContext(): BackendContext = BackendContext(
        session = BackendSession("inactive-session", 1, Network.SIGNET),
        walletId = TEST_WALLET_ID,
        network = Network.SIGNET,
    )

    private fun gatewayWalletConfiguration(
        maximumFeeRate: FeeRate = FeeRate.satPerVbyte(100.0),
    ): WalletConfiguration = WalletConfiguration(
        walletId = TEST_WALLET_ID,
        network = Network.SIGNET,
        signingPolicy = LocalSigningPolicy(maximumFeeRate = maximumFeeRate),
    )

    private fun persistedSendCheckpoint(
        request: SendBtcRequest,
        phase: OperationPhase,
        expiresAt: UnixSeconds = UnixSeconds.of(TEST_NOW + 1_000L),
    ): OperationCheckpoint {
        require(
            phase == OperationPhase.SIGNED ||
                phase == OperationPhase.SUBMITTING ||
                phase == OperationPhase.TERMINAL,
        )
        val requestHash = RequestHasher.sendBtc(Network.SIGNET, request)
        val operationId = OperationId.of("123e4567-e89b-42d3-a456-426614174000")
        val prepared = PreparedTransaction(
            operationId = operationId,
            idempotencyKey = request.idempotencyKey,
            requestHash = requestHash,
            network = Network.SIGNET,
            operation = OperationKind.SEND_BTC,
            psbtBase64 = TEST_PSBT_BASE64,
            expiresAt = expiresAt,
        )
        val signed = SignedOperationPayload(TEST_PSBT_BASE64, testValidation())
        return OperationCheckpoint(
            walletId = TEST_WALLET_ID,
            idempotencyKey = request.idempotencyKey,
            requestHash = requestHash,
            operationKind = OperationKind.SEND_BTC,
            phase = phase,
            operationId = operationId,
            preparedTransaction = prepared,
            signedPayload = signed,
            terminalOutcome = if (phase == OperationPhase.TERMINAL) {
                OperationTerminalOutcome.Succeeded(
                    BackendOperationResult.SendBtc(operationId, TEST_TXID),
                )
            } else {
                null
            },
        )
    }

    private fun sendBtcRequest(skipSync: Boolean = false): SendBtcRequest = SendBtcRequest(
        address = TEST_GATEWAY_ADDRESS,
        amount = Satoshis.of(25_000),
        feeRate = FeeRate.satPerVbyte(2.0),
        skipSync = skipSync,
        idempotencyKey = TEST_IDEMPOTENCY_KEY,
    )

    private fun signedSubmission(
        context: BackendContext,
        operationId: OperationId,
        psbtBase64: String,
    ): SignedTransactionSubmission = SignedTransactionSubmission(
        context = operationContext(context),
        operationId = operationId,
        signedPsbtBase64 = psbtBase64,
        validation = testValidation(),
    )

    private fun testBackend(
        interceptor: Interceptor,
        allowUnverifiedPsbtProfile: Boolean = true,
    ): RgbLightningNodeGatewayBackend {
        val configuration = gatewayConfiguration(
            allowUnverifiedPsbtProfile = allowUnverifiedPsbtProfile,
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        val constructor = RgbLightningNodeGatewayBackend::class.java.getDeclaredConstructor(
            RgbLightningNodeGatewayConfiguration::class.java,
            OkHttpClient::class.java,
            Function0::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(configuration, client, { 1_500_000L })
            as RgbLightningNodeGatewayBackend
    }

    private fun gatewayConfiguration(
        baseUrl: String = "https://gateway.example",
        allowInsecureLocalDevelopment: Boolean = false,
        allowUnverifiedPsbtProfile: Boolean = false,
    ): RgbLightningNodeGatewayConfiguration = RgbLightningNodeGatewayConfiguration(
        baseUrl = baseUrl,
        expectedNetwork = Network.SIGNET,
        bearerToken = TEST_TOKEN,
        allowInsecureLocalDevelopment = allowInsecureLocalDevelopment,
        allowUnverifiedPsbtProfile = allowUnverifiedPsbtProfile,
    )

    private fun gatewayIdentity(): WalletIdentity = WalletIdentity(
        network = Network.SIGNET,
        chainNetwork = "signet",
        derivationProfile = WalletCapabilities.DERIVATION_PROFILE_V1,
        masterFingerprint = "deadbeef",
        xpub = "v".repeat(111),
        accountXpubVanilla = "t".repeat(111),
        accountXpubColored = "u".repeat(111),
    )

    private fun preparedSendBtcJson(
        operationId: String,
        expiresAtMillis: Long,
        psbtBase64: String = TEST_PSBT_BASE64,
    ): String = """
        {
          "opId":"$operationId",
          "psbt":"$psbtBase64",
          "intent":{
            "kind":"send_btc",
            "feeRateSatPerVb":2,
            "recipients":[{
              "address":"${TEST_GATEWAY_ADDRESS.value}",
              "scriptHex":"512000",
              "amountSat":25000
            }],
            "asset":null,
            "utxos":null
          },
          "expiresAt":$expiresAtMillis
        }
    """.trimIndent()

    private companion object {
        const val TEST_TOKEN: String = "utxg_test_token_0123456789"
        const val TEST_PSBT_BASE64: String = "cHNidP8BAHECAAAAAAAAAAAA"
        val TEST_WALLET_ID: WalletId = WalletId.of("gateway-wallet")
        val TEST_GATEWAY_ADDRESS: BitcoinAddress = BitcoinAddress.of(
            "tb1ptestaddress000000000000000000000000000000000000000000000",
        )
    }
}

private class QueueInterceptor(vararg responses: QueuedResponse) : Interceptor {
    private val responses: ArrayDeque<QueuedResponse> = ArrayDeque(responses.toList())
    val requests: MutableList<Request> = mutableListOf()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val response = checkNotNull(responses.pollFirst()) { "no queued response" }
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(response.statusCode)
            .message("test")
            .header("Content-Type", "application/json")
            .body(response.body.toResponseBody("application/json".toMediaType()))
        response.headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}

private data class QueuedResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
)

private fun queuedResponse(
    statusCode: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
): QueuedResponse = QueuedResponse(statusCode, body, headers)

private fun Request.bodyUtf8(): String {
    val buffer = Buffer()
    checkNotNull(body).writeTo(buffer)
    return buffer.readUtf8()
}

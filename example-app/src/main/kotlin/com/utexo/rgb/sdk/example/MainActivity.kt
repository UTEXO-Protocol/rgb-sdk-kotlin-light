package com.utexo.rgb.sdk.example

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.utexo.rgb.sdk.AndroidKeystoreAPIKeyStore
import com.utexo.rgb.sdk.AndroidKeystoreSeedStore
import com.utexo.rgb.sdk.AssetAmount
import com.utexo.rgb.sdk.AssetBalance
import com.utexo.rgb.sdk.AssetId
import com.utexo.rgb.sdk.BitcoinAddress
import com.utexo.rgb.sdk.CreateUtxosRequest
import com.utexo.rgb.sdk.FeeRate
import com.utexo.rgb.sdk.InvoiceRequest
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.OnchainSendRequest
import com.utexo.rgb.sdk.RgbInvoice
import com.utexo.rgb.sdk.Satoshis
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.SecretMnemonic
import com.utexo.rgb.sdk.SendBtcRequest
import com.utexo.rgb.sdk.UTEXOWallet
import com.utexo.rgb.sdk.UnixSeconds
import com.utexo.rgb.sdk.WalletAPIKey
import com.utexo.rgb.sdk.WalletChainContext
import com.utexo.rgb.sdk.WalletChainContextProvider
import com.utexo.rgb.sdk.WalletConfiguration
import com.utexo.rgb.sdk.WalletId
import com.utexo.rgb.sdk.WalletServiceConfiguration
import com.utexo.rgb.sdk.WitnessData
import com.utexo.rgb.sdk.generateKeys
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A minimal, readable integration of the UTEXO mobile SDK against a
 * lightsdk gateway (`rln-gateway`) deployment. One screen, no architecture, no DI — the point
 * is that every SDK call is visible in one file.
 *
 * It covers the lifecycle an app actually has to get right:
 *
 *   provision seed (once) -> create -> init -> unlock -> read / spend -> shutdown
 *
 * Spending (BTC sends, UTXO creation, RGB sends) is signed on the device. The
 * gateway prepares each transaction, the local signer checks it against the
 * request and a chain tip from a source the app trusts, signs, and only then
 * asks the gateway to broadcast.
 *
 * Fill in the gateway URL and paste a bearer token, then Run. For a gateway on
 * your own machine use `http://10.0.2.2:<port>` from the emulator.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var log: TextView
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var walletField: EditText
    private lateinit var tipField: EditText
    private lateinit var recipientField: EditText
    private lateinit var amountField: EditText
    private lateinit var assetField: EditText
    private var wallet: UTEXOWallet? = null
    private var network: Network = NETWORK

    /** Every wallet action, by the name a scripted run uses for it. */
    private val actions: List<Pair<String, suspend (UTEXOWallet) -> Unit>> = listOf(
        "address" to ::onAddress,
        "balance" to ::onBalance,
        "sync" to ::onSync,
        "unspents" to ::onUnspents,
        "utxos" to ::onCreateUtxos,
        "sendbtc" to ::onSendBtc,
        "assets" to ::onAssets,
        "transfers" to ::onTransfers,
        "witnessinvoice" to ::onWitnessInvoice,
        "blindinvoice" to ::onBlindInvoice,
        "rgbsend" to ::onRgbSend,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        // Convenience for scripted demos and CI:
        //   adb shell am start -S -n …/.MainActivity \
        //     -e gatewayUrl https://your-gateway.example -e bearerToken utxg_… \
        //     [-e walletId example] [-e network regtest] [-e tipUrl https://…/blocks/tip/height] \
        //     [-e recipient …] [-e amount …] [-e assetId …] \
        //     [-e provisionedMnemonic word_word_…] [-e autoConnect true] \
        //     [-e run sync,balance,…]
        // `provisionedMnemonic` is underscore-separated because `am start -e`
        // does not carry spaces through reliably. `run` connects, then runs
        // the named actions in order; see `actions` for the names.
        intent?.getStringExtra("gatewayUrl")?.let { urlField.setText(it) }
        intent?.getStringExtra("bearerToken")?.let { tokenField.setText(it) }
        intent?.getStringExtra("walletId")?.let { walletField.setText(it) }
        intent?.getStringExtra("network")?.let { network = Network.valueOf(it.uppercase()) }
        intent?.getStringExtra("tipUrl")?.let { tipField.setText(it) }
        intent?.getStringExtra("recipient")?.let { recipientField.setText(it) }
        intent?.getStringExtra("amount")?.let { amountField.setText(it) }
        intent?.getStringExtra("assetId")?.let { assetField.setText(it) }
        line("Enter the gateway URL and a bearer token, then press Connect.")
        line("A token is minted by the operator: POST /v1/users with x-operator-token.")
        line("Connect generates a wallet on first run and reuses it afterwards.")
        val script = intent?.getStringExtra("run")
        when {
            script != null -> runScript(script.split(',').map(String::trim).filter(String::isNotEmpty))
            intent?.getStringExtra("autoConnect") == "true" -> run("connect") { connect() }
        }
    }

    override fun onDestroy() {
        // Shutdown releases the session but deliberately retains storage
        // ownership; destroy() is what a replacement instance needs.
        wallet?.let { open -> CoroutineScope(Dispatchers.IO).launch { runCatching { open.shutdown() } } }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- actions

    /**
     * First run provisions a seed into the Android Keystore; later runs reuse
     * it. The seed must exist *before* the wallet is created — `unlock()` loads
     * it and fails with `KeyAccessFailed` if it was never provisioned.
     */
    private suspend fun connect() {
        // A wallet's storage has one owner at a time, so the instance already
        // open — possibly a different wallet ID — is destroyed first.
        wallet?.let { previous ->
            wallet = null
            previous.destroy()
        }
        val walletId = WalletId.of(walletField.text.toString().trim())
        val seedStore = AndroidKeystoreSeedStore(applicationContext, walletId, network)
        val restoreFrom = intent?.getStringExtra("provisionedMnemonic")?.replace('_', ' ')
            ?: PROVISIONED_MNEMONIC
        if (!restoreFrom.isNullOrBlank()) {
            provision(seedStore, generated = false) {
                SecretMnemonic.fromWords(restoreFrom.toCharArray())
            }
        } else {
            // Nothing to restore from, so this is a new wallet.
            provision(seedStore, generated = true) {
                val keys = generateKeys(network)
                // GeneratedKeys.close() closes the mnemonic too, so it cannot
                // be used here; release the root key the example does not need
                // and hand the mnemonic on for `use` to close.
                keys.xpriv.close()
                keys.mnemonic ?: error("generateKeys returned no mnemonic")
            }
        }

        val token = tokenField.text.toString().trim()
        check(token.isNotEmpty()) { "paste a bearer token first" }
        // The provider is called per request, so rotating the stored credential
        // takes effect without rebuilding the wallet.
        val keyProvider = AndroidKeystoreAPIKeyStore(applicationContext, walletId, network)
        runCatching { keyProvider.store(WalletAPIKey.fromCharacters(token.toCharArray())) }

        val open = UTEXOWallet.create(
            context = applicationContext,
            configuration = WalletConfiguration(walletId = walletId, network = network),
            service = WalletServiceConfiguration(
                baseUrl = urlField.text.toString().trim(),
                apiKeyProvider = keyProvider,
                // Loopback / 10.0.2.2 only; a real deployment is HTTPS.
                allowInsecureLocalDevelopment = true,
                // Well above the 30s read / 45s call defaults. The first
                // call against a newly registered wallet also pays for the
                // gateway building its rgb-lib wallet and reaching the
                // indexer. Raising these makes the first-call
                // `BackendUnavailable` rarer but does not remove it, so do not
                // read it as a fix — retry is still the contract.
                readTimeoutMillis = 120_000L,
                callTimeoutMillis = 180_000L,
                // Without it reads still work, but every new spend fails closed.
                chainContextProvider = chainTip(tipField.text.toString().trim()),
            ),
        )
        open.init()     // initializes the gateway session (idempotent)
        open.unlock()   // loads the seed and registers the watch-only xpubs
        wallet = open
        line("connected as ${walletId.value} — wallet registered and unlocked")
    }

    /**
     * The signer bounds every prepared transaction against this tip, so it
     * must come from a source the app trusts *independently of the gateway* —
     * here an HTTP `/blocks/tip/height` for [network]. Never copy
     * a height out of a PSBT, and never restamp an old observation: the SDK
     * rejects a tip older than 300 seconds.
     */
    private fun chainTip(tipUrl: String) = WalletChainContextProvider { network ->
        val height = withContext(Dispatchers.IO) {
            val connection = URL(tipUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.inputStream.bufferedReader().use { it.readText().trim().toInt() }
            } finally {
                connection.disconnect()
            }
        }
        WalletChainContext(network, height, UnixSeconds.of(System.currentTimeMillis() / 1_000L))
    }

    /**
     * Provisioning is one-shot: the store refuses to overwrite an existing
     * seed, and that refusal is how a later run discovers it already has one.
     * Replacing a seed would silently orphan the funds held under the old one,
     * so the example never forces it — see `resetForRecovery` for the explicit
     * destructive path.
     */
    private suspend fun provision(
        seedStore: AndroidKeystoreSeedStore,
        generated: Boolean,
        mnemonicFactory: suspend () -> SecretMnemonic,
    ) {
        // Secrets are AutoCloseable and zeroize on close(); always use `use`.
        mnemonicFactory().use { mnemonic ->
            try {
                seedStore.provisionFromMnemonic(mnemonic)
            } catch (failure: SdkException.InvalidState) {
                // Match the state, not the message: codes and details are the
                // stable contract.
                if (failure.details["state"] != "already provisioned") throw failure
                line("seed already in the Keystore — reusing it")
                return
            }
            if (!generated) {
                line("seed restored from the supplied mnemonic")
                return
            }
            line("new wallet generated and provisioned")
            // This is the user's ONLY backup. A real app shows it once behind a
            // confirmation and makes the user prove they recorded it; printing
            // it to a log view is acceptable for a signet demo and nowhere else.
            mnemonic.useWords { words -> line("BACK THIS UP: ${String(words)}") }
        }
    }

    /**
     * Each call allocates a fresh vanilla address. The SDK re-derives it from
     * the local seed before returning it: the gateway holds the xpubs, so an
     * address it hands back unchecked could be anyone's.
     */
    private suspend fun onAddress(open: UTEXOWallet) {
        line("address: ${open.getAddress().value}")
    }

    private suspend fun onBalance(open: UTEXOWallet) {
        val btc = open.getBtcBalance()
        line("vanilla  settled=${btc.vanilla.settled.value} spendable=${btc.vanilla.spendable.value}")
        line("colored  settled=${btc.colored.settled.value} spendable=${btc.colored.spendable.value}")
    }

    private suspend fun onSync(open: UTEXOWallet) {
        // One combined gateway call: Bitcoin wallet sync plus RGB refresh. An
        // incoming RGB transfer only settles after the receiver refreshes.
        val result = open.syncWallet()
        line("synced at ${result.completedAt.value} (tipHeight=${result.tipHeight ?: "not reported"})")
    }

    private suspend fun onUnspents(open: UTEXOWallet) {
        val unspents = open.listUnspents()
        line("${unspents.size} unspent output(s)")
        for (u in unspents) {
            line(
                "  ${u.utxo.outpoint.txid.hex.take(12)}…:${u.utxo.outpoint.vout}  " +
                    "${u.utxo.btcAmount.value} sat  colorable=${u.utxo.colorable}  " +
                    "exists=${u.utxo.exists ?: "unknown"}  allocations=${u.rgbAllocations.size}",
            )
        }
    }

    /**
     * RGB allocations live on colored UTXOs, so a wallet needs some before it
     * can receive to a blinded invoice or hold change from an RGB send. The
     * gateway defaults to five 1,000-sat outputs.
     */
    private suspend fun onCreateUtxos(open: UTEXOWallet) {
        val result = open.createUtxos(CreateUtxosRequest(feeRate = FEE_RATE))
        line("created ${result.createdUtxoCount} colored UTXO(s) in ${result.txid.hex}")
    }

    /**
     * A real app commits the request — idempotency key included — to its own
     * database BEFORE this call, and retries with the same key after a crash
     * or `OperationAmbiguous`. The returned txid means accepted, not confirmed.
     */
    private suspend fun onSendBtc(open: UTEXOWallet) {
        val request = SendBtcRequest(
            address = BitcoinAddress.of(recipientField.text.toString().trim()),
            amount = Satoshis.of(amountField.text.toString().trim().toLong()),
            feeRate = FEE_RATE,
        )
        line("sending ${request.amount.value} sat to ${request.address.value}")
        line("sent: txid ${open.sendBtc(request).hex}")
    }

    private suspend fun onAssets(open: UTEXOWallet) {
        val assets = open.listAssets()
        fun show(schema: String, id: AssetId, label: String, balance: AssetBalance) = line(
            "  $schema ${id.value}  $label  settled=${balance.settled?.decimal ?: "-"} " +
                "spendable=${balance.spendable?.decimal ?: "-"} future=${balance.future?.decimal ?: "-"}",
        )
        line("${assets.nia.size + assets.cfa.size + assets.ifa.size + assets.uda.size + assets.bfa.size} asset(s)")
        assets.nia.forEach { show("NIA", it.assetId, it.ticker, it.balance) }
        assets.ifa.forEach { show("IFA", it.assetId, it.ticker, it.balance) }
        assets.cfa.forEach { show("CFA", it.assetId, it.name, it.balance) }
        assets.uda.forEach { show("UDA", it.assetId, it.ticker, it.balance) }
        assets.bfa.forEach { show("BFA", it.assetId, it.ticker, it.balance) }
    }

    private suspend fun onTransfers(open: UTEXOWallet) {
        val assetText = assetField.text.toString().trim()
        val transfers = open.listTransfers(assetText.takeIf(String::isNotEmpty)?.let(AssetId::of))
        line("${transfers.size} transfer(s)")
        for (t in transfers) {
            line(
                "  #${t.idx} ${t.kind.wireValue} ${t.status.wireValue}  " +
                    "amounts=${t.assignments.joinToString { it.amount?.decimal ?: it.type.wireValue }}  " +
                    "asset=${t.assetId?.value ?: "-"}  txid=${t.txid?.hex ?: "-"}",
            )
        }
    }

    /**
     * A witness invoice asks the sender to create the receiving output, so the
     * receiver needs no colored UTXO; a blinded invoice receives onto one of
     * the receiver's own. Asset and amount are optional, and the SDK decodes
     * the returned invoice locally to check the gateway encoded this request.
     */
    private suspend fun onWitnessInvoice(open: UTEXOWallet) = onRgbInvoice(open, witness = true)

    private suspend fun onBlindInvoice(open: UTEXOWallet) = onRgbInvoice(open, witness = false)

    private suspend fun onRgbInvoice(open: UTEXOWallet, witness: Boolean) {
        val assetText = assetField.text.toString().trim()
        val amountText = amountField.text.toString().trim()
        val request = InvoiceRequest(
            amount = amountText.takeIf(String::isNotEmpty)?.let(AssetAmount::parse),
            assetId = assetText.takeIf(String::isNotEmpty)?.let(AssetId::of),
        )
        val received = if (witness) open.witnessReceive(request) else open.blindReceive(request)
        line("invoice: ${received.invoice.value}")
        line("  recipientId=${received.recipientId} batchTransferIdx=${received.batchTransferIdx}")
    }

    /**
     * The SDK decodes the invoice locally and the signer binds the prepared
     * transaction to it; the gateway is never trusted for the recipient.
     * A witness invoice needs the sats for the receiving output; rgb-lib
     * identifies one by the `wvout:` in its recipient ID (`sb:wvout:…`).
     */
    private suspend fun onRgbSend(open: UTEXOWallet) {
        val invoice = RgbInvoice.of(recipientField.text.toString().trim())
        val decoded = open.decodeRgbInvoice(invoice)
        val assetText = assetField.text.toString().trim()
        val request = OnchainSendRequest(
            invoice = invoice,
            amount = AssetAmount.parse(amountField.text.toString().trim()),
            assetId = assetText.takeIf(String::isNotEmpty)?.let(AssetId::of),
            witnessData = if ("wvout:" in decoded.recipientId) WitnessData(WITNESS_SATS) else null,
            feeRate = FEE_RATE,
        )
        line("sending ${request.amount.decimal} of ${request.assetId?.value ?: decoded.assetId?.value} to ${decoded.recipientId}")
        val sent = open.onchainSend(request)
        line("sent: txid ${sent.txid.hex} batchTransferIdx=${sent.batchTransferIdx ?: "not reported"}")
    }

    // ------------------------------------------------------------- plumbing

    private fun withWallet(label: String, block: suspend (UTEXOWallet) -> Unit) = run(label) {
        val open = wallet ?: error("press Connect first")
        block(open)
    }

    /** Every SDK call is suspending; nothing touches the main thread. */
    private fun run(label: String, block: suspend () -> Unit) {
        scope.launch { report(label, block) }
    }

    /** Connects, then runs each named action in order; a failure does not stop the rest. */
    private fun runScript(names: List<String>) {
        scope.launch {
            if (!report("connect") { connect() }) {
                line("script done")
                return@launch
            }
            for (name in names) {
                val action = actions.firstOrNull { it.first == name }?.second
                if (action == null) {
                    line("✗ unknown action $name")
                    continue
                }
                report(name) { action(wallet ?: error("not connected")) }
            }
            line("script done")
        }
    }

    private suspend fun report(label: String, block: suspend () -> Unit): Boolean {
        line("· $label…")
        return try {
            withContext(Dispatchers.IO) { block() }
            true
        } catch (failure: SdkException) {
            // Messages, codes and details are documented as redacted and
            // safe to log. Always record the code and correlationId: the
            // code is the stable contract (never branch on the message),
            // and the correlationId is what the operator greps for.
            line("✗ $label failed: ${failure.code.value}: ${failure.message}")
            failure.correlationId?.let { line("   correlationId=$it") }
            if (failure.details.isNotEmpty()) line("   details=${failure.details}")
            false
        } catch (failure: Exception) {
            line("✗ $label failed: ${failure.message}")
            false
        }
    }

    /** Callable from any thread: every SDK call runs off the main thread. */
    private fun line(text: String) {
        Log.i(LOG_TAG, text)
        runOnUiThread { log.append(text + "\n") }
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        fun field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                inputType = type
            }
        urlField = field("gateway base URL", GATEWAY_URL, InputType.TYPE_TEXT_VARIATION_URI)
        tokenField = field("bearer token (utxg_…)")
        walletField = field("wallet ID", WALLET_ID)
        tipField = field("trusted chain tip URL", CHAIN_TIP_URL, InputType.TYPE_TEXT_VARIATION_URI)
        recipientField = field("recipient: BTC address or RGB invoice")
        amountField = field("amount: sats for BTC, asset units for RGB")
        assetField = field("asset ID (optional)")
        log = TextView(this).apply { setPadding(0, pad, 0, pad); setTextIsSelectable(true) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttons.addView(Button(this).apply { text = "Connect"; setOnClickListener { run("connect") { connect() } } })
        for ((name, action) in actions) {
            buttons.addView(Button(this).apply { text = name; setOnClickListener { withWallet(name, action) } })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            listOf(urlField, tokenField, walletField, tipField, recipientField, amountField, assetField)
                .forEach(::addView)
            addView(HorizontalScroll(context, buttons))
            addView(log)
        }
        return ScrollView(this).apply { addView(column) }
    }

    private class HorizontalScroll(context: android.content.Context, child: View) :
        android.widget.HorizontalScrollView(context) {
        init { addView(child) }
    }

    private companion object {
        /**
         * UTEXO's stage gateway, on Bitcoin's public signet. For a gateway on this
         * machine use `http://10.0.2.2:<port>`; `10.0.2.2` is the emulator's host.
         */
        const val GATEWAY_URL: String = "https://stage.gateway.lightsdk.utexo.com"
        val NETWORK: Network = Network.SIGNET
        const val WALLET_ID: String = "example"
        const val LOG_TAG: String = "UtexoExample"

        /**
         * An HTTP `/blocks/tip/height` for the chain the gateway runs on; it
         * must not be the gateway. The stage gateway runs on Bitcoin's public
         * signet. A tip from another chain is far off the gateway's height, and
         * the signer then refuses every transaction. On regtest,
         * `scripts/tip.mjs` serves one.
         */
        const val CHAIN_TIP_URL: String = "https://mempool.space/signet/api/blocks/tip/height"

        /** Explicit, because a gateway whose indexer has no fee data cannot estimate one. */
        val FEE_RATE: FeeRate = FeeRate.satPerVbyte("2")

        /** Sats a sender puts on the output it creates for a witness invoice. */
        val WITNESS_SATS: Satoshis = Satoshis.of(1_000)

        /**
         * Set to a 12-word mnemonic to restore an existing wallet on first run.
         * Left null, Connect generates a fresh wallet and prints the mnemonic
         * once. A real app never hardcodes this — it is here so a scripted demo
         * can reach a funded wallet.
         */
        val PROVISIONED_MNEMONIC: String? = null
    }
}

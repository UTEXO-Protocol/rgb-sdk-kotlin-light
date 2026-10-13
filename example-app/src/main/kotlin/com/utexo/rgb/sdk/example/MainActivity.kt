package com.utexo.rgb.sdk.example

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.utexo.rgb.sdk.AndroidKeystoreAPIKeyStore
import com.utexo.rgb.sdk.AndroidKeystoreSeedStore
import com.utexo.rgb.sdk.Network
import com.utexo.rgb.sdk.SdkException
import com.utexo.rgb.sdk.SecretMnemonic
import com.utexo.rgb.sdk.UTEXOWallet
import com.utexo.rgb.sdk.WalletAPIKey
import com.utexo.rgb.sdk.WalletConfiguration
import com.utexo.rgb.sdk.WalletId
import com.utexo.rgb.sdk.WalletServiceConfiguration
import com.utexo.rgb.sdk.generateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A minimal, readable integration of the UTEXO mobile SDK against a
 * `minimal-gateway` deployment. One screen, no architecture, no DI — the point
 * is that every SDK call is visible in one file.
 *
 * It covers the lifecycle an app actually has to get right:
 *
 *   provision seed (once) -> create -> init -> unlock -> read -> shutdown
 *
 * and deliberately shows the *supported* surface. `sendBtc` is included only to
 * demonstrate that it is gated off in the application API; see
 * `onSend` for why and what to do about it.
 *
 * Fill in GATEWAY_URL and paste a bearer token, then Run. For a gateway on your
 * own machine use `http://10.0.2.2:<port>` from the emulator.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var log: TextView
    private lateinit var tokenField: EditText
    private lateinit var urlField: EditText
    private var wallet: UTEXOWallet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        // Convenience for scripted demos and CI:
        //   adb shell am start -n …/.MainActivity \
        //     -e gatewayUrl https://your-gateway.example -e bearerToken utxg_… \
        //     [-e provisionedMnemonic word_word_…] [-e autoConnect true]
        // `provisionedMnemonic` is underscore-separated because `am start -e`
        // does not carry spaces through reliably.
        intent?.getStringExtra("gatewayUrl")?.let { urlField.setText(it) }
        intent?.getStringExtra("bearerToken")?.let { tokenField.setText(it) }
        line("Enter the gateway URL and a bearer token, then press Connect.")
        line("A token is minted by the operator: POST /v1/users with x-operator-token.")
        line("Connect generates a wallet on first run and reuses it afterwards.")
        if (intent?.getStringExtra("autoConnect") == "true") onConnect()
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
    private fun onConnect() = run("connect") {
        val walletId = WalletId.of(WALLET_ID)
        val seedStore = AndroidKeystoreSeedStore(applicationContext, walletId, NETWORK)
        val restoreFrom = intent?.getStringExtra("provisionedMnemonic")?.replace('_', ' ')
            ?: PROVISIONED_MNEMONIC
        if (!restoreFrom.isNullOrBlank()) {
            provision(seedStore, generated = false) {
                SecretMnemonic.fromWords(restoreFrom.toCharArray())
            }
        } else {
            // Nothing to restore from, so this is a new wallet.
            provision(seedStore, generated = true) {
                val keys = generateKeys(NETWORK)
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
        val keyProvider = AndroidKeystoreAPIKeyStore(applicationContext, walletId, NETWORK)
        runCatching { keyProvider.store(WalletAPIKey.fromCharacters(token.toCharArray())) }

        val open = UTEXOWallet.create(
            context = applicationContext,
            configuration = WalletConfiguration(walletId = walletId, network = NETWORK),
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
            ),
        )
        open.init()     // registers the xpubs with the gateway (idempotent)
        open.unlock()   // loads the seed; required before any balance call
        wallet = open
        line("connected — wallet registered and unlocked")
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
     * `getAddress` re-derives locally from the seed rather than trusting what
     * the gateway returns: the gateway holds the xpubs, so an address it hands
     * back unchecked could be anyone's.
     *
     * Against this gateway it currently throws `Unsupported` —
     * `RgbLightningNodeGatewayBackend` does not implement `reserveAddress`
     * yet. The button stays so the gap is discovered here, with the workaround
     * next to it, rather than in production.
     */
    private fun onAddress() = withWallet("address") { open ->
        try {
            line("address: ${open.getAddress().value}")
        } catch (_: SdkException.Unsupported) {
            line("getAddress needs WalletBackend.reserveAddress, which this")
            line("gateway backend does not implement yet. For a deposit address")
            line("call GET /v1/wallet/address and re-derive it from the")
            line("`derivation` it returns before showing it to a user.")
            line("A null `derivation` is unproven — do not treat it as safe.")
        }
    }

    private fun onBalance() = withWallet("balance") { open ->
        val btc = open.getBtcBalance()
        line("vanilla  settled=${btc.vanilla.settled.value} spendable=${btc.vanilla.spendable.value}")
        line("colored  settled=${btc.colored.settled.value} spendable=${btc.colored.spendable.value}")
    }

    private fun onSync() = withWallet("sync") { open ->
        // One combined gateway call: Bitcoin wallet sync plus RGB refresh.
        val result = open.syncWallet()
        line("synced at ${result.completedAt.value} (tipHeight=${result.tipHeight ?: "not reported"})")
    }

    private fun onUnspents() = withWallet("unspents") { open ->
        val unspents = open.listUnspents()
        line("${unspents.size} unspent output(s)")
        for (u in unspents) {
            line(
                "  ${u.utxo.outpoint.txid.hex.take(12)}…:${u.utxo.outpoint.vout}  " +
                    "${u.utxo.btcAmount.value} sat  colorable=${u.utxo.colorable}  " +
                    "exists=${u.utxo.exists}  allocations=${u.rgbAllocations.size}",
            )
        }
    }

    /**
     * Sending is gated OFF in the application API: `WalletServiceConfiguration`
     * hardcodes `allowUnverifiedPsbtProfile = false`, so this always throws
     * `SdkException.Unsupported`.
     *
     * That is a deliberate hold until a deployment's PSBT profile is qualified,
     * not an unfinished feature — the path works end to end when reached through
     * `RgbLightningNodeGatewayBackend` with the flag enabled. This button exists
     * so the behaviour is discovered here rather than in production.
     */
    private fun onSend() = withWallet("send") { _ ->
        line("sendBtc is intentionally unavailable through UTEXOWallet.")
        line("WalletServiceConfiguration pins allowUnverifiedPsbtProfile = false.")
        line("To send, build RgbLightningNodeGatewayBackend yourself with the flag on.")
    }

    // ------------------------------------------------------------- plumbing

    private fun withWallet(label: String, block: suspend (UTEXOWallet) -> Unit) = run(label) {
        val open = wallet ?: error("press Connect first")
        block(open)
    }

    /** Every SDK call is suspending; nothing touches the main thread. */
    private fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            line("· $label…")
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (failure: SdkException) {
                // Messages, codes and details are documented as redacted and
                // safe to log. Always record the code and correlationId: the
                // code is the stable contract (never branch on the message),
                // and the correlationId is what the operator greps for.
                line("✗ $label failed: ${failure.code.value}: ${failure.message}")
                failure.correlationId?.let { line("   correlationId=$it") }
                if (failure.details.isNotEmpty()) line("   details=${failure.details}")
            } catch (failure: Exception) {
                line("✗ $label failed: ${failure.message}")
            }
        }
    }

    /** Callable from any thread: every SDK call runs off the main thread. */
    private fun line(text: String) {
        runOnUiThread { log.append(text + "\n") }
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        urlField = EditText(this).apply {
            hint = "gateway base URL"
            setText(GATEWAY_URL)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        tokenField = EditText(this).apply {
            hint = "bearer token (utxg_…)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        log = TextView(this).apply { setPadding(0, pad, 0, pad); setTextIsSelectable(true) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        listOf(
            "Connect" to ::onConnect,
            "Address" to ::onAddress,
            "Balance" to ::onBalance,
            "Sync" to ::onSync,
            "Unspents" to ::onUnspents,
            "Send" to ::onSend,
        ).forEach { (label, action) ->
            buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(urlField)
            addView(tokenField)
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
        /** Point this at your deployment; `10.0.2.2` is the emulator's host. */
        const val GATEWAY_URL: String = "http://10.0.2.2:8480"
        val NETWORK: Network = Network.SIGNET
        const val WALLET_ID: String = "example"

        /**
         * Set to a 12-word mnemonic to restore an existing wallet on first run.
         * Left null, Connect generates a fresh wallet and prints the mnemonic
         * once. A real app never hardcodes this — it is here so a scripted demo
         * can reach a funded wallet.
         */
        val PROVISIONED_MNEMONIC: String? = null
    }
}

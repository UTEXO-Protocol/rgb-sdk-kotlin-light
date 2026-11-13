# Getting started with the UTEXO Kotlin SDK

This guide takes an Android app from an empty project to its first BTC payment
and its first RGB asset transfer. It covers installing the SDK, setting up a
wallet, and both payment flows end to end, including what the receiving wallet
has to do. The [README](../README.md) is the reference for every limit, error
code and lifecycle method; this guide links to it where you need more detail.

The [example app](../example-app) runs every step below against a real gateway.
Its [`MainActivity`](../example-app/src/main/kotlin/com/utexo/rgb/sdk/example/MainActivity.kt)
is a compact, working version of the code in this guide.

## How a payment works

Your app holds the keys. The UTEXO wallet gateway does everything else: it syncs
the chain, tracks RGB state, builds transactions and broadcasts them. Every
spend takes the same path:

1. The gateway prepares an unsigned transaction for your request.
2. The SDK checks it on the device against your request and against a chain
   tip that your app reads from its own trusted source.
3. The SDK signs it locally.
4. The gateway submits it.

The gateway never sees the seed. For BTC, the device checks the recipient,
amount, change and fee before signing, so the gateway can't redirect a payment.
For RGB, the device checks the Bitcoin side of the transaction and the invoice,
but trusts the gateway for RGB state, allocations and consignments.

The cost of that check is the chain tip. Without one, balances and invoices
still work, but every new spend fails.

## What you need from UTEXO

- **An API key.**
- **The wallet gateway URL** for your account. It's an origin only: scheme, host
  and optional port, with no path. A plain RLN, Cloud API or Swap API URL won't
  work in its place.
- **The network** your gateway runs. The SDK accepts `Network.MAINNET`,
  `TESTNET`, `SIGNET` and `REGTEST`. UTEXO's stage gateway runs on Bitcoin's
  public signet and uses `Network.SIGNET`.
- **For RGB, an asset contract ID** your gateway supports. The send path handles
  fungible assets (NIA, CFA and IFA). It can't send UDA assets. BFA, which current
  Mint-issued USDT uses, can be received and listed if your gateway enables it.
  Sending it hasn't been tested.

Your app needs `minSdk` 24 and Kotlin coroutines. The SDK ships native signing
libraries for `arm64-v8a`, `armeabi-v7a` and `x86_64`.

## 1. Add the SDK

The SDK isn't on a public Maven repository yet. Build it from source and
publish it to a local repository:

```sh
git clone https://github.com/UTEXO-Protocol/rgb-sdk-kotlin-light.git
cd rgb-sdk-kotlin-light
./gradlew :sdk:publishReleasePublicationToValidationLocalRepository
```

The build needs JDK 17 and an Android SDK, set through `ANDROID_HOME` or
`local.properties`. The native signing libraries are already in the repository,
so you don't need a Rust toolchain.

Add that repository to your app's `settings.gradle.kts`, using your checkout's
absolute path:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("/absolute/path/rgb-sdk-kotlin-light/build/validation-maven")
            content { includeGroup("com.utexo.rgb") }
        }
    }
}
```

Then add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT")
}
```

Depend on the Maven publication rather than copying the AAR, so Gradle also
pulls in the SDK's coroutine, HTTP, JSON and JNA dependencies. The AAR brings
its own R8 rules and the internet permission. If UTEXO gives you a versioned
release, use the coordinates and repository that come with it.

## 2. Set up the wallet

The snippets use `import com.utexo.rgb.sdk.*`. Every wallet and storage call is
a `suspend` function, so call them from a coroutine.

### Choose a wallet ID

```kotlin
val walletId = WalletId.of("customer-wallet-42")
val network = Network.SIGNET
```

Create the wallet ID once and persist it. The SDK keys all of its storage (the
seed, the API key and its operation journal) by wallet ID and network, so the
same pair reopens the same wallet. A wallet ID is also bound to the gateway
account it first registers with. Reusing it with another account's API key, or
another gateway, fails with `wallet.invalid_state`.

### Store the API key

`apiKeyChars` is a `CharArray` from your app's provisioning flow:

```kotlin
val apiKeyStore = AndroidKeystoreAPIKeyStore(applicationContext, walletId, network)
try {
    WalletAPIKey.fromCharacters(apiKeyChars).use { apiKeyStore.store(it) }
} finally {
    apiKeyChars.fill('\u0000')
}
```

Store the key once during onboarding, and again only to rotate it. Pass the key
itself, without a `Bearer ` prefix. The SDK reads it for each request, so a
rotation takes effect without reopening the wallet. Never put the key in source
code, `BuildConfig` or logs.

### Create or restore the seed

Do this once, before the wallet is first opened. For a new wallet,
`generateKeys` returns a 12-word recovery phrase:

```kotlin
val seedStore = AndroidKeystoreSeedStore(applicationContext, walletId, network)

generateKeys(network).use { keys ->
    val mnemonic = checkNotNull(keys.mnemonic)
    mnemonic.useWords { words ->
        // Show the words to the user and confirm they wrote them down.
        // Don't log, keep or upload this array.
    }
    seedStore.provisionFromMnemonic(mnemonic)
}
```

To restore an existing wallet, provision from the user's phrase instead.
`recoveryWords` is a `CharArray`:

```kotlin
try {
    SecretMnemonic.fromWords(recoveryWords).use { seedStore.provisionFromMnemonic(it) }
} finally {
    recoveryWords.fill('\u0000')
}
```

Provisioning never replaces a seed. If one is already stored, it throws
`SdkException.InvalidState` with `details["state"] == "already provisioned"` and
leaves the stored seed alone. Track onboarding in your own app state rather than
provisioning on every launch.

The recovery phrase is the only backup of the keys. The SDK keeps the seed in
app-private storage that Android backup excludes, so clearing the app's data
removes it. A restored phrase brings back the keys, not your pending-payment
records or the SDK's operation journal.

The default seed store needs no user authentication. To require biometrics or
the device credential, choose that policy before provisioning; see
[Android authentication and lifecycle](../README.md#android-authentication-and-lifecycle).

### Supply a trusted chain tip

The SDK asks your app for the current block height before it signs anything.
Read it from a node or indexer you trust, not from the gateway:

```kotlin
val trustedTip = WalletChainContextProvider { network ->
    val height = withContext(Dispatchers.IO) {
        val connection = URL(TIP_URL).openConnection() as HttpURLConnection
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
```

The tip has to be from the chain your gateway runs on, and no more than 300
seconds old. Stamp it with the time you actually read it.

UTEXO's stage gateway runs on Bitcoin's public signet; use a public-signet
indexer such as `https://mempool.space/signet/api/blocks/tip/height`. A tip
from another chain is far off the gateway's height, and the signer rejects every
spend with `policy.rejected`.

### Open and unlock the wallet

```kotlin
val wallet = UTEXOWallet.create(
    context = applicationContext,
    configuration = WalletConfiguration(walletId = walletId, network = network),
    service = WalletServiceConfiguration(
        baseUrl = gatewayBaseUrl,
        apiKeyProvider = apiKeyStore,
        chainContextProvider = trustedTip,
    ),
)
wallet.init()   // starts the gateway session; safe to call again
wallet.unlock() // loads the seed and registers watch-only public keys
```

Keep this instance for the user's session and call `wallet.destroy()` when the
session ends. Only one instance can own a wallet's storage at a time, so destroy
the old instance before creating another for the same wallet. To pause on
backgrounding or device lock, use `shutdown()` and later `reinit()` with
`unlock()`; the README's
[lifecycle table](../README.md#android-authentication-and-lifecycle) lists
each method.

The first call for a newly registered wallet can fail with
`backend.unavailable` while the gateway creates the wallet on its side. That
error is retryable, so try the call again.

## Send BTC

| Step | Wallet | Calls |
| --- | --- | --- |
| 1. Share an address | Receiver | `getAddress()` |
| 2. Check the balance | Sender | `syncWallet()`, `getBtcBalance()` |
| 3. Choose a fee rate | Sender | `estimateFeeRate()` |
| 4. Save the payment, then send | Sender | `sendBtc()` |
| 5. See the payment arrive | Receiver | `syncWallet()`, `getBtcBalance()` |

### 1. The receiver shares an address

```kotlin
val address = wallet.getAddress()
// Show address.value as text or a QR code.
```

Each call allocates a new address, which the SDK re-derives from the local seed
before returning it. Keep the address you showed the user; calling
`getAddress()` again returns a different one.

### 2. The sender checks its balance

```kotlin
wallet.syncWallet()
val btc = wallet.getBtcBalance()
val spendableSats = btc.vanilla.spendable.value
```

The balance comes in two parts. `vanilla` is plain BTC, and it's what `sendBtc`
spends. `colored` is BTC on outputs set aside for RGB assets (see
[Send an RGB asset](#send-an-rgb-asset)). Within each part, `settled` counts
confirmed funds, `future` also counts pending ones, and `spendable` is what a
new payment can use now.

### 3. The sender chooses a fee rate

```kotlin
val feeRate = wallet.estimateFeeRate(BlockCount.of(6)).feeRate
```

The target is a number of blocks, from 1 to 1,008. If the gateway can't
estimate for that target, the call throws `configuration.unsupported`; fall back
to a fixed rate such as `FeeRate.satPerVbyte("2")`, which is also the request
default. The gateway accepts whole sat/vB only. By default the device refuses
to sign above 100 sat/vB or above 1,000,000 sats of total fee; you can lower
those limits with `LocalSigningPolicy` in `WalletConfiguration`.

### 4. The sender saves the payment, then sends it

```kotlin
val request = SendBtcRequest(
    address = BitcoinAddress.of(recipientText),
    amount = Satoshis.of(50_000),
    feeRate = feeRate,
    idempotencyKey = IdempotencyKey.random(),
)
// Commit every request field, including the key, to your database first.
payments.insertPending(request)
val txid = wallet.sendBtc(request)
payments.markSubmitted(request.idempotencyKey, txid.hex)
```

`payments` stands for your app's own database. Saving the request before the
call is what makes a retry safe after a crash or timeout; see
[Retry safely](#retry-safely-after-a-crash-or-timeout).

The amount must be at least 294 sats, and the device may refuse an output below
the dust limit for the recipient's address type. A returned txid means the
gateway accepted the signed transaction. It doesn't mean the transaction is
confirmed.

### 5. The receiver sees the payment

```kotlin
wallet.syncWallet()
val received = wallet.getBtcBalance().vanilla
```

The payment shows in `received.future` once the receiver syncs, and in
`received.settled` after it confirms. To look up a transaction on the stage
gateway's public signet, use `https://mempool.space/signet/tx/<txid>`.

## Send an RGB asset

### Colored UTXOs and invoices

RGB assets live on Bitcoin outputs. The wallet's `colored` balance is BTC on
outputs that can hold assets. An RGB send pays everything from colored outputs:
the Bitcoin fee, the change, and any new output it creates for the receiver. It
never touches `vanilla`. So a wallet needs colored UTXOs before it can send,
even if it already holds the asset.

The receiver starts every transfer by creating an invoice. There are two kinds:

| | Witness invoice | Blind invoice |
| --- | --- | --- |
| Created with | `onchainReceive(OnchainReceiveRequest(witness = true))`, the default | `witness = false` |
| Where the asset lands | A new output the sender creates and funds | One of the receiver's existing colored UTXOs |
| Receiver needs colored UTXOs | No | Yes |
| Sender pays extra BTC | Yes, for the new output (`witnessData`) | No |
| Recipient ID contains | `wvout:` | `utxob:` |

Use a witness invoice when the receiver has no colored UTXOs, such as a new
wallet. Use a blind invoice when the receiver already has a free colored UTXO
and you don't want the sender to fund a new output.

Asset amounts are integers in the asset's base units. Apply the asset's
`precision` only for display: `1000000` at precision 6 is 1 token.

### 1. The receiver creates an invoice

```kotlin
val receive = wallet.onchainReceive(
    OnchainReceiveRequest(
        assetId = AssetId.of(assetContractId),
        amount = AssetAmount.parse("1000000"),
        duration = DurationSeconds.of(3_600),
    ),
)
val invoiceText = receive.invoice.value // Send this to the payer as text or a QR code.
```

The gateway can only put an asset in an invoice if the receiving wallet already
knows that asset. For a wallet's first receipt of an asset, leave `assetId` out;
the sender then names the asset in its send request.

The SDK decodes the invoice the gateway returns and checks its network, asset,
amount, type and expiry against your request. The gateway sets the expiry from
its own clock, so the device clock must be within 300 seconds of the gateway's.
The duration can be from 60 seconds to 30 days, and defaults to 24 hours.

### 2. The sender creates colored UTXOs

```kotlin
val created = wallet.createUtxos(
    CreateUtxosRequest(
        num = 5,
        size = Satoshis.of(1_000),
        feeRate = FeeRate.satPerVbyte("2"),
        idempotencyKey = IdempotencyKey.random(),
    ),
)
val count = created.createdUtxoCount
```

This spends vanilla BTC to create colored outputs; the defaults are 5 outputs of
1,000 sats each. Use `createdUtxoCount` as the number actually created, not
`num`. Wait for the transaction to confirm before sending RGB from these
outputs. `createUtxos` signs and submits a transaction, so save the request
before calling it, as you would a payment.

A wallet that received an asset on a single 1,000-sat witness output still
needs this step: that output alone can't pay for the send.

### 3. The sender decodes the invoice

```kotlin
val invoice = RgbInvoice.of(invoiceText)
val decoded = wallet.decodeRgbInvoice(invoice)
val isWitnessInvoice = "wvout:" in decoded.recipientId
```

Show the user what the invoice asks for before they confirm: `decoded.assetId`,
`decoded.assignment.amount`, and `decoded.expirationTimestamp`. Decoding fails
for an invoice from another network. It checks the invoice's syntax and
contents, not the asset's RGB history.

### 4. The sender sends

```kotlin
val request = OnchainSendRequest(
    invoice = invoice,
    assetId = AssetId.of(assetContractId),
    amount = AssetAmount.parse("1000000"),
    witnessData = if (isWitnessInvoice) WitnessData(amountSat = Satoshis.of(1_000)) else null,
    feeRate = FeeRate.satPerVbyte("2"),
    idempotencyKey = IdempotencyKey.random(),
)
// Commit every request field, including the key, to your database first.
val sent = wallet.onchainSend(request)
val txid = sent.txid
```

- `amount` is required. If the invoice names an amount, they must match.
- `assetId` is required when the invoice doesn't name an asset. If both name
  one, they must match.
- `witnessData` is required for a witness invoice and must be left out for a
  blind one. Its `amountSat` is the BTC that goes to the receiver's new output,
  at least 294 sats.
- The invoice must carry between one and four transport endpoints, such as
  `rpcs://stage.rgb-proxy.utexo.com/json-rpc` on the stage gateway. Invoices
  without endpoints aren't supported.
- A proxy protocol 1.0 invoice also carries the receiver's key as `pk=…`, and
  may carry `enc`. The SDK passes them to the gateway, which needs them to
  encrypt the transfer to the receiver.

`sent.batchTransferIdx` may be null; use the txid to find the transfer.

### 5. Both wallets sync until the transfer settles

The txid that `onchainSend` returns is not on chain yet. An RGB transfer needs
both wallets to take part, and the sender broadcasts only after the receiver
acknowledges the transfer:

1. **The receiver syncs.** It fetches the transfer data (the consignment) from
   the RGB proxy, validates it and acknowledges it.
2. **The sender syncs.** It sees the acknowledgement and broadcasts the
   transaction. Only now is the txid on chain.
3. **After the transaction confirms, both sync again.** The receiver's sync
   settles the incoming transfer, and the sender's settles its side.

Each step is a `syncWallet()` call, which syncs Bitcoin and refreshes RGB state
in one gateway request. UTEXO's RGB proxy acknowledges a transfer as soon as the
sender uploads it, so the sender can broadcast on its next sync without waiting
for the receiver. The receiver still has to sync to accept the asset.

In practice, have each app call `syncWallet()` while it has a transfer pending,
for example when the user opens the wallet or the transfers screen.

### 6. Track the transfer

```kotlin
wallet.syncWallet()
val transfers = wallet.listTransfersByTxid(sent.txid)
val settled = transfers.isNotEmpty() && transfers.all { it.status == TransferStatus.SETTLED }
val balance = wallet.getAssetBalance(AssetId.of(assetContractId))
```

`listTransfers(assetId)` lists every transfer for an asset, and each transfer's
`kind` says whether it's a `Send`, `ReceiveWitness` or `ReceiveBlind`.
`listTransfersByTxid` and `listTransfers()` without an asset ID search every
asset, so they make one gateway request per asset. The statuses you'll see
most are:

| Status | Meaning |
| --- | --- |
| `WaitingCounterparty` | The other wallet hasn't taken its step yet. For an incoming transfer, the receiver hasn't accepted a consignment. |
| `WaitingConfirmations` | The transfer is accepted, and its transaction is waiting for confirmations. |
| `Settled` | The transfer is complete. |
| `Failed` | The transfer failed or expired. |

The gateway can return other statuses, including ones added later. Treat any
status you don't recognize as pending, and keep its `wireValue`.

Asset balance fields are nullable, and null means unavailable, not zero. While
an incoming transfer waits for confirmations, the amount shows in the
receiver's `future` balance; it moves to `settled` and `spendable` once the
transfer settles.

## Retry safely after a crash or timeout

This applies to `sendBtc`, `createUtxos` and `onchainSend`, the calls that sign
and submit a transaction.

A timeout, process death or coroutine cancellation can happen after a
transaction was already submitted. Retrying is safe if you retry with the
**same request and idempotency key**. If the operation was already submitted,
the SDK checks its status instead of signing and submitting again. If it
already succeeded, the SDK returns the saved result. A new key means a new
payment, even for the same amount and recipient.

So before each call, commit a record of the request to your database in one
transaction. For `sendBtc`, store:

| Field | Store |
| --- | --- |
| Your payment ID | A unique ID created once for the user's approved payment |
| Wallet | Wallet ID, network, gateway URL and your own account ID |
| Address | `request.address.value` |
| Amount | `request.amount.value`, in sats |
| Fee | `request.feeRate.milliSatsPerVbyte`, as an integer |
| `skipSync` | The value you sent, even if it's the default |
| Idempotency key | `request.idempotencyKey.value` |
| Outcome | Pending until you've saved the txid |

For `onchainSend`, also store `invoice.value`, `amount.decimal`,
`assetId?.value`, `witnessData?.amountSat?.value`, `donation` and
`minConfirmations.value`. Never store the recovery phrase, seed or API key in
this record. If saving the record fails, don't call the SDK.

To retry, rebuild the request from the record, setting every field explicitly:

```kotlin
val request = SendBtcRequest(
    address = BitcoinAddress.of(record.address),
    amount = Satoshis.of(record.amountSats),
    feeRate = FeeRate.milliSatsPerVbyte(record.feeMilliSatsPerVbyte),
    skipSync = record.skipSync,
    idempotencyKey = IdempotencyKey.of(record.idempotencyKey),
)
val txid = wallet.sendBtc(request)
```

Don't rely on constructor defaults here: an omitted `idempotencyKey` generates
a new random key, which starts a new payment. After a restart, reopen and unlock
the same wallet before retrying. Recovering an already submitted operation
doesn't need the chain tip provider or an unexpired invoice.

Handle failures by `SdkException.code`, never by the message:

| Code | What to do |
| --- | --- |
| `operation.ambiguous` | The outcome is unknown. Keep the payment pending and retry the same request with backoff. |
| `backend.unavailable` | Retry with backoff. For a payment, retry with the same request and key. |
| `policy.rejected` | The device refused to sign. Check the fee, the invoice and the chain tip. |
| `operation.conflict` | This key was already used for a different request. Load the original record and investigate. |
| `validation.invalid_argument`, `validation.wrong_network`, `configuration.unsupported` | Fix the request or configuration before sending again. |

`retryable` on an exception means the same request may succeed later. It is not
permission to create a second payment. Let `CancellationException` propagate;
cancelling a coroutine doesn't undo a broadcast. The README's
[recovery section](../README.md#persist-payments-and-recover-after-interruption)
covers the remaining codes.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Every spend fails with `policy.rejected` | The chain tip is from the wrong chain or older than 300 seconds. For the stage gateway, read it from a public-signet source such as `mempool.space/signet/api`. |
| Creating an invoice fails with `backend.protocol_mismatch` about expiry | The device and gateway clocks differ by more than 300 seconds. Fix the device clock. |
| The first call fails with `backend.unavailable` | The gateway is still creating a newly registered wallet. Retry the call. |
| An RGB send, or a blind invoice, fails with `backend.rejected` (`INSUFFICIENT_FUNDS`) while the wallet has BTC | Both need colored UTXOs. Call `createUtxos`, wait for a confirmation, and retry. |
| An incoming transfer stays `WaitingCounterparty` | The receiver hasn't accepted a consignment. Sync the receiver, then the sender. |
| `init()` fails with `wallet.invalid_state` | The wallet ID is already bound to another gateway account or URL. Use the original API key and gateway, or a new wallet ID. |

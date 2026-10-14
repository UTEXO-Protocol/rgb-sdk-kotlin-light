# UTEXO Lightweight Kotlin SDK

A lightweight Android SDK for Bitcoin and on-chain RGB wallets. Use
`UTEXOWallet` to open a wallet session, receive BTC, prepare RGB-capable UTXOs,
read balances, create RGB invoices, and send BTC or RGB assets. Private keys and
Bitcoin transaction signing stay on the device; a UTEXO wallet gateway handles
chain synchronization, RGB state, transaction preparation, and broadcast.

**An API key is required. Contact [UTEXO](https://utexo.com/contact-sales)
([sales@utexo.com](mailto:sales@utexo.com)) to obtain an API key and the
SDK-compatible wallet gateway URL for your account and network.** The API key
goes into the SDK through `WalletServiceConfiguration.apiKeyProvider`, as shown
below. Keep it in protected storage and supply the key itself, without a
`Bearer ` prefix. The SDK adds the HTTP authorization header.

[UTEXO](https://docs.utexo.com/overview) provides infrastructure for RGB assets and
Bitcoin payments. [RGB](https://docs.utexo.com/getting-started/rgb-concepts) tracks asset ownership against Bitcoin UTXOs and exchanges
asset state between participants. [RGB Lightning Node (RLN)](https://github.com/UTEXO-Protocol/rgb-lightning-node) supplies Bitcoin/RGB
wallet and Lightning infrastructure; this SDK connects through a wallet gateway
rather than exposing the full RLN API. **This build has no public Lightning
payment, BOLT11 invoice, or channel-management API.**

The gateway contract is provisional, with known crash/expiry recovery and RGB
input-reservation limitations. Confirm deployment and recovery expectations with
UTEXO before using real funds; this integration has not been qualified as
production-ready.

## Before you integrate

You need:

- An Android app with minimum API 24 and Kotlin coroutine support.
- The SDK source or an artifact supplied by UTEXO. The build includes native
  signing libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- Your UTEXO API key, compatible gateway URL, supported network, and RGB asset
  contract ID/schema. A plain RLN endpoint, Cloud API, or Swap API URL is not a
  substitute for this gateway.
- A stable, app-persisted `WalletId`, a recovery-phrase backup flow, and durable
  storage for pending payment requests.
- For **new spending**, a `WalletChainContextProvider` backed by a trusted
  Bitcoin node/indexer independent of the gateway's proposed transaction.

The factory currently accepts `Network.MAINNET`, `TESTNET`, `SIGNET`, and
`REGTEST`; the gateway must serve the selected network. `Network.UTEXO` and
`TESTNET4` exist in the shared model but are rejected by the shipped gateway
adapter. Select the environment UTEXO provisions for you.

## Install from source

The current build declares `com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT`
and publishes to a local validation Maven repository. It does not configure a
public remote Maven repository. For a local integration, clone the repository
with an authorized GitHub account and publish its AAR and dependency metadata:

```sh
git clone https://github.com/UTEXO-Protocol/rgb-sdk-kotlin-light.git
cd rgb-sdk-kotlin-light
./gradlew :sdk:publishReleasePublicationToValidationLocalRepository
```

The source build uses JDK 17, Android Gradle Plugin 9.1.0, Gradle 9.3.1, Android
SDK 36, and NDK 28.2.13676358. Configure your Android SDK path through
`ANDROID_HOME` or `local.properties` before building. Native signing binaries
are already included.

In your app's `settings.gradle.kts`, add the generated repository alongside your
existing repositories, replacing the path with your checkout's absolute path:

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

Add the dependency in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT")
}
```

Use the Maven publication so Gradle resolves the coroutine, HTTP, JSON, and
Android JNA dependencies. The AAR includes consumer R8 rules and the internet
permission. If UTEXO supplies a versioned release and repository, use the exact
coordinates and repository provided with that release.

## Configure credentials and wallet storage

The examples use `import com.utexo.rgb.sdk.*`. Run wallet and storage calls from
an application coroutine; these methods are `suspend` functions. Keep one wallet
instance per wallet identity. `applicationContext` below is your Android context.

```kotlin
val configuration = WalletConfiguration(
    walletId = WalletId.of("customer-wallet-42"), // Persist once; reuse on reopen.
    network = Network.SIGNET,
)
val storage = WalletStorageConfiguration()
val credentials = AndroidKeystoreAPIKeyStore(
    applicationContext, configuration.walletId, configuration.network,
)
val seedStore = AndroidKeystoreSeedStore(
    applicationContext, configuration.walletId, configuration.network,
    storage.seedStoreConfiguration,
)
```

Store the API key during initial onboarding or a deliberate credential rotation.
Here `apiKeyCharacters` is a `CharArray` received through your application's
protected provisioning flow:

```kotlin
try {
    WalletAPIKey.fromCharacters(apiKeyCharacters).use { apiKey ->
        credentials.store(apiKey)
    }
} finally {
    apiKeyCharacters.fill('\u0000')
}
```

The SDK loads and closes a fresh `WalletAPIKey` for every HTTP request. Rotating
`credentials` takes effect without rebuilding the wallet. Use keys for the same
UTEXO account: wallet storage is bound to its gateway account and origin, and
rejects switching either under an existing wallet ID. Never embed an API key in
source code, `BuildConfig`, logs, or a public example.

The current gateway associates one vanilla/colored account-xpub pair with each
gateway user. Do not reuse that user's credential for independently generated
wallets; changing `WalletId` alone does not create another gateway user. Ask UTEXO
about per-user credential provisioning when integrating a multi-user app.

### Create a new wallet or import a recovery phrase

Provision the seed **once**, before opening the wallet. For a new wallet,
`generateKeys` produces a 12-word English BIP39 mnemonic:

```kotlin
generateKeys(configuration.network).use { keys ->
    val mnemonic = checkNotNull(keys.mnemonic)
    mnemonic.useWords { words ->
        // Complete your protected recovery-phrase display/backup confirmation.
        // Do not log, retain, or upload this temporary character array.
    }
    seedStore.provisionFromMnemonic(mnemonic)
}
```

Complete your app's backup flow before provisioning; the comment above is its
integration point. `generateKeys` creates key material; it does not register or
fund a wallet, or store it automatically.

For an import, use the user's verified phrase. `recoveryWords` is an app-supplied
`CharArray`; a BIP39 passphrase, if used originally, must also match:

```kotlin
try {
    SecretMnemonic.fromWords(recoveryWords).use { mnemonic ->
        seedStore.provisionFromMnemonic(mnemonic)
    }
} finally {
    recoveryWords.fill('\u0000')
}
```

`provisionFromMnemonic(mnemonic, passphrase)` also accepts a `CharArray`
passphrase; clear it after use. Provisioning refuses to overwrite existing seed
storage. Do not run it during every launch or use it to resolve an ordinary unlock
error. Reopening uses the same wallet ID, network, and storage configuration.

## Open and unlock the wallet

Supply the **gateway origin** provided by UTEXO, such as
`https://your-wallet-gateway.example`; it must contain only scheme, host, and
optional port, with no API path, query, or embedded credentials.

```kotlin
val service = WalletServiceConfiguration(
    baseUrl = gatewayBaseUrl, // SDK-compatible URL supplied by UTEXO.
    apiKeyProvider = credentials, // Contains your UTEXO API key.
    chainContextProvider = trustedChainContextProvider,
)
val wallet = UTEXOWallet.create(
    context = applicationContext,
    configuration = configuration,
    service = service,
    storage = storage,
)
try {
    wallet.init()
    wallet.unlock()
    val balance = wallet.getBtcBalance()
    val spendableSats = balance.vanilla.spendable.value
    // Keep using this instance for the authenticated application session.
} finally {
    wallet.destroy() // Close when the session ends, not after every method.
}
```

`trustedChainContextProvider` is your `WalletChainContextProvider`
implementation. Its `suspend loadChainContext(network)` must return:

```kotlin
WalletChainContext(
    network = requestedNetwork,
    tipHeight = independentlyObservedHeight,
    observedAt = UnixSeconds.of(actualObservationEpochSeconds),
)
```

The observation must match the requested network, be at most 300 seconds old,
and be no more than 30 seconds in the future. Fetch from your trusted node/indexer;
do not copy a height from a PSBT or assign a new timestamp to an old observation.
The SDK refreshes this context before sensitive signing/submission transitions.
The gateway's `syncWallet()` result does not supply a trusted tip for this purpose.

You may omit `chainContextProvider` for balance/invoice reads and recovery of
already-submitted operations. New `sendBtc`, `createUtxos`, and `onchainSend`
operations then fail closed. After unlock, `wallet.capabilities` reports the
local features enabled for that instance.

### Android authentication and lifecycle

The default seed-store policy uses Android Keystore encryption with no user
authentication requirement. For an authenticated store, choose the policy
**before provisioning** and pass the same storage configuration on reopen:

```kotlin
val storage = WalletStorageConfiguration(
    seedStoreConfiguration = AndroidKeystoreSeedStoreConfiguration(
        strongBoxPolicy = StrongBoxPolicy.PREFERRED_WITH_KEYSTORE_FALLBACK,
        authentication = AndroidKeyAuthentication.BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL,
        authenticationValiditySeconds = 30,
    ),
)
```

Authenticate through your app's Android biometric/device-credential flow before
provisioning or `unlock()`. The API uses an authentication validity window;
`unlock()` does not launch a prompt or accept an SDK password for this store.
StrongBox protects the wrapping key when available under the selected policy;
Bitcoin signatures are made in the native process after unlock. Changing the
policy for existing ciphertext is not a supported migration.

| Method | Use |
| --- | --- |
| `init()` / `initialize()` | Initialize the gateway session; safe to call again. |
| `unlock()` | Load the protected seed locally and register watch-only public keys. |
| `shutdown()` | Close signer/session while keeping storage ownership and data. |
| `reinit()` then `unlock()` | Resume a shut-down instance after app authentication. |
| `destroy()` / `dispose()` | Permanently close the instance and release ownership; data is retained. |

All wallet feature methods require unlock. On logout, backgrounding, or device
lock, stop issuing calls and await `shutdown()` according to your app's session
policy. The SDK installs no automatic lifecycle observer. Destroy the old instance
before creating another for the same wallet; shutdown retains its exclusive
storage lease. Calls are serialized, so shutdown waits behind active work and
cannot undo a broadcast.

## Receive BTC and prepare RGB UTXOs

The following workflow examples run inside an initialized, unlocked session:

```kotlin
val receivingAddress = wallet.getAddress()
// Display receivingAddress.value as text/QR; fund it on configuration.network.
wallet.syncWallet()
val btc = wallet.getBtcBalance()
val availableSats = btc.vanilla.spendable.value
val unspents = wallet.listUnspents()
```

`getAddress()` and `rotateVanillaAddress()` each allocate a fresh, locally verified
vanilla external address. Retain the returned address for your receive screen;
repeated calls do not return a pinned address. Use BTC on the selected network
and wait for the confirmations your workflow requires. RGB operations need BTC
for fees and suitable UTXOs even when transferring an asset.

To prepare outputs for RGB allocations:

```kotlin
val request = CreateUtxosRequest(
    upTo = true,
    num = 5,
    size = Satoshis.of(1_000),
    feeRate = FeeRate.satPerVbyte("2"),
    idempotencyKey = IdempotencyKey.random(),
)
// Commit the exact request and key to your app database BEFORE this call.
val result = wallet.createUtxos(request)
val created = result.createdUtxoCount // Authoritative count, not request.num.
val transactionId = result.txid.hex
```

Persist this request just as you would a payment. The gateway accepts 1–50
requested outputs; owned Taproot outputs must be at least 330 sats. Omitting
`num`/`size` selects gateway defaults of 5 outputs and 1,000 sats. `upTo` controls
the gateway's target behavior; use the returned count as the result.

## Send BTC

```kotlin
val request = SendBtcRequest(
    address = BitcoinAddress.of(recipientAddressText),
    amount = Satoshis.of(50_000),
    feeRate = FeeRate.satPerVbyte("2"),
    idempotencyKey = IdempotencyKey.random(),
)
// Commit the exact request and key BEFORE dispatch. A retry reloads this record.
val txid = wallet.sendBtc(request)
// Commit txid.hex to the same payment record before reporting success.
```

The backend prepares the transaction; the device validates Bitcoin ownership,
recipient, change, and fee policy, signs, then asks the gateway to complete it.
The gateway requires at least 294 sats; the local signer may reject a recipient
output below its script-specific dust limit. A returned txid is the accepted
operation result, not a confirmation or settlement guarantee.

## Receive and send RGB assets

Use an asset contract ID and schema supported by your gateway. The current asset
models/parser cover NIA, CFA, UDA, and IFA; the on-chain send path supports
fungible owner payments only. UDA recognition does not enable non-fungible sends.
BFA assets are not supported by this build. Current
[Mint-issued USDT](https://docs.utexo.com/getting-started/rgb-concepts) uses BFA;
confirm a token's schema and SDK compatibility with UTEXO before integrating it.

Create a witness receive invoice and share its encoded value with the sender:

```kotlin
val assetId = AssetId.of(assetContractId)
val receive = wallet.onchainReceive(
    OnchainReceiveRequest(
        assetId = assetId,
        amount = AssetAmount.parse("1000000"),
        duration = DurationSeconds.of(3_600),
        minConfirmations = BlockCount.of(1),
        witness = true,
    ),
)
val encodedInvoice = receive.invoice.value // Share privately as text/QR.
val decoded = wallet.decodeRgbInvoice(receive.invoice)
```

The named-asset example requires the gateway wallet to already know that asset.
For the first receipt of an unknown asset, create a blind invoice without an
`assetId`, backed by an existing suitable UTXO:

```kotlin
val firstReceive = wallet.onchainReceive(
    OnchainReceiveRequest(witness = false, duration = DurationSeconds.of(3_600)),
)
val firstInvoiceText = firstReceive.invoice.value
```

The sender specifies the agreed `assetId` and amount in `OnchainSendRequest`,
and omits `witnessData` for that blind invoice. Refresh the receiver after the
transfer so the asset becomes available for named-asset invoices and balances.

Set `witness = false` for a blind invoice backed by an existing suitable UTXO.
The facade verifies the invoice's network, requested asset/amount, mode,
recipient, and expiry. Decoding checks syntax and intent; it does not validate an
RGB contract/consignment or prove gateway beneficiary ownership. Inspecting an
expired invoice is possible; a new send rejects it.

For a **witness invoice**, include the BTC amount for the witness output:

```kotlin
val request = OnchainSendRequest(
    invoice = RgbInvoice.of(witnessInvoiceText),
    assetId = AssetId.of(assetContractId),
    amount = AssetAmount.parse("1000000"),
    witnessData = WitnessData(amountSat = Satoshis.of(1_000)),
    feeRate = FeeRate.satPerVbyte("2"),
    minConfirmations = BlockCount.of(1),
    idempotencyKey = IdempotencyKey.random(),
)
// Commit every request field and its key BEFORE dispatch.
val sent = wallet.onchainSend(request)
val transactionId = sent.txid.hex
```

For a **blind invoice**, omit `witnessData`. The asset must be explicit in the
request or invoice; when both specify it, they must agree. `amount` is required,
and must match an amount encoded in the invoice. Keep the invoice and transport
endpoints intact. The send route requires 1–4 unique transport endpoints accepted
by the gateway; invoices using out-of-band consignment exchange without endpoints
are unsupported. `batchTransferIdx` may be null after sending: the current
completion route supplies the txid without a batch index.

Refresh and inspect transfer state before presenting settlement:

```kotlin
wallet.refreshWallet()
val assets = wallet.listAssets()
val balance = wallet.getAssetBalance(AssetId.of(assetContractId))
val transfers = wallet.listTransfers(AssetId.of(assetContractId))
val related = wallet.listTransfersByTxid(sent.txid)
```

Asset balance fields are nullable; null means unavailable, not zero. Transfer
states include waiting for the counterparty, confirmations, settlement, and
failure. Preserve unknown future status strings.

## Units and current gateway limits

| Value/API | Integration rule |
| --- | --- |
| `Satoshis` | Integer BTC base units; 100,000,000 sats = 1 BTC. |
| `AssetAmount` | Unsigned integer RGB base units. Apply asset `precision` for display; `1000000` at precision 6 displays as 1. |
| `FeeRate` | Sat/vB at construction; stored as exact `milliSatsPerVbyte`. Default is 2 sat/vB. |
| Fee requests | Gateway requires whole sat/vB. Default local caps: 100 sat/vB and 1,000,000 sats total fee. Tighten with `LocalSigningPolicy`. |
| `estimateFeeRate(BlockCount.of(n))` | Targets 1–1,008; may return `Unsupported` if the cached estimate cannot satisfy the target. Confirm fees with the user. |
| RGB send/receive amounts | Models retain unsigned 64-bit values; gateway requests allow 1–9,007,199,254,740,991 units for lossless JSON. |
| Receive duration / confirmations | 60–2,592,000 seconds; default duration 86,400. RGB confirmation counts: 0–100. |
| `skipSync` / witness blinding | `skipSync = true` for spending and custom witness blinding are unsupported. |
| `blindReceive()` / `witnessReceive()` | Require a gateway batch index; use `onchainReceive(witness = ...)` when unavailable. |
| Issuance / inflation | `issueAssetNia`, `issueAssetIfa`, and `inflate` are unsupported through the ordinary factory. |
| BTC transaction history / failing transfers | `listTransactions`, `listTransactionsByTxid`, and `failTransfers` are unsupported by this gateway. RGB transfer lists are available. |

## Persist payments and recover after interruption

For `sendBtc`, `createUtxos`, and `onchainSend`, generate an idempotency key once
per user-approved operation and **commit the exact request before invoking the
SDK**. Store wallet/network/account identity, every request field, fee milli-units,
and the key in a durable app record. Reconstruct all values explicitly on retry;
request constructors otherwise generate a new random key.

The SDK also keeps an encrypted operation journal, retaining prepared/signed
transactions and results. If a timeout, process death, or coroutine cancellation
occurs, a transaction may already have been submitted. Load the original app
record, reopen/unlock the same wallet, and repeat the same method with the **same
request and key**. An already-submitting operation reconciles status; it does not
create or automatically submit a replacement payment. A retained successful
receipt replays if the app crashed before committing its own result.

Handle `SdkException` using `code`, `retryable`, `operationId`, and
`correlationId`; do not parse exception messages:

| Failure | Application response |
| --- | --- |
| `WALLET_LOCKED` / `KEY_ACCESS_FAILED` | Initialize/authenticate/unlock as appropriate; respect whether key access is retryable. |
| `INVALID_ARGUMENT` / `WRONG_NETWORK` / `UNSUPPORTED` | Correct configuration or unsupported intent before new dispatch. |
| `POLICY_REJECTED` | Review fees, invoice, chain context, and local policy. Preserve the payment record. |
| `OPERATION_AMBIGUOUS` | Keep pending; reconcile the original request/key with backoff. |
| `OPERATION_CONFLICT` | The key was used for different intent; load the original record and investigate. |
| `BACKEND_UNAVAILABLE` | Retry reads with backoff; retain a payment's exact request/key. |
| `OPERATION_JOURNAL_UNAVAILABLE` | Stop dispatching and investigate storage recovery; do not delete history to retry. |

A `retryable` flag is not permission to create a second payment. Propagate Kotlin
`CancellationException`; cancellation does not reverse broadcast. Gateway
crash/expiry recovery and RGB-input reservation limitations can leave an outcome
pending and require support. See the [durable consumer recovery contract](docs/DURABLE_CONSUMER_RECOVERY.md)
for exact record fields and restart behavior.

## Backup and trust boundaries

Android Keystore protects the encrypted seed, API key, and operation journal in
app-private storage excluded from Android backup. Close secret containers with
`use`; memory cleanup across Kotlin/FFI/native copies is best-effort. The backend
receives watch-only public keys, which are privacy-sensitive even though they
cannot sign.

A recovery phrase restores keys, **not** the SDK journal, pending-payment records,
or server-owned RGB state. Coordinate migration and backend/RGB recovery with
UTEXO. Missing or invalidated Keystore material fails closed. `resetForRecovery`
is an explicit destructive seed-recovery path requiring your app's fresh
authentication and verified backup; it is not a routine error fix. Reopen with
the same wallet ID, network, gateway/account, and custody policy.

Local signing protects Bitcoin intent and fees. This build trusts the gateway
for RGB input selection, allocations, recipient assignment, change, consignments,
and state transitions; it does not independently validate full RGB state. The
journal limit is 64 MiB or 100,000 records, whichever is reached first. Never
delete it to regain capacity or clear an uncertain payment; plan ongoing
recovery/storage support with UTEXO.

See [native artifact documentation](NATIVE_ARTIFACTS.md), [security reporting](SECURITY.md),
and [verification notes](docs/assurance/KOTLIN_RN_REMEDIATION_2026-09-21.md) for
implementation and release evidence. The [Swift lightweight SDK](https://github.com/UTEXO-Protocol/rgb-sdk-swift-light)
is the companion integration for Apple platforms.

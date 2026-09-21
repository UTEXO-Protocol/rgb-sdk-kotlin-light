# UTEXO RGB SDK for Kotlin

This repository is the lightweight Android implementation behind the permanent
`com.utexo.rgb.sdk` package. It keeps wallet keys, Bitcoin transaction-policy
validation, and signatures on the device while a `WalletBackend` owns chain
sync, UTXO discovery, RGB state, transfer construction, and broadcast.

The application-facing contract follows the high-level semantics of
`@utexo/rgb-sdk-rn`: `UTEXOWallet`, `init`, `unlock`, `getNetwork`, `sendBtc`,
`createUtxos`, on-chain send/receive, balances, assets, UTXOs, transfers, and
transactions. Raw RLN and PSBT operations are not application API.

## Current safety status

See [the RN-driven remediation and verification](docs/assurance/KOTLIN_RN_REMEDIATION_2026-09-21.md)
for the current fixes and remaining gates. The [September 20 report](docs/assurance/KOTLIN_REMEDIATION_2026-09-20.md)
and [independent audit](docs/assurance/KOTLIN_INDEPENDENT_REAUDIT_2026-09-19.md)
explains the pre-fix findings. Earlier [closure evidence](SDK_CLOSURE_2026-09-19.md)
is historical, not a claim that only backend blockers remain or that this is an RC.

This is an implementation foundation awaiting the production backend and its
versioned API contract. The generated UniFFI 0.32.1 binding and release native
libraries are checked in for Android `arm64-v8a`, `armeabi-v7a`, and `x86_64`
(minimum API 24). The Gradle build prepares generated code in an isolated build
source so handwritten public code remains under Kotlin explicit-API checking.

The only concrete remote adapter shipped in this phase is
`RgbLightningNodeGatewayBackend`. `WalletBackend` remains an opt-in
infrastructure seam for tests and future adapters; it is not a second
application-facing integration path.

The ordinary factory retains the legacy BTC-only signing profile. The additive
internal RGB-lib profile now implements Bitcoin authorization/signing for BTC
send, create-UTXOs and RGB send, with tests against untouched real gateway PSBTs.
It is not selected by the public factory: fresh spending remains gated.
The current create-UTXO wire adapter, extracted output hints and status-only
recovery are implemented for internal qualification; durable backend crash
recovery still blocks release. RGB send still needs its full result contract.
Inflation lacks a gateway primitive and qualified local
authorization profile. Local capability/intent checks run before risky
transitions, while terminal replay and status-only recovery remain available
without requiring a signing capability or an unexpired RGB invoice.

Local RGB invoice decoding and validated `onchainReceive` are implemented;
`onchainSend` remains unavailable through the current public gateway adapter;
that is distinct from the implemented internal Bitcoin policy. Lightweight v1 cannot independently
validate the RGB state transition: it can protect Bitcoin ownership and fee
policy, but it must trust the backend for RGB input selection, recipient
assignment, RGB change, and consignment state.

Invoice parsing is a size-bounded, canonical RGB invoice implementation in the
shared Rust core, with compatible projections for supported NIA/UDA/CFA/IFA
assignments. It is not full RGB contract/consignment validation. Unknown schemas,
unsupported networks or transports, invalid checksums, duplicate/unknown query
fields, noncanonical encodings and invalid assignment combinations fail closed.
`decodeRgbInvoice` / `decodeRGBInvoice` preserve the input and exact unsigned
64-bit amounts, including expired positive timestamps for inspection. Logical
UTEXO wallets accept the wire Signet network without changing the decoded
network label; no other network is silently treated as equivalent.

## Cryptography and standards

The signing engine is the shared narrow Rust crate `utexo-local-signer`, exposed
internally with UniFFI. See [NATIVE_ARTIFACTS.md](NATIVE_ARTIFACTS.md) for build
provenance and checksums. It intentionally does not depend on full `rgb-lib`,
BDK wallet sync, a database, an HTTP stack, TLS, or migrations.

Pinned security-critical libraries:

- `rust-bitcoin 0.32.102` for BIP32 derivation, PSBT parsing, Bitcoin transaction
  primitives, Taproot sighashes, and secp256k1/BIP340 Schnorr signatures;
- `bip39 2.2.2` for English mnemonic validation and seed derivation;
- `zeroize 1.9.0` for best-effort secret-memory cleanup;
- `UniFFI 0.32.1` and Android JNA `5.17.0` for the private Kotlin/Rust boundary.

The optional PR #174 gateway adapter uses OkHttp `4.12.0` for cancellable
Android HTTP/TLS and kotlinx-serialization JSON `1.6.3` for strict response
parsing. Redirects, automatic connection retries, and non-local cleartext HTTP
are disabled so a bearer credential cannot be forwarded to another origin.
Both success and error bodies receive a constant-space structural preflight
before recursive JSON decoding: at most 64 nested containers, 100,000 lexical
tokens, and the existing 1,500,000-byte HTTP body ceiling. Malformed or excessive
structures produce sanitized typed errors, not stack-overflow recovery.

Every handwritten key/signing entry point performs the generated UniFFI
contract-version and API-checksum validation once before entering operational
native functions or allocating native key buffers. A mismatch remains a sticky
typed failure. This is ABI compatibility checking, not binary authenticity or a
replacement for verified release provenance.

The implemented local candidate profile is `utexo.bip86.v1` /
`utexo.psbt.bip86.v1`:

- BIP39 mnemonic to 64-byte seed;
- BIP32 hierarchical deterministic derivation;
- BIP86 key-path Taproot accounts and addresses;
- BIP174 PSBT v0 plus BIP371 Taproot PSBT fields;
- BIP341 Taproot validation and BIP340 Schnorr signatures;
- transaction version 2, locktime 0, sequence `0xFFFFFFFD` (RBF);
- vanilla-account inputs only for `sendBtc`; legacy branch-1 change or the
  explicitly selected internal RGB-lib profile's verified branch-0 change.

In this profile, “vanilla” is a locally verified BIP86 derivation account. It
does not prove that an outpoint is free of RGB allocations; that needs
authoritative RGB state evidence from the still-unfrozen gateway contract.

Vanilla accounts use `m/86'/0'/0'` on mainnet and `m/86'/1'/0'` elsewhere.
Colored accounts preserve the existing UTEXO profile at coin types `827166`
(mainnet) and `827167` (non-mainnet). Address branches are `0` external and `1`
change. Logical network `utexo` maps to Signet for Bitcoin cryptography while
remaining `utexo` in the public API.

The compatibility `signMessage` primitive inside the Rust crate is
SHA256(message) followed by a BIP340 signature at vanilla `/0/0`. It is not
BIP322 and is intentionally not exposed by `UTEXOWallet` as a generic login or
authentication scheme.

## Application API

```kotlin
// Inside an application coroutine; no WalletInfrastructureApi opt-in is required.
val configuration = WalletConfiguration(
    walletId = WalletId.of("customer-wallet-id"),
    network = Network.SIGNET,
)
val storage = WalletStorageConfiguration()
val credentials = AndroidKeystoreAPIKeyStore(
    applicationContext, configuration.walletId, configuration.network,
)
// First setup or deliberate credential rotation. Obtain this short-lived object from your login flow.
apiKey.use { credentials.store(it) }

// One-time create/restore; omit provisioning when opening an existing wallet.
AndroidKeystoreSeedStore(
    applicationContext, configuration.walletId, configuration.network,
    storage.seedStoreConfiguration,
).provisionFromMnemonic(secretMnemonic)

val wallet = UTEXOWallet.create(
    context = applicationContext,
    configuration = configuration,
    service = WalletServiceConfiguration("https://gateway.example", credentials),
    storage = storage,
)
try {
    wallet.init()
    wallet.unlock()
    val balance = wallet.getBtcBalance()
    // sendBtc(request) remains gated until the backend PSBT/recovery contract is verified.
} finally {
    wallet.destroy() // releases exclusive ownership; never deletes wallet data
}
```

The factory assembles Keystore seed custody, the gateway adapter, credential
loading, and authenticated durable operation storage. One instance owns a
wallet's storage until `destroy`/`dispose`; `shutdown` retains that ownership
so `reinit` is safe. Another instance or process fails immediately while the
lease is held. Always close the old instance before opening a replacement.
The constructor taking backend, key provider, and journal remains available
for infrastructure integrations and tests.
Permanent `destroy`/`dispose` cleanup completes even if its caller is canceled;
it still waits for the current serialized operation before releasing storage.

`WalletAPIKeyProvider.loadAPIKey()` is called for every HTTP request, and the
SDK closes the returned `WalletAPIKey`. `AndroidKeystoreAPIKeyStore.store`
deliberately rotates encrypted credentials without rebuilding the wallet.
The gateway account is rechecked when the credential changes, using the exact
same credential before the requested action. The journal also binds gateway
origin/account and local signing identity across process restarts; changing
accounts under an existing wallet ID fails before registration or signing.
Changing the service origin for an existing wallet fails during factory open,
before loading its API key or making an HTTP request.

`WalletInfrastructureApi` is an error-level opt-in around backend/journal and
raw transaction transport. Application feature code should only retain and
call `UTEXOWallet`; backend integration belongs in an infrastructure module.

RN-compatible top-level key helpers are available from package
`com.utexo.rgb.sdk` (and Java class `RGBSDK`): `generateKeys`, `createWallet`,
`deriveKeysFromMnemonic`, `deriveSeedFromMnemonic`, `deriveKeysFromSeed`,
`deriveKeysFromMnemonicOrSeed`, `restoreKeys`, `accountXpubsFromMnemonic`,
`getXprivFromMnemonic`, `getXpubFromXpriv`, and `deriveKeysFromXpriv`.
Mnemonic, seed, and xpriv results use redacting, closeable containers. Raw root
xpriv compatibility paths are explicitly deprecated.

`AndroidKeystoreSeedStore` is a production custody implementation: it wraps a
64-byte seed with AES-256-GCM in Android Keystore, atomically writes ciphertext
under `noBackupFilesDir`, and binds package name, wallet ID, logical network,
schema, and immutable key policy as AAD. StrongBox behavior is explicit:
disabled, required, or preferred with an explicit Keystore fallback. Provision
directly from a mnemonic without exposing seed bytes:

```kotlin
val seedStore = AndroidKeystoreSeedStore(
    context = applicationContext,
    walletId = walletId,
    network = Network.SIGNET,
    configuration = AndroidKeystoreSeedStoreConfiguration(
        strongBoxPolicy = StrongBoxPolicy.PREFERRED_WITH_KEYSTORE_FALLBACK,
        authentication = AndroidKeyAuthentication.BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL,
        authenticationValiditySeconds = 30,
    ),
)
seedStore.provisionFromMnemonic(secretMnemonic) // one-time, create-only
```

The provider never silently replaces a key or an existing envelope. Per-use
BiometricPrompt `CryptoObject` authentication is intentionally not implied;
the current API supports a bounded authentication-validity window. A corrupt
envelope or permanently invalidated key can only be removed through the
explicit `resetForRecovery` callback after the host application performs fresh
user authentication and confirms a backup; replacement provisioning is a
separate action.
Seed reads are bounded to 4 KiB before allocation and verify exact EOF. Existing
seed, lock and temporary files must be regular, single-link, owner-UID files;
the SDK directory and files receive owner-only permissions. Symlinks and hardlinks
are rejected, not followed. These checks do not defend against a compromised
application process or rooted operating system.

The seed-store configuration is a creation-time custody policy, not a live
policy upgrade. A changed policy selects a different wrapping-key alias while
the existing wallet ciphertext remains bound to its original policy; opening
it fails closed. Do not reset or reprovision to "upgrade" a policy. A verified,
explicit custody migration is not implemented. Keep the same configuration.

After unlock, secp256k1 signing material exists in the native process session
until `shutdown`, `destroy`, or `dispose`. StrongBox/Keystore protects seed
encryption and retrieval; Taproot signatures are **not** made inside StrongBox,
and each signature does not automatically prompt for biometrics. The app owns
the foreground/background and device-lock policy: stop issuing wallet calls
and await `shutdown()` when its authenticated session ends, then authenticate
and `reinit`/`unlock` before reuse. Shutdown waits behind already-running wallet
work; it does not undo a broadcast. No automatic lifecycle observer is installed.
JVM, FFI and native memory cleanup is best-effort, not guaranteed zeroization.

Persist each money-moving request and its `idempotencyKey` before calling the
SDK. If a timeout or cancellation occurs, retry the same request with the same
key. Cancellation stops the caller's wait; it does not imply that submission or
broadcast was reversed.

The SDK-owned operation journal records `created -> prepared ->
signed -> submitting -> terminal` before network transitions. PREPARED retains
the exact server PSBT; SIGNED and SUBMITTING retain the exact signed bytes and
local validation report. Recovery never prepares or signs another transaction
after SIGNED. A SUBMITTING retry only polls the durable operation ID; Pending
remains ambiguous and is never automatically resubmitted until the backend's
duplicate-submit contract is frozen. `OperationCheckpointCodec` provides the
SDK-owned `UTXOPCHK` v1 canonical representation for nonterminal evidence and
v2 for compact terminal receipts. Before the first submission of a retained
SIGNED checkpoint, current policy is fully revalidated against the original
preparation without re-signing. A tightened policy may block that first
submission. SUBMITTING recovery and terminal replay bypass fresh-payment
policy gates, because the transaction may already exist on-chain.

Only definitive TERMINAL records are compacted: both PSBT payloads are removed
while wallet/key/request hash/kind/operation ID, final outcome, and any local
validation report are retained. CREATED, PREPARED, SIGNED and SUBMITTING records
are never pruned. Older authenticated v1 snapshots migrate on the next atomic
write; existing v1 checkpoint bytes still decode. Downgrading to an old SDK that
cannot read v2 fails closed and is not supported. The factory encrypts snapshots
with AES-256-GCM, binds package/wallet/network, and atomically fsyncs the file
and parent directory. Separate Android Keystore commit markers detect
restoring an older valid SIGNED snapshot after SUBMITTING. Interrupted commits
recover their authenticated old/new candidate; interrupted first setup reuses
the original wrapping key. Missing committed data or keys fails closed and
never silently starts a new history. Storage is capped at 64 MiB and 100,000
records, with no automatic pruning of idempotency history; exhaustion stops
the operation before the next network transition. Custom infrastructure
journals must provide equivalent guarantees. The included
`InMemoryOperationJournal` is for tests and prototypes only.

This is a finite-lifetime journal, not an unbounded database. Compact receipt
capacity depends on field lengths and outcomes; the smaller of 100,000 records
or 64 MiB wins. Never delete journal data to regain capacity. Persist an app-owned
pending-request list (exact request and stable key) before calling a money-moving
method and retain it until the definitive result is recorded. The facade does
not enumerate forgotten requests; seed recovery alone cannot reconstruct them.
See [the durable consumer recovery contract](docs/DURABLE_CONSUMER_RECOVERY.md)
for the exact record fields, ordering and restart evidence. Persist the numeric
values, not rendered labels or new defaults; do not generate a new key on retry.

A definitively established submission rejection or expiry can be persisted as a
terminal outcome and replayed without resubmission. The current gateway has no
reviewed authoritative no-broadcast completion-error allowlist. Every completion
3xx/4xx, including idempotency reuse and operation-kind mismatch, remains ambiguous
and triggers one GET-only reconciliation. Matching success is saved; pending,
unavailable or inconsistent status preserves SUBMITTING with truthful ambiguity.
Preparation conflicts remain distinct because no signed submission occurred.
Custom infrastructure backends retain their documented error contract; their
unmapped errors never fabricate a terminal receipt or automatically resubmit.

## Public behavior inherited from RN—and corrected

Kotlin data-class constructors and `copy` retain their public shape. Bounded
stored text (asset ticker/name, recipient IDs, endpoints and MIME/reference
metadata) rejects surrounding whitespace so validation cannot inspect a
trimmed value while storing a different value. Value-wrapper factories such
as `WalletId.of` keep their documented normalization. Asset precision is
0–18 and IFA supply fields cannot exceed maximum supply. Unlike Kotlin's
strict stored-text constructors, Swift normalizes the corresponding strings;
each platform must preserve its own behavior in a future full SDK.

| Kotlin method | RN semantic | Deliberate correction |
|---|---|---|
| `init`, `initialize` | initialize wallet | idempotent and version-negotiated |
| `unlock`, `reinit` | attach signer/restart | seed is loaded only into a short-lived local signer |
| `shutdown`, `destroy`, `dispose` | lifecycle aliases | cleanup errors are not swallowed; destroy does not delete data |
| `getNetwork` | logical network | preserves `utexo`, despite cryptographic Signet mapping |
| `sendBtc` | atomic high-level send | typed exact units, caller-bound hash, idempotency and recovery |
| `createUtxos` | atomic UTXO creation | returns authoritative `CreateUtxosResult`, never the requested count |
| `onchainSend` | atomic RGB on-chain send | amount is required; local parser and operation capability are mandatory |
| `onchainReceive`, invoice decoding | wallet invoices | raw encoded amount, assignment, beneficiary mode, network and expiry checked locally |

## Backend contract

`WalletBackend` is an infrastructure SPI, not the application facade. Its
prepare response is accepted only when network, operation kind, idempotency key,
request hash, expiry, and local size policy all match. The SDK then validates
and signs locally and submits under the same durable operation ID. If the
submit response is lost, it queries that operation ID and never creates a new
payment identity.

The future backend must guarantee tenant isolation, prepare idempotency,
stable operation status, input/index reservations, an explicitly versioned
duplicate-submit contract keyed by operation ID,
and exact decimal-string handling for unsigned RGB amounts.

### PR #174 gateway adapter

`RgbLightningNodeGatewayBackend` connects the facade to
`rgb-lightning-node/minimal-sdk/packages/gateway` without making its REST
prepare/complete protocol application API:

```kotlin
@OptIn(WalletInfrastructureApi::class)
val backend = RgbLightningNodeGatewayBackend(
    RgbLightningNodeGatewayConfiguration(
        baseUrl = "https://wallet-gateway.example",
        expectedNetwork = Network.SIGNET,
        bearerToken = tokenLoadedFromEncryptedApplicationStorage,
        // Keep false in production until the exact PSBT profile and canonical
        // fixtures are frozen. Tests must opt into true explicitly.
        allowUnverifiedPsbtProfile = false,
    ),
)
```

The adapter currently maps authentication/session validation, watch-only xpub
registration, BTC balance, a single asset balance, sync/refresh and `onchainReceive`. BTC send
prepare/complete is default-off and is reachable only through the explicitly
unsafe `allowUnverifiedPsbtProfile` development opt-in. When enabled, BTC sends
still pass through the Rust local policy engine before completion. The prepare
response's operation ID, intent, recipient,
amount, whole-number fee rate, canonical Base64 PSBT, and expiry are checked
before the PSBT reaches that signer. Expiry must be in the future and no more
than the configured `maximumPreparationTtlSeconds` (one hour by default). A
backend instance permanently binds its bearer credential to the first logical
`WalletId`; initialization is idempotent while active, reinit opens a fresh
session after shutdown, and a different wallet ID is rejected. The gateway
`userId` returned for that credential is also pinned across shutdown and reinit;
an identity change fails as a backend protocol mismatch.

Turning the provisional opt-in off prevents new, created, prepared, or signed
work from advancing, without creating or nonterminally advancing a checkpoint.
An already-expired prepared or signed checkpoint may still be truthfully
terminalized. The gate does not hide a terminal result already in the journal,
and a SUBMITTING checkpoint may still use status-only recovery; it can never
issue another prepare or complete.

The remaining gateway routes are deliberately not adapted when they cannot
populate the SDK model without guessing. In particular, gateway addresses do
not carry derivation branch/index evidence. The gateway already has an address
route; maintaining the SDK's local ownership guarantee needs that evidence or
a separately reviewed durable discovery/recovery design. A fixed scan window
would introduce a wallet-lifetime limit and could abandon remote reservations
on failure, so it is not used as an apparent fix. UTXOs omit `pendingBlinded`;
transfers omit the authoritative batch index and assignment list; assets omit
issuance/timestamp fields. Lower `blindReceive` / `witnessReceive` return models
require an authoritative batch index that the gateway does not supply; they
remain unsupported. Complete history snapshots can share local txid filters;
the default transaction filter rejects `skipSync = true` unless the backend
implements it explicitly. These aliases do not invent missing gateway history.
`estimateFeeRate` validates RN's 1–1008 block target locally, but still needs a
real estimator route. `CreateUtxosRequest.num` is 1–255 when specified, matching
the native u8 domain rather than wrapping 256 to zero; `size` is 1–4,294,967,295
satoshis (native u32). Native selection can produce fewer than `num` even with
`upTo = false`; the actual count must still equal the locally authorized exact
colored-output plan. `upTo` affects accounting for existing available slots,
not an exact-count guarantee. An already-satisfied/no-op create request retains
the native `AllocationsAlreadyAvailable` failure. It does not produce a new
signed transaction or require a fabricated transaction ID/new success type.
`onchainReceive` permits a null batch index, which is returned
unchanged instead of substituting a guessed value.

`onchainReceive` sends the requested blind/witness mode, exact optional asset and
amount, confirmations (default 0, supported 0–100), and explicit duration (default
86,400 seconds, supported 60–2,592,000). The current gateway's numeric JSON field
limits amounts to 9,007,199,254,740,991; larger u64 amounts are rejected before
HTTP, never rounded. The facade binds raw encoded state as well as the public
assignment: an omitted amount cannot be replaced by a hidden encoded amount,
and an unspecified asset cannot be silently narrowed to a particular schema.
Received invoices must contain an expiry within the local start/end time plus
the effective duration. Clocks must be synchronized; no implicit skew allowance
is invented. Required response fields must exist. An explicit null DTO expiry
is preserved, but the locally decoded invoice expiry is still mandatory; every
nonnull DTO expiry must match it. Recipients and mode must match exactly.

This creates RGB receive state in the **trusted gateway wallet**. Local syntax
and intent checks do not prove that a recipient belongs to the caller's seed:
the DTO lacks witness derivation evidence and blind-secret custody/recovery
remains server-held. The parser validates transport syntax without contacting
the endpoints. Receive creation is not automatically retried after timeout;
cancellation does not prove that server-side receive state was not created.

`syncWallet()` and `refreshWallet()` are both implemented as the gateway's
combined `/v1/wallet/sync` operation: it awaits Bitcoin wallet sync and RGB
refresh before returning `status: "ok"`. `SyncResult.completedAt` is the local
client time when that successful response is observed, not a claimed server
timestamp. `tipHeight` remains null because the server provides no height.
Neither alias retries automatically; cancellation stops waiting, not necessarily
the server's queued sync. This is not exact RN workload parity: RN `syncWallet`
uses colored-keychain FastSync, whereas `refreshWallet` refreshes RGB transfers.
The lightweight combined operation and richer return model must be retained by
any future drop-in full SDK, or separately versioned if that contract changes.

Backend revision `7f318a80372ae3bdc7e2f54cd6cd4b7b5d734d9d` adds authenticated
`GET /v1/onchain/operations/{opId}`. The coordinator now uses that read-only
route for a retained SUBMITTING BTC or create-UTXO checkpoint, including after restart and
with the development signing opt-in disabled. It checks the operation ID,
original request/intent, kind, bounded timestamps and retained preparation
expiry, broadcast flag consistency, and a canonical transaction ID equal to
the locally signed transaction. Only `completed` state with all evidence
matching becomes terminal success. The evidence-free infrastructure
`getOperation` method remains unsupported.

`pending` and `expired`, with or without a transaction ID, remain
`OperationAmbiguous`: this unqueued read can race an in-flight completion or
observe a crash window after broadcast but before durable bookkeeping. A
completion's `410 OP_EXPIRED` likewise cannot prove a previous attempt never
broadcast. Ambiguous completions receive one GET-only reconciliation; there is
no automatic prepare, re-sign, complete, or polling loop. Missing, inconsistent,
unavailable, and canceled reads leave the SUBMITTING checkpoint intact.
RGB status lacks its native batch ID. A create count can be obtained from
locally validated outputs once the gateway authoritatively confirms that exact
operation; a missing count field alone is not an independent server blocker.
The create adapter derives the actual count from the authenticated, locally
validated output plan only after that completed response. Initial completion
counts must match the same plan. Null/mismatched transaction IDs and counts
remain ambiguous. RGB recovery remains unavailable; no batch indices are
fabricated and local output counting is never proof of completion.

Create preparation explicitly sends `upTo`, `num` (default 5), `size` (default
1,000 sats) and the exact whole-sat/vB fee. The current gateway domain is 1–50
outputs and the local P2TR dust floor is 330 sats. `skipSync=true` is rejected.
Extracted branch-0 colored output metadata is untrusted: it is independently
checked against the request and locally re-derived by the signer. This private
qualification path does not enable create spending through the ordinary factory.

The adapter currently derives distinct prepare/complete HTTP idempotency keys
from the caller's durable logical key. The updated backend documentation now
explicitly requires separate phase keys; the existing deterministic derivation
is preserved so an SDK upgrade does not change an in-flight payment identity.
This clarification does not fix atomic prepare/idempotency persistence or
establish a crash-safe automatic completion-resubmission contract.

PR #174 currently accepts only integer `feeRateSatPerVb`, so callers must pass
an exact whole-sat/vB `FeeRate`; the lightweight convenience default is now
2 sat/vB. Explicit fractional values remain exact value types but are rejected
before new gateway work or credential access, never rounded. RN `sendBtc` requires an explicit fee;
1.5 is its default for create-UTXOs, RGB send and inflation, not BTC send.
`SendBtcRequest.skipSync = true` is also rejected because
the gateway hardcodes synchronization on. `Network.UTEXO` and `Network.TESTNET4`
are rejected because the gateway cannot negotiate those logical network
identities. Local container HTTP is available only with
`allowInsecureLocalDevelopment = true` and only for loopback, `localhost`, or
Android emulator host `10.0.2.2`; a host application must also opt into Android
cleartext traffic for that development endpoint.

This public wiring does not relax the legacy `utexo.psbt.bip86.v1` profile or
change the identity of retained operations. Its zero-locktime/branch-1 change
rules remain intact for that explicitly named profile.

An additive, **internal qualification path** implements
`utexo.psbt.rgb-lib.v1`: branch-0 vanilla/colored derivation, version 2, RBF,
height locktime bounded by separately trusted context, and operation-specific
BTC/create-UTXO/RGB Bitcoin authorization. The requested fee is a target with
a tightly bounded signed-weight rounding allowance, while the wallet's
absolute-fee and maximum-rate limits remain independent hard caps. No arbitrary
dust overpayment allowance or fractional gateway fee conversion is introduced.
Create-UTXO authorization binds caller count/size defaults and the exact colored
output plan. RGB authorization binds the original invoice's raw encoded state,
asset, amount, Bitcoin witness payment where present, and selected request
options; it does **not** validate RGB transitions or consignments locally.

The ordinary factory does not select this path and fresh gateway spending
remains disabled. A trusted height source, exact deployed native profile/options,
durable server recovery, complete results and end-to-end deployment qualification
are still required. Passing offline public-key fixtures is not evidence that a
gateway completion/broadcast/restart round trip is safe.

## Building

The project uses Android Gradle Plugin 9.1, compile SDK 36, NDK
`28.2.13676358`, Java 17 bytecode,
Kotlin explicit API mode, warnings-as-errors, dependency locking, and JUnit 5.
The checked-in Gradle 9.3.1 wrapper pins the official distribution SHA-256.
Release packaging marks Kotlin-internal implementation and UniFFI bytecode as
synthetic, and `verifyReleaseJvmApi` audits the packaged AAR plus positive and
negative Java compilation probes so raw signing, seed-storage internals,
credentials, and custom HTTP-client injection cannot enter the Java API.
The provisional Maven publication is
`com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT`; consuming the published
module (instead of copying the AAR) preserves its JNA and coroutines dependency
metadata.

The companion `utexo-local-signer` source provides `scripts/build-android.sh`.
It refuses unpinned native tooling: it requires `cargo-ndk 4.1.2` and NDK
`28.2.13676358`, builds all three ABIs in a staging directory, verifies the
complete library set, and replaces the checked-in `jniLibs` directory only
after every build succeeds.

```shell
./gradlew :sdk:testDebugUnitTest
./gradlew :sdk:assembleRelease
./gradlew :sdk:verifyConnectedDebugAndroidTests
./gradlew :sdk:verifyReleaseNativeAbis
./gradlew :sdk:verifyReleaseApiBaseline
```

`release-smoke-app` is a non-shipped consumer fixture. It depends on the
generated `sdk-release.aar` as a flat packaged artifact—not on the `:sdk`
project—then enables full release R8 minification. Its instrumentation test
loads the packaged native signer and checks the deterministic RN mnemonic,
fingerprint, root xpub, and both BIP86 account xpubs. It also compiles the
ordinary factory without infrastructure opt-in and opens/reopens durable
storage using real Android Keystore. Both the SDK and the minified release
consumer sign a synthetic BIP86 PSBT and verify its finalized Taproot witness.
The release signing test opts into test-only backend SPI and supplies a fixed
public vector with a nonexistent prevout; it never contacts a server or broadcasts.
With an Android API 24+
device or emulator connected, run the complete packaged-artifact gate with:

```shell
./gradlew :release-smoke-app:verifyPackagedReleaseSmoke
bash scripts/verify-maven-consumer.sh
bash scripts/verify-native-compatibility.sh
```

The Maven consumer script publishes only to the repository's disposable
`build/validation-maven` directory, then compiles and R8-minifies copied consumer
sources in a fresh temporary standalone build. Its only SDK/runtime dependency
is the Maven coordinate. It verifies both Gradle Module Metadata and POM-only
resolution, including the JNA **AAR** and all transitive dependencies. Separate
`native-source` (tar.gz), `native-evidence` (zip) and `native-sbom` (JSON)
classifiers carry the native evidence. The actual native notices, review status
and SBOM are also included in `assets/utexo-native/`; the consumer checks their
bytes in both the resolved AAR and its minified APK. Production builds verify the
canonical native checksums before staging these assets. This is local packaging
evidence, not a claim that any remote package was published. Project-license
selection and approval, including the explicit unresolved upstream notice, remain
owner-controlled release gates. Do not mistake evidence delivery for legal approval.

The native-compatibility script starts separate JVMs against the actual release
AAR and deliberately mismatched native fixtures. Every operational symbol exists
but terminates the fixture process if reached; contract-version and API-checksum
failures must instead surface as typed failures before key helpers or signer
construction, without retrying initialization. Its checksum-pinned host JNA JAR
is test tooling only; published Android consumers still resolve the JNA AAR.
The minified consumer also exercises hostile success/error JSON through the
ordinary factory and a loopback HTTP fixture. Cleartext permission belongs only
to that non-shipped test application, never to the SDK manifest.

Before publishing, CI must rebuild with that pinned script, verify 16 KiB
Android page alignment, run Rust and Kotlin
golden/adversarial tests, execute an on-device native smoke test, inspect the
release AAR, establish an API compatibility baseline, produce an SBOM, verify
license notices, and run the release security workflow.

`.github/workflows/verify.yml` runs unit tests, release lint, packaged Java API
isolation, JVM ABI/Kotlin metadata baseline checks, minified consumer compilation, native ABI
packaging, and device tests. `sdk/api/release-api.txt` is the reviewed packaged
JVM ABI and Kotlin metadata baseline; change it only after reviewing `build/api/release-api.txt`
with `:sdk:updateReleaseApiBaseline`. This checks descriptors, generic types,
constants, annotations (including nullability), Kotlin default-argument bridges,
and complete Kotlin metadata (including source parameter names). The metadata
guard is deliberately conservative: changing private Kotlin declarations may
also require review. Both tasks always run, including when only the reviewed
baseline changes. This does not replace tests of default values or behavior. Lint
suppresses only newer-version notices for deliberately pinned build inputs;
security advisories still require separate review.

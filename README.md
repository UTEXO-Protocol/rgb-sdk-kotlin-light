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

This is an implementation foundation awaiting the production backend and its
versioned API contract. The generated UniFFI 0.32.1 binding and release native
libraries are checked in for Android `arm64-v8a`, `armeabi-v7a`, and `x86_64`
(minimum API 24). The Gradle build prepares generated code in an isolated build
source so handwritten public code remains under Kotlin explicit-API checking.

The local Rust core currently supports complete signing policy for `sendBtc`
only. `createUtxos`, `onchainSend`, and `inflate` are deliberately unavailable
until their exact output/commitment evidence is frozen and enforced in Rust.
The Kotlin facade checks the operation capability before asking the backend to
prepare anything, so an incomplete policy cannot silently sign.

`onchainSend` will also remain unavailable until a local RGB invoice parser can
bind network, asset, and amount. Even then, lightweight v1 cannot independently
validate the RGB state transition: it can protect Bitcoin ownership and fee
policy, but it must trust the backend for RGB input selection, recipient
assignment, RGB change, and consignment state.

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

The frozen key/signing profile is `utexo.bip86.v1` / `utexo.psbt.bip86.v1`:

- BIP39 mnemonic to 64-byte seed;
- BIP32 hierarchical deterministic derivation;
- BIP86 key-path Taproot accounts and addresses;
- BIP174 PSBT v0 plus BIP371 Taproot PSBT fields;
- BIP341 Taproot validation and BIP340 Schnorr signatures;
- transaction version 2, locktime 0, sequence `0xFFFFFFFD` (RBF);
- vanilla inputs only and vanilla branch-1 change for `sendBtc`.

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
@OptIn(WalletInfrastructureApi::class)
suspend fun send(productionBackend: WalletBackend) {
    val wallet = UTEXOWallet(
        configuration = WalletConfiguration(
            walletId = WalletId.of("customer-wallet-id"),
            network = Network.SIGNET,
        ),
        backend = productionBackend,
        keyProvider = keystoreBackedKeyProvider,
        operationJournal = encryptedDurableOperationJournal,
    )

    wallet.init()
    wallet.unlock()

    val txid = wallet.sendBtc(
        SendBtcRequest(
            address = BitcoinAddress.of(destination),
            amount = Satoshis.of(25_000),
            feeRate = FeeRate.satPerVbyte("1.5"),
            idempotencyKey = IdempotencyKey.random(),
        ),
    )

    wallet.shutdown() // restartable with reinit(...)
    wallet.destroy()  // closes this instance; never deletes durable wallet data
}
```

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

Persist each money-moving request and its `idempotencyKey` before calling the
SDK. If a timeout or cancellation occurs, retry the same request with the same
key. Cancellation stops the caller's wait; it does not imply that submission or
broadcast was reversed.

`OperationJournal` is required because the SDK records `created -> prepared ->
signed -> submitting -> terminal` before network transitions. PREPARED retains
the exact server PSBT; SIGNED and SUBMITTING retain the exact signed bytes and
local validation report. Recovery never prepares or signs another transaction
after SIGNED. A SUBMITTING retry only polls the durable operation ID; Pending
remains ambiguous and is never automatically resubmitted until the backend's
duplicate-submit contract is frozen. `OperationCheckpointCodec` provides the
SDK-owned `UTXOPCHK` v1 canonical representation. Production journals must
atomically protect it with authenticated encryption; its checksum is only for
accidental-corruption detection. The included
`InMemoryOperationJournal` is for tests and prototypes only.

A definitive non-retryable submission rejection or expiry is persisted as a
terminal outcome and replayed without resubmission. The current checkpoint
schema has no terminal representation for an idempotency conflict or operation-
kind protocol mismatch; those errors propagate while the checkpoint remains
SUBMITTING, so retrying cannot issue another completion request. Extending that
terminal model is a backend-contract release gate.

## Public behavior inherited from RN—and corrected

| Kotlin method | RN semantic | Deliberate correction |
|---|---|---|
| `init`, `initialize` | initialize wallet | idempotent and version-negotiated |
| `unlock`, `reinit` | attach signer/restart | seed is loaded only into a short-lived local signer |
| `shutdown`, `destroy`, `dispose` | lifecycle aliases | cleanup errors are not swallowed; destroy does not delete data |
| `getNetwork` | logical network | preserves `utexo`, despite cryptographic Signet mapping |
| `sendBtc` | atomic high-level send | typed exact units, caller-bound hash, idempotency and recovery |
| `createUtxos` | atomic UTXO creation | returns authoritative `CreateUtxosResult`, never the requested count |
| `onchainSend` | atomic RGB on-chain send | amount is required; local parser and operation capability are mandatory |
| receive/read APIs | wallet views and invoices | returned invoices are locally cross-checked when parser support exists |

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
    ),
)
```

The adapter currently maps authentication/session validation, watch-only xpub
registration, BTC balance, a single asset balance, and BTC send
prepare/complete. BTC sends still pass through the Rust local policy engine
before completion. The prepare response's operation ID, intent, recipient,
amount, whole-number fee rate, canonical Base64 PSBT, and expiry are checked
before the PSBT reaches that signer. Expiry must be in the future and no more
than the configured `maximumPreparationTtlSeconds` (one hour by default). A
backend instance permanently binds its bearer credential to the first logical
`WalletId`; initialization is idempotent while active, reinit opens a fresh
session after shutdown, and a different wallet ID is rejected. The gateway
`userId` returned for that credential is also pinned across shutdown and reinit;
an identity change fails as a backend protocol mismatch.

The remaining gateway routes are deliberately not adapted when they cannot
populate the SDK model without guessing. In particular, gateway addresses do
not carry derivation branch/index evidence; UTXOs omit `pendingBlinded`;
transfers omit the authoritative batch index and assignment list; assets omit
issuance/timestamp fields; receive responses omit the authoritative batch
index; sync omits server completion metadata; and the gateway has no
operation-status route. Consequently an uncertain completion remains
`OperationAmbiguous` and is never blindly resubmitted by the wallet coordinator.

PR #174 currently accepts only integer `feeRateSatPerVb`, so callers must pass
an exact whole-sat/vB `FeeRate`; the RN-compatible 1.5 sat/vB default is rejected
rather than rounded. `SendBtcRequest.skipSync = true` is also rejected because
the gateway hardcodes synchronization on. `Network.UTEXO` and `Network.TESTNET4`
are rejected because the gateway cannot negotiate those logical network
identities. Local container HTTP is available only with
`allowInsecureLocalDevelopment = true` and only for loopback, `localhost`, or
Android emulator host `10.0.2.2`; a host application must also opt into Android
cleartext traffic for that development endpoint.

This wiring does not relax the frozen `utexo.psbt.bip86.v1` signer profile. A
gateway PSBT using a different transaction version, locktime, sequence, key
branch, ownership class, or Taproot PSBT shape will be rejected locally. End-to-
end compatibility therefore remains gated on a canonical backend fixture and a
frozen shared PSBT profile.

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
```

`release-smoke-app` is a non-shipped consumer fixture. It depends on the
generated `sdk-release.aar` as a flat packaged artifact—not on the `:sdk`
project—then enables full release R8 minification. Its instrumentation test
loads the packaged native signer and checks the deterministic RN mnemonic,
fingerprint, root xpub, and both BIP86 account xpubs. With an Android API 24+
device or emulator connected, run the complete packaged-artifact gate with:

```shell
./gradlew :release-smoke-app:verifyPackagedReleaseSmoke
```

Before publishing, CI must rebuild with that pinned script, verify 16 KiB
Android page alignment, run Rust and Kotlin
golden/adversarial tests, execute an on-device native smoke test, inspect the
release AAR, establish an API compatibility baseline, produce an SBOM, verify
license notices, and run the release security workflow.

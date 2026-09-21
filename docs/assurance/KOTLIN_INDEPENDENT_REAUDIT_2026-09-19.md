# Independent Kotlin release-readiness audit — 2026-09-19

Verdict: **not a production release candidate**. The shipped implementation has substantial local security and packaging verification, but payment functionality is disabled in the ordinary factory, 18 of 26 wallet business method names remain unsupported, a completion-error contract defect remains in the development payment path, and distribution, device, interoperability, and migration evidence is incomplete. Passing local tests does not close these gaps.

This is an analysis-only audit of the existing dirty working tree at base commit `dc88ed30280ae87734eee719d701b5f1a4a75895`. No shipping source, native binary, baseline, or test was changed. This document is the only authored change. No remote publication, funded transaction, or backend mutation was performed by this audit.

## Scope and evidence limits

The review read all 25 handwritten shipping Kotlin files, **7,565 lines**, including the facade, public values/models/requests, gateway adapter, key custody, encrypted journal and checkpoint codec, operation coordinator, JSON preflight, native initialization/ownership helpers, and native policy mapper. It also read the four buildSrc Java classes (**779 lines**), seven relevant Gradle build/settings files (**651 lines**), both verification shell scripts, native incompatibility fixture generator/probe, CI workflow, and Android manifest/consumer shrinker rules. No applicable `AGENTS.md` was found in this repository or its workspace ancestors.

The 3,390-line generated UniFFI file was inventoried and its initialization/adapter boundaries inspected; it is not counted as manually re-reviewed handwritten code. The shared Rust implementation, upstream dependency internals, and backend source are separate audit scopes. This review examined selected regression implementations and the inventory of 17 JVM test/support files (4,286 lines), plus the Android and packaged-consumer fixtures. Test counts below are executions, not source line coverage, proof of every possible interleaving, or proof of cryptographic correctness.

Fresh checks in this audit:

- Forced gateway/coordinator regression run: **71 tests passed** (46 gateway, 25 coordinator), 18 executed Gradle tasks, 14 seconds.
- Subsequent full JVM run: **147 tests / 16 suites; zero failures, errors, or skips**, 9 seconds. Current XML is under `sdk/build/test-results/testDebugUnitTest`.
- All **12** entries in `NativeSource/SHA256SUMS` passed, and `git diff --check` passed.
- The existing connected-test XML count guard passed with the device-execution task explicitly excluded. This only checks retained results; it is **not a new emulator run**.
- Independently inspected the actual release AAR entry list and native/source hashes.

Existing evidence inspected, not rerun here: `build/assurance/invoice-final-2026-09-19/{api24,api36,api36-16k}` contains **33 successful Android executions**, five SDK tests and six R8 consumer tests per ARM64 emulator. The tested page sizes are 4 KiB, 4 KiB, and 16 KiB respectively, as recorded by the preceding closure. The smoke fixture uses a public synthetic Bitcoin PSBT with a nonexistent prevout and fake backend (`release-smoke-app/.../ReleaseTransactionSmoke.kt:10,18-45`); it does not demonstrate acceptance or broadcast of a real gateway payment. Receive tests use loopback JSON fixtures and the real native parser (`ReleaseGatewaySmoke.kt:15-21`).

## Confirmed SDK findings

### K-A01 — P1 before payment enablement: two completion errors escape the ambiguous-outcome contract

`RgbLightningNodeGatewayBackend.kt:948-965` maps `409 OP_KIND_MISMATCH` and `409 IDEMPOTENCY_KEY_REUSED` before the catch-all completion 4xx ambiguity branch. They become `BackendProtocolMismatch` and `OperationConflict`, both non-retryable (`Errors.kt:18,23`). The latter carries no operation ID (`Errors.kt:120-125`). `OperationCoordinator.kt:292-296` rethrows these without attempting the read-only status reconciliation that other uncertain completion outcomes receive.

An intermediary or inconsistent server response after a complete request has crossed the network cannot establish that the original operation never broadcast. The SDK retains `SUBMITTING`, which is the correct storage behavior, but reports a definitive-looking caller error instead of the actual unknown payment outcome. Applications following the public retryability code may stop reconciliation or create a replacement logical payment. **No automatic duplicate submission by the SDK was observed.**

Reachability is limited: `WalletServiceConfiguration.kt:57-66` always disables fresh payments in the ordinary factory. The defect is reachable through the explicit infrastructure development opt-in and must be resolved before enabling the ordinary payment path. Existing `SUBMITTING` recovery with the gate disabled performs GET only and wraps GET failures as `OperationAmbiguous` (`OperationCoordinator.kt:436-465`); that path does not call complete and is not regressed by this finding.

Evidence: the passing test `operation kind mismatch is a protocol failure` explicitly asserts the current completion behavior (`RgbLightningNodeGatewayBackendTest.kt:1064-1085`). The broad ambiguity table at lines 644-678 omits these two codes. The idempotency-conflict test at lines 972-997 covers **prepare**, where a conflict classification is appropriate, not completion. The generic SPI coordinator test at `OperationCoordinatorTest.kt:613-633` also locks in preservation without reconciliation for a protocol error.

Required closure: distinguish prepare conflicts from post-submission uncertainty; on the concrete gateway complete path retain the operation identity, use the ambiguous-outcome contract, reconcile once by GET, and add explicit tests for both 409 codes with completed, pending, and unavailable status. Generic SPI handling can preserve its separate documented contract.

### K-A02 — P2: the linked native provenance document identifies obsolete artifacts

`README.md:56-58` directs integrators to `NATIVE_ARTIFACTS.md`. That file describes the **currently checked-in** libraries as September 18 builds, gives obsolete source SHA at line 14, obsolete Cargo lock hash at line 28, and obsolete ELF hashes at lines 40-42. The machine manifest and latest closure describe different September 19 artifacts. A consumer following the linked verification instructions sees an unexplained mismatch.

Measured current identities:

| Artifact | Current SHA-256 |
| --- | --- |
| Source archive | `476f6d8a1952686d9569adb8aeb92c0c511d038fb02c399a7589bf838ff3f0bf` |
| Input arm64-v8a ELF | `a00039dc7c3714bb9ae71bd4020f1a6522b2b9325ed64c8e91e7a28dc727bab4` |
| Input armeabi-v7a ELF | `2716e78fe412743ad4fd01f3277bbc3490a7208c05fc0c38e8f6758e89cadb63` |
| Input x86_64 ELF | `149845cb80e0c1c10d2ff93ee799c49dfe2989f0aa03c4ceafc54f59e0a4df22` |

These are input ELF identities. AGP strips them further during packaging, so they intentionally differ from packaged AAR ELF hashes in `SDK_CLOSURE_2026-09-19.md`. Required closure: make the prose reference the canonical current receipts and accurately distinguish input from packaged hashes.

## Actual public wallet business surface

This table describes the concrete adapter/native implementation supplied by `UTEXOWallet.create`, not what a test double or a future `WalletBackend` could implement. “Implemented” means a real code path exists; it does not claim live backend interoperability, trustless RGB validation, or release approval. All business methods require an unlocked wallet. Aliases count as separate public names because they are independently callable.

| Public method and parameters | Result | Current concrete behavior |
| --- | --- | --- |
| `getBtcBalance()` | `BtcBalance` | Implemented gateway balances mapping |
| `getAddress()` | `BitcoinAddress` | Unsupported: no authoritative reservation/derivation metadata adapter |
| `rotateVanillaAddress()` | `BitcoinAddress` | Unsupported: same reservation contract |
| `listUnspents()` | `List<Unspent>` | Unsupported: authoritative UTXO/allocation DTO mapping absent |
| `estimateFeeRate(blocks: BlockCount)` | `FeeEstimate` | Unsupported: adapter/fee contract absent |
| `sendBtc(request: SendBtcRequest)` | `TransactionId` | Fresh send disabled in ordinary factory; development opt-in only; local BTC signing exists; same-request SUBMITTING recovery/terminal replay implemented |
| `createUtxos(request: CreateUtxosRequest = CreateUtxosRequest())` | `CreateUtxosResult` | Unsupported by native capability and adapter; exact output plan/durable result contract required |
| `listAssets()` | `ListAssets` | Unsupported: exact full asset models not mapped |
| `getAssetBalance(assetId: AssetId)` | `AssetBalance` | Implemented for safe numeric JSON range; offchain fields remain absent |
| `issueAssetNia(request: IssueAssetNiaRequest)` | `AssetNia` | Unsupported: adapter/full result contract absent |
| `issueAssetIfa(request: IssueAssetIfaRequest)` | `AssetIfa` | Unsupported: adapter/full result contract absent |
| `blindReceive(request: InvoiceRequest)` | `InvoiceReceiveData` | Unsupported: lower-level result requires batch-transfer index |
| `witnessReceive(request: InvoiceRequest)` | `InvoiceReceiveData` | Unsupported: same required batch metadata |
| `decodeRgbInvoice(invoice: RgbInvoice)` | `InvoiceData` | Implemented local parsing and network check |
| `decodeRGBInvoice(invoice: RgbInvoice)` | `InvoiceData` | Implemented alias |
| `onchainReceive(request: OnchainReceiveRequest)` | `OnchainReceiveResponse` | Implemented gateway receive plus local invoice intent validation; server owns receive state; batch index may be null |
| `onchainSend(request: OnchainSendRequest)` | `OnchainSendResponse` | Unsupported by native capability and adapter; authorization/commitment contract incomplete |
| `inflate(request: InflateRequest)` | `TransactionId` | Explicitly unsupported; local inflation authorization missing |
| `listTransactions()` | `List<Transaction>` | Unsupported |
| `listTransactionsByTxid(txid: TransactionId, skipSync: Boolean = false)` | `List<Transaction>` | Unsupported |
| `listTransfers(assetId: AssetId? = null)` | `List<Transfer>` | Unsupported |
| `listTransfersByTxid(txid: TransactionId)` | `List<Transfer>` | Unsupported |
| `listOnchainTransfers(assetId: AssetId? = null)` | `List<Transfer>` | Alias of unsupported `listTransfers` |
| `failTransfers(request: FailTransfersRequest)` | `FailTransfersResult` | Unsupported |
| `refreshWallet()` | `SyncResult` | Implemented; awaits combined gateway sync/refresh |
| `syncWallet()` | `SyncResult` | Implemented; client-observed completion time, no chain-height/freshness proof |

Count: **7 implemented names, 1 provisionally gated name, 18 unsupported names**. Deduplicating decoder and transfer aliases yields 6 implemented behaviors, 1 gated behavior, 17 unsupported behaviors. Thus 7/26 (26.9%) is a narrow method-availability ratio, **not a production-quality percentage**. Fresh ordinary-factory payment coverage is currently zero. Lifecycle/factory/configuration APIs and the 11 top-level key helpers are additional implemented infrastructure and are excluded from this business-method denominator.

Primary evidence: facade `UTEXOWallet.kt:176-321`; unsupported backend defaults `Backend.kt:45-140`; concrete gateway overrides `RgbLightningNodeGatewayBackend.kt:363-488`; native signing capabilities `internal/UniFfiLocalSigningEngine.kt:76-86` and explicit unsupported signing branches at lines 154-165. Declaring all functions and compiling a public API baseline does not implement the missing behavior.

## Release blockers by owner

| Category / owner | Blocker and evidence | Required closure |
| --- | --- | --- |
| Backend + SDK contract, P1 | Fresh BTC gate; integer gateway fee vs SDK default 1.5 sat/vB; `skipSync` rejected (`RgbLightningNodeGatewayBackend.kt:488-499,1044-1057`); strict local locktime 0, sequence FD, vanilla branch-1 change (`UniFfiLocalSigningEngine.kt:241-263`); SDK cannot prove allocation-free RGB inputs from derivation alone | Frozen versioned profile, canonical real prepare/complete fixtures, fee/default semantics, authoritative allocation evidence and actual interoperability |
| Backend + SDK recovery, P1 | Adapter intentionally treats pending/expired status as unknown because state cannot prove no broadcast (`RgbLightningNodeGatewayBackend.kt:667-680`) | Durable backend broadcast/idempotency/result contract and funded timeout/crash/restart qualification; close K-A01 separately |
| SDK + backend feature work, P1 | 18 unsupported names; native capability remains BTC-only, including create-UTXOs | Implement exact approved DTO adapters and operation-specific local authorization, then verify each real public result; backend availability alone will not write the missing SDK implementation |
| SDK/app recovery integration, P1 before real payments | Requests default to a random idempotency key (`Requests.kt:8-9,22,115`); journal stores a hash/evidence, not a recoverable public caller request; recovery requires original request (`OperationCoordinator.kt:48-64,505-510`) | Demonstrate durable app pending-request storage and same-key restart recovery, or provide an SDK-owned recovery workflow without exposing raw PSBTs. Constructing a new request is a new logical payment |
| Platform assurance, P1 | Physical biometric/credential/StrongBox/invalidation behavior unqualified; only ARM64 emulator evidence inspected. Seed auth is checked on retrieval, while signer keys stay in memory until shutdown (`AndroidKeystoreSeedStore.kt:105-107`, `UTEXOWallet.kt:399-433,436-465`) | Test real production Android devices, authentication expiry/enrollment/lock/reboot, key invalidation/recovery, and application background shutdown; retain evidence for supported OEM/API combinations |
| Device/storage reliability, P1 | Complete snapshot read/sort/encrypt/write per journal transition (`EncryptedOperationJournal.kt:56-67,80-141`), 100,000-record/64 MiB hard caps; desktop capacity test is not device performance/endurance | Device latency/memory/Keystore-anchor endurance and failure recovery tests, documented supported capacity, operational handling when full; do not delete idempotency evidence to make space |
| ABI/platform support, P1 | Three ABIs distributed (`sdk/build.gradle.kts:90-95`) but current local device evidence covers ARM64; CI x86_64 matrix is configured, not evidence of an executed remote run | Record actual x86_64 CI and armeabi-v7a runtime qualification or narrow supported runtime claims |
| Distribution/provenance, P1 | Version is `0.1.0-SNAPSHOT`; publication targets local validation only; no signing/remote RC release path in `sdk/build.gradle.kts:12-13,266-299`. CI verifies matching checked-in bytes and tests extracted source, but does not reproduce shipped Android native ELFs (`.github/workflows/verify.yml:42-64`) | Immutable tagged release, reviewed native-source/artifact relationship, independent/clean CI rebuild evidence, actual repository-resolution tests and release authenticity policy |
| Licensing/notice delivery, P1 release gate | No project `LICENSE` file; POM has no license metadata; `NativeSource/license-review.json:3-7` says owner review required, license unselected, `hex_lit@0.1.1` notice missing. AAR has no dedicated native notice/SBOM/source entries; publication attaches release component/sources only | Owner selects distribution license and completes notice review; ensure required notices and source/SBOM references accompany the real Maven distribution. This is an observed unresolved delivery/review gate, not a legal determination that infringement occurred |
| Public API / future full SDK migration, P1 for package-only promise | Stable `com.utexo.rgb.sdk` namespace and API baseline exist, but full replacement SDK and shared behavioral suite do not. Journal AAD binds origin/package/wallet/network (`ProtectedWalletFile.kt:308-318`); seed schema/policy binds key aliases (`AndroidKeystoreSeedStore.kt:254-275`); RGB receive state is server-owned | Implement a full SDK with identical public source/default/error semantics, importer for local custody/journal and server RGB state, and upgrade/recovery tests for existing wallets and in-flight operations |
| Independent assurance, P1 for production release | Current evidence is engineering self-verification with synthetic vectors/fixtures | Independent cryptographic/security review of shipped source/native artifacts, findings disposition, and live end-to-end release qualification |

Full RGB contract/consignment validation is outside the agreed lightweight-v1 scope; its absence alone is **not** being promoted into a new v1 requirement. Invoice syntax/intent checks do not establish recipient ownership, contract validity, or recoverability of server-held receive secrets. Those trust boundaries must remain explicit. The actual v1 transaction-authorization policy and unsupported operation scope still need to be settled and implemented.

## Package-only migration verdict

**Cannot promise it today.** Keeping the namespace, method names, parameter types, default values, result shapes and exception codes stable is necessary and is helped by the checked API baseline. A full SDK must also preserve persisted custody access, operation identities, pending-payment recovery, native wallet derivation/state, receive secrets, and result semantics. Restoring the same seed alone does not reproduce server-owned RGB transfer state. A replacement package must read/import that existing state automatically and continue pending operations safely.

The factory currently also requires `WalletServiceConfiguration` containing a base URL and API-key provider. A future full SDK could preserve that initializer for source compatibility and ignore/retire the credential under the agreed exception, but that compatibility adapter and migration are not yet present. No source or binary compatibility test can certify an implementation that has not been built.

The appropriate release sequence is to close the concrete SDK defects and publication metadata mismatch, freeze the backend contracts, implement the remaining required public behavior, run real two-SDK/gateway funded and recovery tests, complete device/distribution/notice/security qualification, and only then freeze an RC. The current evidence supports a well-tested engineering foundation, not a claim of 100% production completion or “only backend blockers remain.”

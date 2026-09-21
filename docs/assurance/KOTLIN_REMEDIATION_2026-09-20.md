# Kotlin remediation and verification — 2026-09-20

This record updates the concrete Kotlin findings in the September 19 independent
audit. It does not replace the outstanding backend, device, release-owner or
full-SDK migration gates with a claim of production completion. The ordinary
factory still refuses fresh payments; no provisional signing profile was enabled.

## Completed SDK work

### S1: completion uncertainty is classified by operation phase

`RgbLightningNodeGatewayBackend.mapGatewayError` now handles every 3xx/4xx carrying
a submitted operation ID before inspecting codes that are definitive at prepare.
Both `OP_KIND_MISMATCH` and `IDEMPOTENCY_KEY_REUSED` therefore preserve the operation
ID as `OperationAmbiguous`. The existing concrete-gateway coordinator path performs
one read-only status lookup, accepts only locally matched success, or retains the
exact SUBMITTING checkpoint. There is no second prepare, signature or completion.

Prepare idempotency conflicts and prepare kind/protocol mismatches remain distinct.
The generic infrastructure SPI keeps its separate error contract and preserves
evidence; its coordinator comment no longer calls a stored SUBMITTING outcome
definitively failed. Existing ordinary-factory status-only recovery remains enabled
and bypasses fresh-payment gates. This fix does not make the provisional profile
compatible with the real gateway.

New/expanded gateway regressions cover both codes directly and through the
coordinator: matching completed result, pending status, unavailable status,
mismatched txid, redacted errors, exact operation identity/evidence retention,
one GET, zero new signatures, restart with a fresh gated adapter, and local terminal
receipt replay. Prepare-phase expectations remain explicitly tested.

### S2: canonical artifact documentation

`NATIVE_ARTIFACTS.md` now links to the current source/archive, checksum manifest,
artifact tree and native build receipt instead of copying obsolete dates and hashes.
It distinguishes checked-in input ELFs from AGP-stripped packaged ELFs, and local
build/checksum evidence from signed release or independent reproduction evidence.
The stale invoice-wrapper comment was also corrected; no public type changed.

### S6: verified native evidence delivery

The local Maven publication now includes these classifiers in addition to the AAR
and facade sources:

| Classifier | Delivered artifact |
| --- | --- |
| `native-source` | Exact bundled native source `.tar.gz` |
| `native-evidence` | Deterministically ordered ZIP of checksum/artifact/build receipts, dependency/advisory inventory, SBOM, collected notices and unresolved license review |
| `native-sbom` | Exact native source/build/test CycloneDX JSON, not a claim of a minimal Android runtime inventory |

The AAR additionally contains the collected native notices, review status and SBOM
under `assets/utexo-native/`. Asset delivery is deliberate: these are present in
the final minified consumer APK, not only a source checkout or a discarded JAR
resource. No owner license was selected, unresolved notices were not invented,
and no remote registry publication was performed.

Before Android builds or publication, the typed Gradle verifier hashes every
declared input, matches the complete actual file set exactly once, requires all
12 critical native/binding/evidence inputs, and rejects duplicate, missing,
unexpected, absolute, traversal and symlink-component paths. The checksum file
itself is excluded from its own hash set. These are integrity-consistency checks,
not protection against a malicious maintainer replacing both source and manifest.

Independent review caught an earlier draft that checked only listed lines; that
draft could miss an omitted checksum. The final verifier fixes this and is tested
with 12 isolated positive/negative byte fixtures. The tests never mutate real
native artifacts or repository evidence. The generated asset task uses Android's
variant source API and typed, configuration-cache-compatible Gradle tasks.

`scripts/verify-maven-consumer.sh` resolves only the local Maven coordinate in a
fresh standalone consumer, using both Gradle Module Metadata and POM-only modes.
It checks all three classifiers byte-for-byte against canonical evidence, verifies
their complete ZIP entry set, and verifies notice/SBOM asset bytes in both the
resolved AAR and R8-minified APK. Runtime dependencies, including the Android JNA
AAR, still come transitively from publication metadata.

### S7: durable consumer recovery demonstration and integration contract

The new encrypted-journal regression creates a distinct authenticated app-owned
payment record before dispatch, using a newly generated key and exact integer
amount/fee units. On lost completion, it destroys the wallet and journal, then
reconstructs the entire caller request from persisted fields and recovers through
status only. A second restart models a crash between SDK receipt commit and app
result commit; it replays local success without a status lookup or submission.
Same-key changed intent is rejected and no in-memory request object is reused.

[The consumer recovery contract](../DURABLE_CONSUMER_RECOVERY.md) documents record
fields, commit ordering, account binding, unique application payment IDs, exact
request reconstruction and uncertainty handling. The app stores its own account
reference; it does not need to discover the SDK-private gateway user ID or use SPI.

This closes a missing host-side workflow demonstration. It is not a production app
database implementation, real Android process-kill/power-loss qualification or real
gateway E2E: the regression uses the production encrypted journal with host test
key storage and a fake signer/backend. Those evidence boundaries remain explicit.

## Reassessment of all wallet business methods

The public source/binary baseline was unchanged. Availability remains **7 implemented
names, 1 gated name, 18 unsupported names** out of 26; alias-deduplicated counts are
6 implemented behaviors, 1 gated, 17 unsupported. No quality percentage is inferred.

Implemented names remain `getBtcBalance`, `getAssetBalance`, `decodeRgbInvoice`,
`decodeRGBInvoice`, `onchainReceive`, `refreshWallet`, `syncWallet`. Fresh `sendBtc`
remains gated; same-request submitted recovery and definitive receipt replay work.

The unsupported methods were rechecked against the actual current gateway routes,
schemas and wallet backend DTOs, not just the earlier report:

| Public names | Concrete missing contract; why no safe implementation was enabled |
| --- | --- |
| `getAddress`, `rotateVanillaAddress` | Address response contains only text; no account/branch/index ownership evidence or authoritative rotation/reservation/high-water semantics |
| `listUnspents` | Missing required `exists`, `pendingBlinded` and assignment-kind evidence; nullable scalar allocation amount cannot identify fungible vs rights/nonfungible assignments |
| `estimateFeeRate` | No gateway fee-estimation endpoint/source; current integer fee contract also cannot represent the 1.5 sat/vB public default |
| `createUtxos` | Native operation-specific authorization absent; prepare lacks exact owned-output plan and durable result/count contract remains incomplete |
| `listAssets` | Balance summary is not full NIA/UDA/CFA/IFA metadata: required supply/timestamp/added-at fields absent |
| `issueAssetNia`, `issueAssetIfa` | No corresponding gateway issuance endpoints/full result/recovery contracts |
| `blindReceive`, `witnessReceive` | Required batch-transfer index absent from actual receive response; cannot substitute zero or fabricate an ID |
| `onchainSend` | Exact RGB/output/commitment authorization missing; full required batch result and durable lifecycle remain incomplete |
| `inflate` | No native inflation authorization or gateway preparation/completion contract |
| `listTransactions`, `listTransactionsByTxid` | No exact transaction-history endpoint/amount/fee/type/confirmation contract |
| `listTransfers`, `listTransfersByTxid`, `listOnchainTransfers` | Transfer summary lacks required batch index, assignment evidence and complete transfer model; filtering incomplete rows cannot supply missing facts |
| `failTransfers` | No exact targeted-state-change/result/retry endpoint contract |

A new exhaustive facade/production-adapter regression invokes all 18 unsupported
names with valid wrapper parameters plus the ordinary send gate, and asserts no
business HTTP, signature, address approval or send checkpoint is produced. The
test injects a BTC-only signer capability matching the current native implementation;
it does not pretend those methods are implemented. Backend contracts will enable
subsequent SDK DTO/policy work, not automatically complete it.

## Verification evidence

Fresh local results for this remediation:

- **151 JVM tests / 16 suites**, zero failures, errors or skips. Gateway suite is
  49 tests; encrypted-journal suite is 26. Repeated runs overlap; they are not added
  together as distinct coverage.
- Release lint, release Java API isolation, exact public JVM/Kotlin metadata
  baseline, and packaged three-ABI checks passed. No baseline was rewritten.
- **12 native distribution verifier cases passed**: valid, missing entry, tampered
  input, duplicate entry, empty inventory, absolute path, traversal, file symlink,
  directory symlink, missing required file, extra unlisted input, unrelated entry.
- Fresh **11 Android executions** on the owned `rgb_sdk_release_api36_16k` ARM64
  emulator: 5 SDK tests and 6 R8 packaged-consumer tests. Observed API 36,
  `arm64-v8a`, page size **16,384 bytes**. This is emulator evidence, not physical
  biometric/StrongBox qualification. Other API/ABI evidence remains historical.
- Isolated GMM and POM-only Maven consumers passed native source/SBOM/evidence
  resolution, transitive JNA/runtime resolution, AAR notice delivery and minified
  APK notice preservation. Repeated after verifier hardening.
- Canonical native input checksums and `git diff --check` passed.
- The existing packaged native incompatibility guard passed all four fresh process
  probes (key-first/signer-first, wrong contract/wrong checksum): no operational
  native function was entered after either incompatibility.

The final aggregate Gradle verification was repeated with configuration cache
**reused**; the native-input verifier still executed. Instrumentation XML was
retained under `build/assurance/remediation-2026-09-20/api36-16k/{sdk,release}`.
The tested consumer's staged AAR checksum equals the final SDK AAR checksum below.
Only the dedicated emulator started by this remediation was stopped afterward;
no emulator data was wiped. The final isolated Maven consumer build remains at
`/var/folders/yq/cn0q0gcx071c_9nck78202k40000gn/T/utexo-maven-consumer.S6z4eb`.

Final native source archive SHA-256:
`6d005cc072802ecd807c71bb990e9e792a52fcf832583bc91ede005630161b00`.
This archive was refreshed by the shared-core owner to include portable Core
consensus verification and its cleanup regressions, plus refreshed advisory evidence;
native signer binaries and generated bindings were not rebuilt in this remediation.

Release AAR SHA-256:
`e165252a4afa549b26cdd7753bc461557ab60a8483a853383ce296261b670909`.
Native evidence ZIP SHA-256:
`def0db4a30e276ed018d792629f34acfcdb56b3c8e05dc3efd76343d6fd83335`.
These identify local validation outputs, not a signed release.

The audit/fix loop corrected the initial Android generated-assets API wiring,
configuration-cache serialization, one test coroutine declaration and the omitted
checksum-line weakness. A transient mixed source/archive-manifest state during
cross-agent refresh correctly failed the new integrity gate; verification was
repeated after the canonical payload stabilized. These failed draft runs are not
counted as passing evidence.

## Remaining external and cross-team gates

Backend/shared protocol: exact PSBT profile, allocation-free input invariant,
fee/default/skipSync semantics, durable operation/broadcast/idempotency/result
contracts, complete lossless DTOs and real prepare/complete fixtures. Required SDK
policies and mappings must then be implemented against those contracts; the 18
unsupported names are not completed work.

App/device qualification: real app-database restart/process-death integration,
supported physical Android custody/authentication/invalidation/reboot/upgrade
coverage, runtime qualification for unexercised ABIs, and journal latency/endurance,
low-space and power-loss behavior on supported devices.

Owner/release qualification: distribution-license selection and the explicit
upstream notice gap, review of collected notices, independent cryptographic/platform
assurance, immutable reviewed versions, actual clean CI/reproduction evidence and
intended-registry distribution/signing. Delivering notices does not resolve their
legal review. No legal violation is asserted.

Full-SDK migration: the replacement implementation, authenticated server-state
export/import and unchanged-consumer migration tests still do not exist. Matching
public method signatures alone cannot prove the user's package-only upgrade promise.

The Kotlin-owned concrete recovery/documentation/delivery defects are remediated;
the product remains an engineering foundation, not a production release candidate.

# Kotlin RN-driven remediation — 2026-09-21

This report describes SDK-owned changes and verification against the RN
reference and retained real PR174 gateway fixtures. It is not a release-candidate
sign-off, a claim that every public API works, or evidence of a live gateway
complete/broadcast round trip. Existing dirty work was preserved. No backend
source, public package coordinate or app-facing signature was changed by this
remediation. The strict API baseline was refreshed only for a reviewed private
Kotlin metadata delta, as detailed below. No commit/push or payment was performed.

## Implemented and reviewed

- Corrected create count to native u8 1–255 and output size to native u32
  1–4,294,967,295 sats. Omitted request defaults authorize at most five outputs
  of 1,000 sats. Native may create fewer outputs even for `upTo=false`; the
  actual count is checked against the exact locally authorized colored plan.
  Mismatched completion/status counts never become terminal success. Compact
  terminal replay keeps original request/default bounds without recreating
  deleted PSBT/plan data or issuing new work.
- Added local estimator target validation (1–1008) and response target binding.
  Complete history snapshots share default txid filters; absent full data still
  fails closed. `skipSync=true` needs an explicit backend implementation rather
  than silently synchronizing. The RN on-chain transfer alias stays shared.
- Outgoing RGB intent checks now use raw encoded state, not RN's lossy amount
  projection. Open amounts and generic invoices may use explicit caller intent;
  fixed invoice amounts/assets cannot be overridden. Nonfungible/named rights,
  expired invoices and blind/witness mismatches are rejected before new work.
- Moved capability and outgoing invoice checks into risky transitions. A
  disabled profile/parser or expired invoice does not block status-only recovery
  of a possibly submitted operation or replay of terminal evidence. Time-sensitive
  validation is repeated after suspending backend readiness checks; late expiry
  leaves the operation SIGNED and sends nothing.
- Integrated the additive private `utexo.psbt.rgb-lib.v1` native profile. It
  takes an explicit independently trusted block-height bound and local clock,
  validates branch-0 BIP86 Bitcoin effects, binds caller identity/hash and RGB
  options, and keeps fee target rounding separate from independent hard caps.
  Create plans are exact colored-output multisets; witness recipient Bitcoin
  addresses are derived by the native invoice parser, never a handwritten URL
  parser. Full RGB transition/consignment validation remains server-owned.
- The ordinary factory, legacy profile/digests, request-hash domains, gateway
  spend gate and status-only recovery contract remain intact. New native
  capabilities are internal qualification infrastructure, not advertised
  public gateway support.
- The legacy private BTC adapter now independently rejects a different
  prepared network, caller idempotency key or canonical request hash before
  FFI, in addition to the coordinator's existing checks. Direct positive-fixture
  tests cover each mismatch during signing and retained validation; valid
  legacy signing results and digests remain unchanged.

## Public availability and true dependencies

Default public gateway availability remains **7 implemented / 1 gated / 18
unsupported**. Tests exercise all 18 unsupported methods and the ordinary BTC
gate without business HTTP or new checkpoints. These are not 18 independent
backend bugs.

| API family | Remaining dependency |
| --- | --- |
| `getAddress` | Existing route lacks derivation metadata. Preserve local ownership with metadata or a reviewed durable discovery/recovery design; a bounded scan alone creates lifetime/reinstall limits and abandoned remote reservations. |
| Address rotation | Select reuse behavior, then expose native persistent reuse/rotation semantics. |
| Unspents/assets/transfers | Complete required native records/typed assignments, not placeholders or rounded values. |
| Blind/witness receive | Same existing receive route; required native batch ID is missing. |
| Estimator / transaction history | Missing selected backend primitives. Local filtering is shared, not a request for separate routes per alias. |
| Issuance / inflation / fail-transfers | Select v1 scope; expose missing native operations. Inflation also needs a qualified authorization/result contract. |
| BTC spending | Durable submission/reconciliation beyond TTL/cache loss, pinned deployed native profile/options, trusted height context, and real end-to-end evidence. The existing GET endpoint alone does not close crash windows. |
| Create/RGB spending | Same recovery guarantees plus exact selected results/options (authoritative completion, RGB batch ID, witness blinding, etc.). A validated create transaction already establishes its actual output count; a missing GET count is not an independent blocker once completion is authoritative. Local Bitcoin policy is implemented; full RGB validation is not a prerequisite for v1. |

An SDK-owned verified output extractor could supply create plan/count evidence
from a PSBT in a future adapter; a new backend output-plan/count field is not
inherently mandatory for every profile. Completion still needs authoritative
reconciliation; local counting does not prove acceptance/broadcast.
An already-satisfied create request has no new transaction; retain native
`AllocationsAlreadyAvailable` failure rather than inventing a transaction ID.
A new no-transaction success result is not required.

Release owners still need selected feature/network scope, API credential
lifecycle, staged deployment/restore and actual end-to-end failure testing,
license approval, immutable CI/release evidence and physical-device qualification.
These are real external/decision/release dependencies, not all backend-code bugs.

## Verification

After the parent rebuilt native libraries and refreshed distribution metadata,
the full Gradle aggregate passed with **no task exclusions**:

- **170 JVM tests**, zero failures, errors or skips; repeated runs overlap and
  are not added together as distinct tests.
- **8 SDK instrumentation tests** and **6 minified packaged-AAR consumer tests**
  on the dedicated API36 ARM64 test emulator with observed **16,384-byte pages**.
  Offline tests validate/sign original BTC/create/RGB gateway fixtures, plus
  malicious/wrong-intent cases and retained-signature expiry revalidation.
- Release lint, Java API isolation/negative compilation probes, exact API
  baseline, native distribution verification and all three packaged ABI checks.
- **12 native distribution verifier adversarial cases** and **4 fresh-process
  native ABI/checksum rejection probes**. No operational FFI function executes
  after incompatible contract/checksum initialization.
- Standalone **Gradle Module Metadata and POM-only Maven consumers** compile,
  minify and package the final locally published AAR with transitive dependencies;
  native source, SBOM, provenance and notices survive both AAR/APK packaging.
  No package was published externally. Final isolated proof is retained at
  `/var/folders/yq/cn0q0gcx071c_9nck78202k40000gn/T/utexo-maven-consumer.zxcrKA`.
- The existing OSV scanner queried **205 exact locked Maven dependencies** on
  **2026-09-21 at 07:38:36 UTC**, returning **0 matches**. This includes recorded
  test/tool configurations, not unrecorded plugin graphs, platform components
  or unpublished vulnerabilities; it is not a general security certification.
- The final aggregate also passed after the legacy binding guard, with the
  baseline frozen (**no baseline update task and no exclusions**). Host-tooling
  dependency-floor checks passed. The owned test emulator was then shut down
  without wiping its data or affecting other emulators.

Final aggregate:

```sh
ANDROID_HOME=/Users/hardik/Library/Android/sdk ./gradlew \
  verifyHostToolingDependencyFloors :sdk:testDebugUnitTest :sdk:lintRelease \
  :sdk:verifyReleaseApiBaseline :sdk:verifyReleaseJvmApi \
  :sdk:verifyReleaseNativeAbis :sdk:verifyConnectedDebugAndroidTests \
  :release-smoke-app:verifyPackagedReleaseSmoke --offline
```

Final artifact identity (bytes; not projected app-download sizes):

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Release AAR, identical to staged packaged-consumer AAR | 6,017,395 | `1dd33a95cb1109b7f4755ee4b2ac7df282ef2ee699ec60ed7f38e5b26ce99bb9` |
| Minified release smoke-app APK, all packaged dependencies/ABIs | 9,086,863 | `517e814bc6f57d1a6369f2ca36dd167fa2692a2a98bdea7a05bddc5a2654eba8` |

Extracted signer libraries are 2,414,400 bytes (`arm64-v8a`), 1,999,500
bytes (`armeabi-v7a`) and 2,581,456 bytes (`x86_64`). Those figures exclude JNA
and managed dependencies. Real production download/install size depends on the
app, ABI splits, compression and store delivery; the test APK is not that estimate.

The initial final aggregate correctly failed the API baseline: changing the
**private** `UTEXOWallet.verifyOutgoingInvoice` helper from `InvoiceData` to
`LocallyDecodedInvoice` changed its Kotlin Metadata d1 payload and four private
d2 string-table entries (165–168). Parent and implementation review verified all
other baseline lines and public JVM signatures unchanged. The existing explicit
baseline regeneration task was used; no verifier was weakened. The subsequent
complete aggregate passed. Provisional runs that excluded stale distribution
hashes during rebuilding are superseded by these no-exclusion checks.

Committed offline instrumentation fixtures contain public test mnemonic material
only. All three base64 PSBT strings and SHA-256 digests were compared byte-for-byte
with the retained gateway captures. Tests never contact a gateway, indexer or
Bitcoin node and never submit or broadcast. Historical preparation IDs/timestamps
are fixture evidence, not usable live operations.

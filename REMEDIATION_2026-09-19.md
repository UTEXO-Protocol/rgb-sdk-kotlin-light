# Kotlin SDK remediation and verification — 2026-09-19

This records SDK-owned changes, not a production-release approval. Fresh BTC
sends remain disabled through ordinary construction. Backend PSBT/profile,
operation durability, remaining DTO contracts, RGB authorization and migration
state still require backend/product work. No remote publication or broadcast
was performed.

## Completed changes

- Closeable secret results retain a cleanup owner across dispatcher-return
  cancellation and across subsequent file/lock-close failures. This covers key
  factories, seed retrieval and API-key retrieval. Temporary mnemonic bytes are
  erased even when passphrase encoding fails; invalid secret constructors do not
  copy rejected material. Cancellation remains cancellation, not a native error.
- Retained SIGNED transactions are validated against current local policy before
  first submission, with exact transaction evidence compared against the original
  report. No replacement signature or PSBT is created. SUBMITTING reconciliation
  and terminal replay remain usable under a stricter/disabled fresh-payment policy.
- Definitive terminal records compact into version-2 receipts, retaining every
  idempotency key, request/operation identity, outcome and local validation report.
  Nonterminal records never lose evidence. Version-1 snapshots migrate atomically;
  malformed versions and old-reader downgrades fail closed. Terminal receipts are
  immutable, including the presence/absence of validation. Full-facade encrypted
  restart tests prove successful receipt replay makes no new backend mutation.
- `syncWallet` and `refreshWallet` now call the gateway's completed combined
  Bitcoin sync/RGB refresh operation. `completedAt` is explicitly client-observed
  completion time; no server timestamp or tip height is fabricated.
- Seed policy, unlocked-session lifetime, StrongBox limitations, app lifecycle
  responsibilities and finite journal capacity are documented in README/SECURITY.
- Isolated Maven validation uses a fresh standalone consumer with only the SDK
  coordinate, no project reference or manually-added runtime dependencies. Both
  Gradle Module Metadata and POM-only resolution compile and R8-minify, including
  correct transitive JNA AAR selection. Artifacts stay in `build/validation-maven`.
- The minified release consumer now actually signs and verifies a finalized
  Taproot witness through the packaged facade, using a public synthetic fixture
  and a test-only backend with no network/broadcast.
- Host-only Android Unified Test Platform dependencies are narrowly patched and
  locked: Netty 4.1.137.Final, Bouncy Castle 1.85, Commons Lang 3.18.0 and HttpClient
  4.5.14. These are minimum floors for recognized stable versions; newer and unknown
  version formats are never downgraded or guessed. These rules do not change
  published runtime dependencies. A fresh scan of all 205 exact locked Maven
  coordinates returned zero OSV advisory matches. CI checks native
  archive integrity, Rust tests and native/Gradle OSV results, plus an emulator
  matrix using the verified emulator-action commit.

## Fresh verification

- 123 JVM tests passed, zero failures/errors/skips.
- Release lint, Java negative/positive API probes, reviewed ABI/Kotlin metadata
  baseline, all three packaged native ABIs, and R8 release build passed.
- Each platform below passed three SDK and three minified-release instrumentation
  tests (18 executions total), including actual native signing, six current-policy
  limits on a retained signature, device Keystore and exclusive storage ownership.

| Local emulator | ABI | Observed kernel page size | Result |
| --- | --- | --- | --- |
| Android 24 / 7.0 | arm64-v8a | 4,096 bytes via `/proc/self/smaps` | 6 passed |
| Android 36 / 16 | arm64-v8a | 4,096 bytes | 6 passed |
| Android 36 / 16, Google APIs ps16k | arm64-v8a | 16,384 bytes via `getconf PAGESIZE` | 6 passed |

The real-native policy test uses the shared `native-signing-multi-smoke.json`
public vector: two nonexistent inputs at BIP86 child indices 2 and 3. Tightening
input count, output count, child index, absolute fee, PSBT bytes or fee rate rejects
first submission and preserves the retained evidence. The original policy submits
exactly the original signed bytes once to the test-only backend.

A 500-operation sustained-use regression compacted 20 MB of synthetic PSBT
payloads into a 271,756-byte encrypted journal while retaining the first and last
identities. One desktop JCE run took approximately 2.9 seconds. This is a bounded
correctness/storage measurement, **not** a physical-device latency SLA. The
64 MiB/100,000-record limit remains; no evidence is evicted to bypass capacity.

Commands (with the installed Android SDK configured):

```sh
./gradlew verifyHostToolingDependencyFloors :sdk:testDebugUnitTest :sdk:lintRelease :sdk:verifyReleaseJvmApi :sdk:verifyReleaseApiBaseline :sdk:verifyReleaseNativeAbis --no-daemon --max-workers=2
./gradlew :sdk:verifyConnectedDebugAndroidTests :release-smoke-app:verifyPackagedReleaseSmoke --no-daemon --max-workers=2
bash scripts/verify-maven-consumer.sh
```

The latest isolated Maven evidence was retained at
`/private/var/folders/yq/cn0q0gcx071c_9nck78202k40000gn/T/utexo-maven-consumer.iTSGvA`.
Normal wallet/key API signatures are unchanged. Reviewed baseline changes are
infrastructure implementation metadata and concrete implementations of existing
gateway sync/refresh interface methods.

## Still not proven or authorized

Physical-device biometric invalidation, hardware-backed custody transitions,
power-loss/OS-upgrade behavior, production performance at journal capacity,
all shipped ABIs on real devices, independent cryptographic/security review,
owner-approved project licensing, clean immutable release provenance and remote
package publication remain separate release gates. Finite receipt retention is
implemented; unbounded storage and automatic full-SDK/server-state migration are
not. Seed recovery alone does not recover server-owned RGB state or lost app
requests. CI configuration is present but no remote CI run was triggered here.

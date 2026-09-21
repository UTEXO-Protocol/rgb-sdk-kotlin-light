# Kotlin SDK audit/fix closure — 2026-09-19

This is a local engineering verification record, not approval to publish or
enable funded sends. The earlier `REMEDIATION_2026-09-19.md` and cross-workspace
re-audit describe earlier snapshots; their counts must not be presented as proof
of the final invoice-enabled artifacts.

## Corrected findings

| Finding | Change and evidence |
| --- | --- |
| K-01: recursive JSON stack exhaustion inside bounded HTTP bodies | Constant-space lexical preflight on both success and error bodies, depth 64, 100,000 tokens and existing byte ceiling. Tests cover 100,000 nested arrays in a 200 KB response, exact nesting boundary, escaped strings, token/size limits and mixed/malformed containers. Actual R8 ordinary-factory HTTP tests exercise hostile 200/400 responses. |
| K-02: native compatibility initialization bypass | One sticky, synchronized guard invokes generated contract-version and API-checksum checks before handwritten key helpers, signer construction and native operations. Concurrency/failure tests and four isolated release-AAR JVM probes cover wrong contract/checksum, key-first/signer-first and retries. All operational native fixture exports terminate if reached; a typed compatibility failure must occur first. This is not a signature/authenticity check. |
| K-03: arbitrary completion 4xx treated as definitive rejection | Unknown/malformed/authentication/intermediary-originated completion 4xx responses remain ambiguous, retain SUBMITTING and reconcile once by GET. Known kind/idempotency conflicts also retain nonterminal evidence. No automatic prepare, signing or completion retry. Restart tests recover by GET with the fresh-payment gate disabled. |
| K-04: stale build/source provenance | Shared build regenerated every Android ABI and the binding together, with a new exact local build receipt, source snapshot, artifact manifest and checksums. All twelve manifest checksum entries pass. This is locally observed provenance, not signed immutable release or independent reproduction. |
| SDK invoice/receive gap | Shared native parser wired into both decoder names and ordinary-factory `onchainReceive`. Native raw state evidence prevents public Any/null or Fungible(0) projections from hiding changed intent. Adapter transmits exact supported request values; facade verifies network/asset/schema/amount/assignment/mode/recipient/expiry. |
| Storage upper bounds and injected disk failure | Actual 100,000-record authenticated journal snapshot rejects an additional identity without deleting old evidence; existing identities remain updateable. Oversized 64 MiB+1 snapshot rejected before decoding. Partial candidate write/IOException poisons the session, retains the prior authenticated checkpoint and permits safe reopening. |
| Second-pass seed file hardening | Seed reads are bounded before allocation and check exact EOF. Owner UID, regular-file type, single-link and SDK-directory validation cover seed, lock and temporary paths. JVM sparse-file/link regressions and actual Android symlink tests verify untouched external referents. Android hardlink fixture creation was independently blocked by SELinux, as distinguished below. |

Public wallet/key signatures, names, parameter types and defaults are preserved.
Internal raw invoice evidence does not enter the public API. Conservative behavior
was tightened intentionally: unsupported/contradictory receive responses fail
closed instead of returning a guessed or partially checked result. A future full
SDK must satisfy the same source, default, error, behavior and persisted-state
conformance requirements; its absence prevents proof of a package-only migration.

## Receive trust boundary

The native parser checks supported canonical invoice syntax/checksums and exposes
exact u64 assignments. It does not validate RGB contract history or consignment
state. Recipient ownership cannot be independently attributed to the wallet seed
from today's DTO; the gateway holds receive state and blind-secret recovery.
Lower `blindReceive` / `witnessReceive` still require batch metadata the gateway
does not provide. `onchainReceive` preserves a null batch index.

Effective duration is 86,400 seconds when omitted, otherwise exactly the requested
60–2,592,000 seconds. Confirmations default to 0, within 0–100. Amounts above the
gateway's exact numeric JSON range (2^53−1) fail before HTTP. Invoice expiry is
mandatory and must fall between local request-start/end plus effective duration;
no clock skew is silently accepted. The response expiry key is required, explicit
null is preserved, and any nonnull value must equal the locally decoded expiry.
An absent raw amount stays absent even when RGB-lib compatibility projection
shows Fungible(0); a requested amount requires matching raw and public fungible
amounts. Generic asset requests cannot be narrowed to a schema. Only omitted or
`assetOwner` assignment names fit ordinary receive intent. Decoding never fetches
invoice transport endpoints.

## Verification record

Fresh verification against the September 19 invoice-enabled native artifacts:

- **147 JVM tests, 16 suites, zero failures/errors/skips.** The complete forced
  build executed all 170 requested Gradle tasks successfully, including release
  lint, positive/negative public-JVM API probes, reviewed ABI/Kotlin metadata,
  all three native ABI/page-alignment checks, and R8 consumer/fixture builds.
  After the device fixture correction, a second forced host/release cycle passed
  all 102 tasks; the release AAR hash remained identical.
- **Four isolated incompatible-library probes passed** against the actual
  release AAR and regenerated native ABI. These are host JVM negative tests,
  not a mismatched library injected into a minified Android application. Real
  Android R8 tests separately exercise successful guarded native initialization.
- **Both standalone Maven consumers passed:** Gradle Module Metadata and
  POM-only resolution compile and R8-minify with only the SDK coordinate and
  its transitive JNA 5.17.0 AAR/coroutines/serialization/HTTP dependencies.
- **33 fresh Android test executions passed**, as detailed below. The actual
  native parser preserves u64 maximum `18446744073709551615`, raw hidden amount
  and omitted-state evidence, and rejects malformed encodings. The minified
  ordinary factory provisions a synthetic Keystore seed, validates registration,
  creates blind/witness receives against loopback fixtures, preserves null DTO
  expiry, rejects hidden amounts/unrequested schemas, and validates exact
  asset/amount100/confirmations100/duration60 parameters. No production backend,
  live RGB asset or payment is used by these fixtures.

| Dedicated local emulator | Runtime ABI | Observed kernel page size | SDK tests | R8 consumer tests |
| --- | --- | --- | --- | --- |
| Android 24 / 7.0 | arm64-v8a | 4,096 bytes, `/proc/self/smaps` | 5 passed | 6 passed |
| Android 36 / 16 | arm64-v8a | 4,096 bytes, `getconf PAGESIZE` | 5 passed | 6 passed |
| Android 36 / 16, Google APIs ps16k | arm64-v8a | 16,384 bytes, `getconf PAGESIZE` | 5 passed | 6 passed |

Android link-test precision: symlinks for seed, lock and temporary paths are
actually created and rejected by the SDK without changing external referents.
All three emulator policies prevented app-created hardlinks with `EACCES`;
filtered audit logs confirm SELinux `avc: denied { link }`, `permissive=0`.
The test asserts no target/referent mutation and reports platform prevention.
This is not a claim that the Android SDK examined a real hardlinked file.
The initial fixture cleanup masked that OS error; cleanup was corrected and
the final full device matrix rerun. JVM file-link validation and sparse-file
regressions remain separate host evidence.

The actual 100,000-record JVM capacity fixture was 17,188,976 encrypted bytes;
a single desktop run took approximately four seconds. This is not device
latency/throughput qualification or an endurance claim.

Commands, with the installed Android SDK configured:

```sh
./gradlew verifyHostToolingDependencyFloors :sdk:testDebugUnitTest :sdk:lintRelease :sdk:verifyReleaseJvmApi :sdk:verifyReleaseApiBaseline :sdk:verifyReleaseNativeAbis :sdk:assembleDebugAndroidTest :release-smoke-app:assembleRelease :release-smoke-app:assembleReleaseAndroidTest --no-daemon --max-workers=2 --rerun-tasks
./gradlew :sdk:verifyConnectedDebugAndroidTests :release-smoke-app:verifyPackagedReleaseSmoke --no-daemon --max-workers=2
bash scripts/verify-native-compatibility.sh
bash scripts/verify-maven-consumer.sh
shasum -a 256 -c NativeSource/SHA256SUMS
git diff --check
```

Device XML and UTP evidence is retained under
`build/assurance/invoice-final-2026-09-19/{api24,api36,api36-16k}`. Temporary
standalone proof directories are `/var/folders/yq/cn0q0gcx071c_9nck78202k40000gn/T/utexo-native-compatibility.qKN3Fs`
and `/var/folders/yq/cn0q0gcx071c_9nck78202k40000gn/T/utexo-maven-consumer.vpUgXE`.
The API baseline update was reviewed: one private-helper Kotlin metadata `d1`
line changed on `UTEXOWallet`; no public JVM descriptors, annotations, defaults
bridges or `d2` source-parameter strings changed in that update.

### Built artifact identity, not release attestation

Every checked-in native library matches `NativeSource/native-build.json`.
Android Gradle Plugin performs an additional ELF strip step while packaging,
so the packaged hashes intentionally differ from the native-input receipt.
Observed packaged ELF identities are:

| ABI | AAR library bytes | Packaged ELF SHA-256 |
| --- | --- | --- |
| arm64-v8a | 2,376,280 | `7a6e43467690a78bb97a0af11f4d47f0fc252f4be89091240ec329579f19b275` |
| armeabi-v7a | 1,972,064 | `6c1f16e23d74ea595613e9e46e69b896e68149053b57619a2646c94494651016` |
| x86_64 | 2,534,944 | `6481ab005a3130d5c607e5181d087b2d2f3904a50c28a09a2a5e9c178c9d2f43` |

The tested arm64 ELF is identical in the release AAR and minified APK.
The observed AAR SHA-256 is
`0db1923e3d4b8ab639e4213ba86c9e0c926094f73344d2f6a9d03daf48ef8faa`;
the local debug-key-signed minified consumer APK SHA-256 observed during the
three-device matrix was
`9a750e9b947a2e338084bce233db406ef5490ff38006261dcb5cac8ceab40784`.
The subsequent forced fixture rebuild produced APK SHA-256
`f59959de920f5965561c073d722ceb58538fd3fa1fd08fede4da78633153b9df`
while retaining the same release AAR; no whole-test-APK reproducibility claim
is made. The APK is a non-shipped local test fixture, not the SDK publication.
These identify local build outputs, not remotely published or production-signed
packages.

## Remaining release evidence outside local closure

Fresh sends remain default-off. Frozen backend PSBT/profile and vanilla-only RGB
allocation evidence, durable prepare/complete/status semantics, authoritative
remaining DTOs and funded timeout/restart interoperability are backend/contract
gates. Local RGB transaction authorization and supported operation capabilities
must not be enabled by assuming those missing facts.

Physical-device biometric/StrongBox/invalidation, reboot/power-loss/OS-upgrade
behavior, production journal latency/endurance, armeabi-v7a runtime coverage,
independent security/cryptographic review, owner-approved licensing, immutable
signed release provenance and a clean remote release workflow remain separate
release gates. Emulator Keystore success does not certify hardware-backed
custody, per-signature biometrics or complete process-memory erasure. No remote
package was published, no funded transaction was made and no backend state was
modified by these Kotlin fixtures.

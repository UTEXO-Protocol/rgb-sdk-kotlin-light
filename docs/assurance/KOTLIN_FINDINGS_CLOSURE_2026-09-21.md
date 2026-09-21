# Kotlin SDK findings closure — September 21, 2026

This follow-up implements backend-independent work identified by the RN/gateway
recheck. It does not activate fresh spending through the ordinary factory, change
backend source, send payments, or certify a release candidate. Earlier working
tree changes were preserved. Native source/binaries/provenance were rebuilt by
the shared-core workstream; the Kotlin changes consume that reviewed extractor.

## Implemented

- Changed `FeeRate.DEFAULT` from 1.5 to **2 sat/vB**. Fractional value types remain
  exact; explicit fractional gateway requests are rejected, never rounded. This
  is an intentional pre-release default correction, not a claim that every
  historical request hash is unchanged. Retrying work requires its original
  request and idempotency key.
- Added request-aware gateway checks before CREATED journal persistence or
  credential loading: exact whole fees, BTC minimum amount, current create count
  bounds and P2TR dust floor, and unsupported `skipSync=true`.
- Implemented current-contract create prepare/complete/status adapters behind
  the existing disabled-by-default qualification gate. The public factory still
  does not select the internal create-capable signing profile.
- Create preparation sends explicit `upTo`, count 5 and size 1,000 defaults;
  the gateway accepts 1–50 outputs and the local P2TR signer requires at least
  330 sats (the server schema's lower 294 limit does not override local policy).
- Extracted PSBT output hints are not trusted ownership: account/branch/index,
  count, uniqueness and size are checked, then the local signer re-derives the
  exact outputs from the seed and applies full Bitcoin policy before signing.
- Completion route selection is bound to the retained operation kind, request
  hash, idempotency key, network and prepared-PSBT digest. A create result requires
  a canonical matching transaction ID and actual count equal to the validated
  plan. Null/mismatched IDs, inconsistent counts and uncertain failures remain
  ambiguous; there is no replacement payment or automatic resubmission.
- Read-only create recovery uses the original authenticated signed checkpoint,
  request, operation intent, preparation digest/lifetime and transaction ID.
  Only `completed` with matching evidence can derive the count from the retained
  validated plan. Pending/expired remains unresolved. Recovery works after TTL,
  with fresh spending disabled, without requiring signing keys or native parsing
  again. That client behavior does not fix the backend's crash-reconciliation gap.
- `PreparedTransaction` owns an immutable defensive copy of create output hints,
  preventing a caller-owned infrastructure list from mutating retained intent.
- Invalid preparation clocks fail before HTTP; backwards response clocks fail
  closed. Session identity is rechecked after credential/HTTP suspension and
  create extraction. Cancellation during extraction cannot return a usable
  preparation. Completion transaction IDs must be canonical lowercase.

The networking skill's error/cancellation/credential guidance was applied to the
existing Kotlin/OkHttp implementation; no Expo or React dependency was added.

## Audit and verification

An independent reviewer found no new ordinary-path authorization or replay defect
in the gateway/coordinator changes. The review also verified that the API baseline
diff excluding Kotlin compiler metadata is empty. The only metadata differences
are `PreparedTransaction`'s property initialization and gateway private/internal
helpers plus an override of an existing inherited SPI method. The baseline was
intentionally refreshed after review; no public JVM declaration was removed,
changed or added, and the public/negative compilation guard was not weakened.

| Check | Result |
| --- | --- |
| Full source JVM suite | **182 tests passed**, no failures/errors/skips. |
| Release lint, packaged JVM API and negative consumer compilation | Passed. |
| Reviewed API baseline | Passed; no public JVM declaration change. |
| Native evidence and three Android ABIs | Passed. |
| SDK instrumentation | **8 tests passed**, including retained real gateway create fixture extraction and local signing. |
| Minified packaged-AAR consumer | **6 tests passed**, including ordinary-factory receive validation, actual key storage, signing, hostile JSON and the shipped default fee of exactly 2 sat/vB. |
| Isolated Maven consumers | Both Gradle Module Metadata and POM-only modes passed; native evidence/notices preserved in AAR and minified APK. |

Instrumentation used only the existing dedicated `rgb_sdk_release_api36_16k`
ARM64 emulator, Android API36, with verified **16,384-byte pages**. The owned
emulator was stopped after final checks; no user device was accessed and the AVD
was not wiped. Real fixture checks are offline and do not
claim a successful live gateway broadcast/completion/restart round trip. The
packaged consumer is not a new public create-spending integration path.

One initial build correctly failed while native checksums were being regenerated;
a temporary compile-only check omitted that unfinished task. It is **not** release
evidence. The final source, lint, API, native and emulator aggregate ran with no
task exclusions against refreshed native inputs. A final frozen-baseline rerun
also passed without the baseline-update task. The final AAR and locally published
Maven AAR were compared byte-for-byte and matched. Isolated consumer evidence was
retained at `/var/folders/yq/cn0q0gcx071c_9nck78202k40000gn/T/utexo-maven-consumer.w9flGI`.

Release AAR: **6,042,519 bytes**,
SHA-256 `81835d660ef355e114ea89ca62e25dfd8ad29c4e1251e7478a50e7c250e11e83`.
Minified smoke APK: **9,109,839 bytes**,
SHA-256 `8204c4393ab3740f69fdcc2a89cfd93135cccd88b209c6d4d17fda9a9f0eb41d`.
The smoke APK is a test application, not SDK-only app overhead. Uncompressed
signer ELF sizes are arm64-v8a 2,420,472 bytes, armeabi-v7a 2,003,964 bytes and
x86_64 2,588,048 bytes; these are not summed for a single-ABI installation.

## Remaining constraints

The ordinary gateway availability inventory stays **7 implemented, 1 gated BTC
method, 18 unsupported methods**. Internal create transport/signing qualification
is real SDK progress, but not counted as an enabled app-facing gateway method.
Issuance is excluded from intended release scope; omitted inflation/public failure
handling is not automatically a mandatory backend feature.

Real backend dependencies remain: authoritative crash/TTL recovery, BTC pending
RGB and overlapping-input selection guarantees, missing required receive/send
batch IDs, unspent/asset/transfer data, selected option forwarding and correct
native error/data handling. Complete Bitcoin history/estimation need an actual
source if included. Other return fields are not fabricated to inflate supported
counts. Final staging integration, device/OS matrix, legal/security approval and
full SDK replacement qualification remain release work, not resolved by these
unit tests or by a backend response change alone.

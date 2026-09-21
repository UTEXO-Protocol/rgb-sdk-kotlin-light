# Native signer artifact provenance

The checked-in Android signer is built from the shared `utexo-local-signer`
source, bundled in [NativeSource/utexo-local-signer-source.tar.gz](NativeSource/utexo-local-signer-source.tar.gz).
A standalone checkout therefore contains the source needed to audit the native
implementation; it does not depend on an unpublished adjacent workspace.

Use the machine-readable files as the canonical identities, rather than copying
hashes from a historical build report:

- [NativeSource/SHA256SUMS](NativeSource/SHA256SUMS): source, notices, dependency
  inventory, generated binding, native input ELF files and build-receipt checksums.
- [NativeSource/artifact-manifest.json](NativeSource/artifact-manifest.json): exact
  paths, sizes and digests for the declared artifact roots.
- [NativeSource/native-build.json](NativeSource/native-build.json): observed build
  date, exact Rust/Cargo/NDK versions, minimum Android API, code-generation flags,
  source-file identities, generated bindings and native output identities.
- [NativeSource/native-source.cdx.json](NativeSource/native-source.cdx.json) and
  [dependencies.json](NativeSource/dependencies.json): native source/build/test
  dependency inventory, not a minimal runtime dependency list.
- [THIRD_PARTY_RUST_NOTICES.md](NativeSource/THIRD_PARTY_RUST_NOTICES.md) and
  [license-review.json](NativeSource/license-review.json): collected original
  notices and explicit unresolved owner-review items. Neither is a completed
  license determination.

From the repository root, verify the declared bytes before using or extracting them:

```sh
shasum -a 256 -c NativeSource/SHA256SUMS
```

Extract the source archive into a new temporary directory and run `cargo test
--locked` there. Its platform packaging scripts expect the documented shared-core,
Swift and Kotlin three-directory workspace layout. Reproduction requires the
toolchain and dependency inputs recorded in the receipt; a passing source test is
not proof that a compiled artifact was reproduced.

## Native input versus published output

The manifest covers `sdk/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}`. Android
Gradle Plugin may strip these native inputs while building the AAR, so their hashes
intentionally differ from `jni/...` entries extracted from `sdk-release.aar`.
Do not compare a packaged ELF against an input-ELF hash. Release validation records
both the complete AAR identity and its packaged native identities separately.

The local Maven validation publication also carries a `native-source` archive,
`native-evidence` archive and `native-sbom` JSON classifier. The AAR contains the
native notices and review status under `assets/utexo-native/`, where they survive
normal Android asset packaging. Run the local-only consumer validation with:

```sh
ANDROID_HOME=/path/to/android-sdk bash scripts/verify-maven-consumer.sh
```

These artifacts preserve the currently collected evidence; they do not close the
missing upstream notice or select a distribution license. No local receipt,
checksum, SNAPSHOT publication or emulator result is a signed release attestation.
Production publication remains gated by the owner/license, independent security,
backend interoperability, device and release requirements in `README.md` and
`SECURITY.md`.

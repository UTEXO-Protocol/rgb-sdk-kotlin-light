# Native signer artifact provenance

The checked-in `libutexo_local_signer.so` libraries were built from
`utexo-local-signer` 0.1.0 with its locked dependency graph. The native source
is maintained as the shared signer core alongside the Swift and Kotlin SDK
repositories; it is not duplicated in this repository.

Build inputs for this snapshot:

- Rust 1.88.0 (`6b00bc3880198600130e1cf62b8f8a93494488cc`)
- Cargo lockfile SHA-256:
  `0a23166791ea9724859b48d72c97be16ea8699c3ef74c48004cde44a8be8fc3d`
- Android NDK 28.2.13676358 (r28c)
- cargo-ndk 4.1.2
- Android API 24 minimum
- Cargo release profile with locked dependencies, thin LTO, one codegen unit,
  and stripped symbols
- Rust and C source paths remapped so build-machine paths are not embedded in
  the distributed libraries

SHA-256 checksums:

```text
c22d541eb20305d6b97339fe8b007e210d94d71366783cbfef06b6eeed149a37  sdk/src/main/jniLibs/arm64-v8a/libutexo_local_signer.so
8bce91c219be7eb2c7778507a7ad4ede916115fa9a877845e841b36105599a59  sdk/src/main/jniLibs/armeabi-v7a/libutexo_local_signer.so
98cede1c96f1d8f22c8c3d56274000de37f87c1716a0f7b09a8f927b43473140  sdk/src/main/jniLibs/x86_64/libutexo_local_signer.so
```

These checksums identify this source snapshot; they are not a signed release
attestation. Do not publish a production release until the packaging and
security gates in `README.md` and `SECURITY.md` are closed.

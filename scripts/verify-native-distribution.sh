#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/utexo-native-distribution-classes.XXXXXX")"
javac --release 17 -d "$classes_dir" \
    "$repo_dir/buildSrc/src/main/java/com/utexo/build/NativeDistributionVerifier.java" \
    "$repo_dir/scripts/native-distribution/NativeDistributionVerifierTest.java"
java -cp "$classes_dir" com.utexo.build.NativeDistributionVerifierTest
printf 'Native distribution verifier classes retained at %s\n' "$classes_dir"

#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
proof_dir="$(mktemp -d "${TMPDIR:-/tmp}/utexo-native-compatibility.XXXXXX")"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
"$repo_dir/gradlew" -p "$repo_dir" :sdk:bundleReleaseAar --no-daemon --max-workers=2
node "$repo_dir/scripts/native-compatibility/generate-fixture.mjs" \
    "$repo_dir/sdk/src/main/kotlin/com/utexo/rgb/sdk/internal/ffi/utexo_local_signer.kt" "$proof_dir"

# Host-only testing dependency; never added to the published AAR/runtime graph.
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar \
    -o "$proof_dir/jna.jar"
node -e 'const fs=require("fs"),c=require("crypto"); if(c.createHash("sha256").update(fs.readFileSync(process.argv[1])).digest("hex")!=="b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0") throw Error("Host JNA checksum mismatch")' "$proof_dir/jna.jar"
unzip -p "$repo_dir/sdk/build/outputs/aar/sdk-release.aar" classes.jar > "$proof_dir/sdk.jar"
stdlib="$(rg --files "$gradle_cache/org.jetbrains.kotlin/kotlin-stdlib/2.2.21" | rg '/kotlin-stdlib-2\.2\.21\.jar$')"
coroutines="$(rg --files "$gradle_cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2" | rg '/kotlinx-coroutines-core-jvm-1\.10\.2\.jar$')"
test_classpath="$proof_dir/sdk.jar:$proof_dir/jna.jar:$stdlib:$coroutines"
javac -cp "$test_classpath" -d "$proof_dir" "$repo_dir/scripts/native-compatibility/NativeCompatibilityProbe.java"
case "$(uname -s)" in
    Darwin) linker=(-dynamiclib); suffix=dylib ;;
    Linux) linker=(-shared -fPIC); suffix=so ;;
    *) printf 'Unsupported native compatibility test host\n' >&2; exit 1 ;;
esac
for variant in contract checksum; do
    if [ "$variant" = contract ]; then bad_contract=1; bad_checksum=0; checks=1
    else bad_contract=0; bad_checksum=1; checks=2; fi
    fixture="$proof_dir/libincompatible-$variant.$suffix"
    cc "${linker[@]}" -Wall -Wextra -Werror -DBAD_CONTRACT="$bad_contract" -DBAD_CHECKSUM="$bad_checksum" \
        "$proof_dir/incompatible.c" -o "$fixture"
    for entry in key signer; do
        java -cp "$proof_dir:$test_classpath" NativeCompatibilityProbe "$fixture" "$proof_dir/symbols.txt" "$entry" "$checks"
    done
done
printf 'Isolated incompatible-ABI proof retained at %s\n' "$proof_dir"

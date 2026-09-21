#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
consumer_dir="$(mktemp -d "${TMPDIR:-/tmp}/utexo-maven-consumer.XXXXXX")"

"$repo_dir/gradlew" -p "$repo_dir" :sdk:publishReleasePublicationToValidationLocalRepository --no-daemon --max-workers=2
cp "$repo_dir/maven-consumer-smoke/settings.gradle.kts" "$consumer_dir/settings.gradle.kts"
cp "$repo_dir/maven-consumer-smoke/build.gradle.kts" "$consumer_dir/build.gradle.kts"
cp "$repo_dir/maven-consumer-smoke/proguard-rules.pro" "$consumer_dir/proguard-rules.pro"
cp "$repo_dir/gradle.properties" "$consumer_dir/gradle.properties"
mkdir -p "$consumer_dir/src"
cp -R "$repo_dir/maven-consumer-smoke/src/main" "$consumer_dir/src/main"

# Separate invocations also exercise POM-only consumers, not just Gradle Module Metadata.
for pom_only in false true; do
    "$repo_dir/gradlew" -p "$consumer_dir" clean assembleRelease verifyResolvedRuntime \
        -PvalidationRepository="$repo_dir/build/validation-maven" -PpomOnly="$pom_only" \
        -PnativeEvidenceDirectory="$repo_dir/NativeSource" \
        --no-configuration-cache --no-daemon --max-workers=2
done
printf 'Isolated Maven consumer proof retained at %s\n' "$consumer_dir"

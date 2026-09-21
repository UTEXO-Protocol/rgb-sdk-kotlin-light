import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "9.1.0"
}

android {
    namespace = "com.utexo.rgb.sdk.releasesmoke"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.utexo.rgb.sdk.mavenconsumer"
        minSdk = 24
        targetSdk = 36
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val nativeEvidence by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    // Deliberately the sole SDK/runtime dependency: POM or GMM must supply the rest.
    implementation("com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT")
    nativeEvidence("com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT:native-source@tar.gz")
    nativeEvidence("com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT:native-evidence@zip")
    nativeEvidence("com.utexo.rgb:rgb-sdk-kotlin-light:0.1.0-SNAPSHOT:native-sbom@json")
}

tasks.register("verifyResolvedRuntime") {
    dependsOn("assembleRelease")
    doLast {
        val resolved = configurations.getByName("releaseRuntimeClasspath").incoming.artifacts.artifacts
        val names = resolved.map { it.file.name }
        check(names.any { it.startsWith("rgb-sdk-kotlin-light-") && it.endsWith(".aar") })
        check(names.any { it.startsWith("jna-5.17.0") && it.endsWith(".aar") }) { "JNA must resolve as AAR with Android native libraries" }
        for (prefix in listOf("kotlinx-coroutines-core", "kotlinx-serialization-json", "okhttp-", "okio-")) {
            check(names.any { it.startsWith(prefix) }) { "Missing transitive runtime: $prefix" }
        }
        check(resolved.none { it.id.componentIdentifier is org.gradle.api.artifacts.component.ProjectComponentIdentifier })
        val expected = file(providers.gradleProperty("nativeEvidenceDirectory").get())
        val evidence = nativeEvidence.resolve()
        check(evidence.size == 3) { "All three standalone native evidence artifacts must resolve" }
        check(evidence.single { it.name.endsWith("-native-source.tar.gz") }.readBytes()
            .contentEquals(expected.resolve("utexo-local-signer-source.tar.gz").readBytes()))
        check(evidence.single { it.name.endsWith("-native-sbom.json") }.readBytes()
            .contentEquals(expected.resolve("native-source.cdx.json").readBytes()))
        val expectedNames = setOf("THIRD_PARTY_RUST_NOTICES.md", "license-review.json", "native-source.cdx.json",
            "SHA256SUMS", "artifact-manifest.json", "native-build.json", "dependencies.json", "advisories.json")
        ZipFile(evidence.single { it.name.endsWith("-native-evidence.zip") }).use { zip ->
            check(zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet() == expectedNames)
            expectedNames.forEach { name ->
                check(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }.contentEquals(expected.resolve(name).readBytes())) {
                    "Published native evidence differs: $name"
                }
            }
        }
        val aar = resolved.single { it.file.name.startsWith("rgb-sdk-kotlin-light-") && it.file.extension == "aar" }.file
        val apk = layout.buildDirectory.file("outputs/apk/release/isolated-maven-sdk-consumer-release-unsigned.apk").get().asFile
        for (archive in listOf(aar, apk)) {
            ZipFile(archive).use { zip ->
                for (name in listOf("THIRD_PARTY_RUST_NOTICES.md", "license-review.json", "native-source.cdx.json")) {
                    val entry = checkNotNull(zip.getEntry("assets/utexo-native/$name")) { "Missing native notice asset in ${archive.name}: $name" }
                    check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(expected.resolve(name).readBytes())) {
                        "Native notice asset changed in ${archive.name}: $name"
                    }
                }
            }
        }
        println("Verified isolated Maven runtime: " + names.sorted().joinToString())
        println("Verified native source, SBOM, evidence, and notices preserved in AAR and minified APK")
    }
}

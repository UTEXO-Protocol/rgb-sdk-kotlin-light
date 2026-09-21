import java.util.zip.ZipFile
import com.utexo.build.HardenReleaseJvmApiTask
import com.utexo.build.VerifyReleaseJvmApiTask
import com.utexo.build.PublicApiBaselineTask
import com.utexo.build.NativeNoticeAssetsTask
import com.utexo.build.VerifyNativeDistributionInputsTask
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.utexo.rgb"
version = "0.1.0-SNAPSHOT"

val nativeEvidenceDirectory = rootProject.layout.projectDirectory.dir("NativeSource")
val packagedNativeNoticeNames = listOf("THIRD_PARTY_RUST_NOTICES.md", "license-review.json", "native-source.cdx.json")
val nativeEvidenceNames = packagedNativeNoticeNames + listOf(
    "SHA256SUMS", "artifact-manifest.json", "native-build.json", "dependencies.json", "advisories.json",
)
val verifyNativeDistributionInputs by tasks.registering(VerifyNativeDistributionInputsTask::class) {
    repositoryDirectory.set(rootProject.layout.projectDirectory)
    checksums.set(nativeEvidenceDirectory.file("SHA256SUMS"))
    nativeInputs.from(rootProject.fileTree("NativeSource"), fileTree("src/main/jniLibs"),
        file("src/main/kotlin/com/utexo/rgb/sdk/internal/ffi/utexo_local_signer.kt"))
}
val stageNativeNotices by tasks.registering(NativeNoticeAssetsTask::class) {
    dependsOn(verifyNativeDistributionInputs)
    noticeFiles.from(packagedNativeNoticeNames.map { nativeEvidenceDirectory.file(it) })
    outputDirectory.set(layout.buildDirectory.dir("generated/nativeNoticeAssets"))
}
val nativeEvidenceZip by tasks.registering(Zip::class) {
    dependsOn(verifyNativeDistributionInputs)
    from(nativeEvidenceDirectory) { include(nativeEvidenceNames) }
    archiveClassifier.set("native-evidence")
    archiveBaseName.set("rgb-sdk-kotlin-light")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val preparedUniFfiDirectory = layout.buildDirectory.dir("generated/sources/uniffiPrepared/kotlin")
val originalUniFfiSource = file("src/main/kotlin/com/utexo/rgb/sdk/internal/ffi/utexo_local_signer.kt")
val prepareUniFfiBinding by tasks.registering(Copy::class) {
    from(originalUniFfiSource)
    into(preparedUniFfiDirectory)
    rename { "utexo_local_signer_prepared.kt" }
    inputs.property("visibilityRewriteVersion", 6)
    filter { line: String ->
        var rewritten = if (line == "@file:Suppress(\"NAME_SHADOWING\")") {
            """@file:Suppress(
                |    "NAME_SHADOWING",
                |    "NO_EXPLICIT_VISIBILITY_IN_API_MODE",
                |    "NO_EXPLICIT_RETURN_TYPE_IN_API_MODE",
                |)""".trimMargin()
        } else if (
            Regex(
                "^(?:public\\s+)?(?:(?:open|abstract|sealed|data|enum)\\s+)?" +
                    "(?:class|interface|object)\\b",
            ).containsMatchIn(line)
        ) {
            if (line.startsWith("public ")) {
                "internal " + line.removePrefix("public ")
            } else {
                "internal $line"
            }
        } else if (line.startsWith("public fun ") || line.startsWith("inline fun ")) {
            "internal " + line.removePrefix("public ")
        } else {
            line
                .replace(
                    Regex("^(\\s*@Throws\\([^)]*\\)\\s+)(?!override\\s+)fun\\s+"),
                    "$1internal fun ",
                )
                .replace(Regex("^(\\s*\\*/\\s+)fun\\s+"), "$1internal fun ")
        }
        rewritten
    }
}

val verifyUniFfiVisibility by tasks.registering {
    dependsOn(prepareUniFfiBinding)
    val prepared = preparedUniFfiDirectory.map { it.file("utexo_local_signer_prepared.kt") }
    inputs.file(prepared)
    doLast {
        val source = prepared.get().asFile.readText()
        val exposedType = Regex(
            "(?m)^(?:public\\s+)?(?:(?:open|abstract|sealed|data|enum)\\s+)?" +
                "(?:class|interface|object)\\b",
        ).find(source)
        val exposedFunction = Regex("(?m)^public fun\\s+").find(source)
        val exposedThrowingFunction = Regex(
            "(?m)^[ \\t]*@Throws\\([^)]*\\)[ \\t]+" +
                "(?!override[ \\t]+|internal[ \\t]+)fun[ \\t]+",
        ).find(source)
        val exposedCommentTailFunction = Regex(
            "(?m)^[ \\t]*\\*/[ \\t]+(?!internal[ \\t]+)fun[ \\t]+",
        ).find(source)
        val exposedInlineFunction = Regex("(?m)^inline[ \\t]+fun[ \\t]+").find(source)
        check(
            exposedType == null &&
                exposedFunction == null &&
                exposedThrowingFunction == null &&
                exposedCommentTailFunction == null &&
                exposedInlineFunction == null
        ) {
            "Generated UniFFI declarations must remain Kotlin-internal"
        }
    }
}

android {
    namespace = "com.utexo.rgb.sdk"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }


    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(stageNativeNotices, NativeNoticeAssetsTask::getOutputDirectory)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("com.utexo.rgb.sdk.WalletInfrastructureApi")
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
        // UniFFI emits public declarations without explicit-API modifiers and
        // must suppress those two diagnostics. Keep the exception scoped to
        // Kotlin's warning about generated-code error suppression itself.
        freeCompilerArgs.add("-Xwarning-level=ERROR_SUPPRESSION:disabled")
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(prepareUniFfiBinding)
    exclude("**/internal/ffi/utexo_local_signer.kt")
    source(preparedUniFfiDirectory)
}

tasks.named("check").configure {
    dependsOn(verifyUniFfiVisibility)
}
tasks.named("preBuild").configure { dependsOn(stageNativeNotices) }

val releaseClassesJar = layout.buildDirectory.file(
    "intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar",
)
val hardenReleaseJvmApi by tasks.registering(HardenReleaseJvmApiTask::class) {
    dependsOn("syncReleaseLibJars")
    classesJar.set(releaseClassesJar)
    outputs.upToDateWhen { false }
}

tasks.matching { it.name == "bundleReleaseAar" }.configureEach {
    dependsOn(hardenReleaseJvmApi)
}

val releaseCompileClasspath = objects.fileCollection()
configurations.configureEach {
    if (name == "releaseCompileClasspath") {
        releaseCompileClasspath.from(this)
    }
}
val verifyReleaseJvmApi by tasks.registering(VerifyReleaseJvmApiTask::class) {
    dependsOn("bundleReleaseAar")
    aar.set(layout.buildDirectory.file("outputs/aar/sdk-release.aar"))
    compilerClasspath.from(releaseCompileClasspath)
    rootKotlinSources.from(
        fileTree("src/main/kotlin/com/utexo/rgb/sdk") {
            include("*.kt")
        },
    )
}

tasks.named("check").configure {
    dependsOn(verifyReleaseJvmApi)
}

val verifyReleaseApiBaseline by tasks.registering(PublicApiBaselineTask::class) {
    dependsOn("bundleReleaseAar")
    mustRunAfter("updateReleaseApiBaseline")
    aar.set(layout.buildDirectory.file("outputs/aar/sdk-release.aar"))
    baseline.set(layout.projectDirectory.file("api/release-api.txt"))
    actual.set(layout.buildDirectory.file("api/release-api.txt"))
    update.set(false)
    outputs.upToDateWhen { false }
}
tasks.register<PublicApiBaselineTask>("updateReleaseApiBaseline") {
    dependsOn("bundleReleaseAar")
    aar.set(layout.buildDirectory.file("outputs/aar/sdk-release.aar"))
    baseline.set(layout.projectDirectory.file("api/release-api.txt"))
    actual.set(layout.buildDirectory.file("api/release-api-update.txt"))
    update.set(true)
    outputs.upToDateWhen { false }
}
tasks.named("check").configure { dependsOn(verifyReleaseApiBaseline) }

tasks.withType<Jar>().configureEach {
    if (name.contains("source", ignoreCase = true)) {
        // The generated implementation binding is intentionally not a consumer
        // source surface. Public wrappers remain fully represented in this jar.
        exclude("**/internal/ffi/**")
        exclude("**/utexo_local_signer_prepared.kt")
    }
}

tasks.register("verifyConnectedDebugAndroidTests") {
    dependsOn("connectedDebugAndroidTest")
    inputs.dir(layout.buildDirectory.dir("outputs/androidTest-results/connected/debug"))
    doLast {
        val total = inputs.files.files
            .asSequence()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .sumOf { file ->
                Regex("<testsuite[^>]*\\btests=\"([0-9]+)\"")
                    .find(file.readText())
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: 0
            }
        check(total > 0) { "Connected Android verification executed zero tests" }
    }
}

tasks.register("verifyReleaseNativeAbis") {
    dependsOn("assembleRelease")
    inputs.file(layout.buildDirectory.file("outputs/aar/sdk-release.aar"))
    doLast {
        val archive = inputs.files.singleFile
        val expected = setOf(
            "jni/arm64-v8a/libutexo_local_signer.so",
            "jni/armeabi-v7a/libutexo_local_signer.so",
            "jni/x86_64/libutexo_local_signer.so",
        )
        val actual = ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith("/libutexo_local_signer.so") }
                .toSet()
        }
        check(actual == expected) {
            "Release AAR signer ABIs differ: expected=$expected actual=$actual"
        }
    }
}

publishing {
    repositories {
        // Private, disposable validation output only; never Maven Local or a remote registry.
        maven {
            name = "ValidationLocal"
            url = rootProject.layout.buildDirectory.dir("validation-maven").get().asFile.toURI()
        }
    }
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "rgb-sdk-kotlin-light"
            version = project.version.toString()
            artifact(nativeEvidenceZip)
            artifact(nativeEvidenceDirectory.file("utexo-local-signer-source.tar.gz")) {
                classifier = "native-source"
                extension = "tar.gz"
                builtBy(verifyNativeDistributionInputs)
            }
            artifact(nativeEvidenceDirectory.file("native-source.cdx.json")) {
                classifier = "native-sbom"
                extension = "json"
                builtBy(verifyNativeDistributionInputs)
            }
            pom {
                name.set("UTEXO RGB SDK for Kotlin")
                description.set("Lightweight Android facade with local Bitcoin signing and validation")
                url.set("https://github.com/UTEXO-Protocol/rgb-sdk-kotlin-light")
                developers {
                    developer {
                        organization.set("UTEXO Protocol")
                        organizationUrl.set("https://utexo.io")
                    }
                }
                scm {
                    url.set("https://github.com/UTEXO-Protocol/rgb-sdk-kotlin-light")
                    connection.set("scm:git:https://github.com/UTEXO-Protocol/rgb-sdk-kotlin-light.git")
                }
            }
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

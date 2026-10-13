plugins {
    id("com.android.application")
}

val producedReleaseAar = rootProject.layout.projectDirectory.file(
    "sdk/build/outputs/aar/sdk-release.aar",
)
val stagedReleaseAar = layout.buildDirectory.file("packaged-sdk/sdk-release.aar")
val stagePackagedReleaseAar by tasks.registering(Copy::class) {
    dependsOn(":sdk:bundleReleaseAar")
    from(producedReleaseAar)
    into(stagedReleaseAar.map { it.asFile.parentFile })
}
val packagedReleaseAarFiles = files(stagedReleaseAar).builtBy(stagePackagedReleaseAar)

android {
    namespace = "com.utexo.rgb.sdk.releasesmoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.utexo.rgb.sdk.releasesmoke"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            testProguardFiles("test-proguard-rules.pro")
            // The fixture is never distributed. Signing makes the exact minified
            // artifact built in CI installable for the connected-device smoke test.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testBuildType = "release"
    // Public, unfunded, captured PSBT fixtures only; never production wallet data.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("sdk/src/androidTest/assets"))

    buildFeatures {
        buildConfig = false
        resValues = false
    }
}

dependencies {
    // Deliberately consume the packaged artifact. A project dependency would let
    // this fixture accidentally compile against sources or non-release classes.
    implementation(packagedReleaseAarFiles)

    // A flat-file AAR has no POM, so its declared runtime dependencies must be
    // supplied exactly as a Maven consumer would receive them transitively.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// Keep the explicit lifecycle edge in addition to the file collection's
// builtBy metadata so every Android variant starts from the immutable staged
// artifact, even when Gradle resolves transforms early.
tasks.named("preBuild").configure {
    dependsOn(stagePackagedReleaseAar)
}

tasks.register("verifyPackagedReleaseSmoke") {
    dependsOn("connectedReleaseAndroidTest")
    val results = layout.buildDirectory.dir("outputs/androidTest-results/connected/release")
    inputs.dir(results)
    doLast {
        val total = results.get().asFileTree
            .matching { include("**/TEST-*.xml") }
            .files
            .sumOf { file ->
                Regex("<testsuite[^>]*\\btests=\"([0-9]+)\"")
                    .find(file.readText())
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: 0
            }
        check(total > 0) { "Packaged release verification executed zero tests" }
    }
}

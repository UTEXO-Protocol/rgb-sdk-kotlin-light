plugins {
    id("com.android.application")
}

android {
    namespace = "com.utexo.rgb.sdk.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.utexo.rgb.sdk.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Not a shipping app: a readable, debuggable reference integration.
        release { isMinifyEnabled = false }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Source dependency on purpose: this sample is meant to be read next to the
    // SDK and to break loudly when the SDK's public API changes.
    implementation(project(":sdk"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

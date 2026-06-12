// SPIKE-001 module. Throwaway by design (docs/03-roadmap.md Phase 0): no Compose,
// no architecture - just sherpa-onnx + Kokoro int8 and a measurement readout.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "page.stephens.sotto.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "page.stephens.sotto.spike"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "spike-001b"
        ndk { abiFilters += "arm64-v8a" }   // ADR-005: release perf claims are arm64-only
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The Kokoro model assets are large; don't let AAPT try to compress them.
    androidResources { noCompress += listOf("onnx", "bin", "txt", "fst", "dict") }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
}

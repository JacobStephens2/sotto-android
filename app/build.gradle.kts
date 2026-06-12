// SPIKE-001 module. Throwaway by design (docs/03-roadmap.md Phase 0): no Compose,
// no architecture - just sherpa-onnx + Kokoro int8 and a measurement readout.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "page.stephens.sotto"
    compileSdk = 35

    defaultConfig {
        applicationId = "page.stephens.sotto"   // the forever id (concept brief); the spike APKs had .spike and coexist
        minSdk = 29
        targetSdk = 34   // spike-only: avoids SDK-35 edge-to-edge insets work
        versionCode = 6
        versionName = "0.2.0-slice"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }  // x86_64 for emulator tests only
        }
        release {
            ndk { abiFilters += "arm64-v8a" }   // ADR-005: release perf claims are arm64-only
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // The Kokoro model assets are large; don't let AAPT try to compress them.
    androidResources { noCompress += listOf("onnx", "bin", "txt", "fst", "dict") }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

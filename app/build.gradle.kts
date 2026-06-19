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

    // A/B experiment toggle (-Pfp32): the full-precision model as a SEPARATE
    // coexisting app, so int8 and fp32 can be compared by ear on one phone.
    // Off by default, so CI and releases stay int8 and untouched.
    val fp32 = project.hasProperty("fp32")
    compileSdk = 35

    defaultConfig {
        applicationId = "page.stephens.sotto"   // the forever id (concept brief); the spike APKs had .spike and coexist
        minSdk = 29
        targetSdk = 34   // spike-only: avoids SDK-35 edge-to-edge insets work
        versionCode = 16
        versionName = "0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MODEL_ASSET",
            if (fp32) "\"models/model.fp32.onnx\"" else "\"models/model.int8.onnx\"")
        if (fp32) {
            applicationIdSuffix = ".fp32"
            versionNameSuffix = "-fp32"
        }
    }

    // ADR-007: one codebase, two artifacts. `local` is the flagship no-network
    // claim; `sync` (Phase 5) adds opt-in server-library sync and INTERNET only.
    flavorDimensions += "distribution"
    productFlavors {
        create("local") { dimension = "distribution"; isDefault = true }
        create("sync") { dimension = "distribution" }
    }

    // Release signing comes from the environment (CI secrets / local keystore);
    // absent env -> unsigned release, which only the dry-run cares about.
    val ksPath = System.getenv("SOTTO_KEYSTORE")
    if (ksPath != null) signingConfigs.create("release") {
        storeFile = file(ksPath)
        storePassword = System.getenv("SOTTO_KEYSTORE_PASS")
        keyAlias = System.getenv("SOTTO_KEY_ALIAS") ?: "sotto"
        keyPassword = System.getenv("SOTTO_KEY_PASS") ?: System.getenv("SOTTO_KEYSTORE_PASS")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }  // x86_64 for emulator tests only
        }
        release {
            ndk { abiFilters += "arm64-v8a" }   // ADR-005: release perf claims are arm64-only
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets.getByName("main").assets.srcDir(if (fp32) "src/precision/fp32" else "src/precision/int8")

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "sotto-${flavorName}-${versionName}-${buildType.name}.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // The Kokoro model assets are large; don't let AAPT try to compress them.
    androidResources { noCompress += listOf("onnx", "bin", "txt", "fst", "dict") }

    // Roborazzi screenshot tests run on the JVM (Robolectric), so they need the
    // merged Android resources (fonts, colors) and record mode on. No emulator,
    // no GPU - which is the point on a headless box.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Record goldens locally (default); CI runs with -Proborazzi.verify=true
            // -Proborazzi.record=false to compare against the committed set.
            all {
                it.systemProperty("roborazzi.test.record",
                    (project.findProperty("roborazzi.record") ?: "true").toString())
                it.systemProperty("roborazzi.test.verify",
                    (project.findProperty("roborazzi.verify") ?: "false").toString())
            }
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    add("syncImplementation", "androidx.security:security-crypto:1.1.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Roborazzi: render real Composables (SottoTheme, the bundled fonts) to PNG
    // on the JVM via Robolectric - no emulator/GPU. Library-only (no Gradle
    // plugin), driven by the roborazzi.test.record system property above.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.32.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Read secrets from local.properties (gitignored). Falls back to env for CI.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: ""

android {
    namespace = "com.moviebox.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moviebox.tv"
        // 26+: java.util.Base64 (used by the request signer) and broad
        // ExoPlayer codec support; covers virtually all Android TV devices.
        minSdk = 26
        targetSdk = 35
        versionCode = 86
        versionName = "0.1.85"

        // Ship only the ABIs real devices use. The universal APK carried four
        // (arm64-v8a, armeabi-v7a, x86, x86_64) = ~40 MB, and that size was
        // truncating mid-download from GitHub's asset CDN → corrupt APK →
        // "problem parsing the package". x86/x86_64 are emulator-only here; the
        // TV is armeabi-v7a and phones are arm64-v8a. Dropping x86* trims ~13 MB
        // (~40 MB → ~27 MB) so the download completes in one shot.
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        buildConfigField(
            "String", "TMDB_TOKEN",
            "\"" + secret("TMDB_TOKEN") + "\"",
        )
    }

    // Release signing — keystore path + passwords live in local.properties
    // (gitignored), which the hexhoxhex push procedure also relies on.
    // CI can override via env vars of the same names.
    signingConfigs {
        create("release") {
            val storePath = secret("HEXHOXHEX_KEYSTORE")
            if (storePath.isNotBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = secret("HEXHOXHEX_STORE_PASS")
                keyAlias = secret("HEXHOXHEX_KEY_ALIAS")
                keyPassword = secret("HEXHOXHEX_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off until we audit Moshi / Room / NanoHTTPD keep
            // rules; APK is ~24 MB unminified, fine for sideload.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    // ffmpeg-based audio renderer extension. Substitutes the platform AAC
    // decoder for an ffmpeg one that outputs at a sample rate the device
    // can actually open — fixes the FOX USA / FOXNY USA 24 kHz audio
    // crash that previously forced WebView fallback.
    implementation(libs.nextlib.media3ext)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
}

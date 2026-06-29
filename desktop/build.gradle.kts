import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Kotlin JVM version is supplied by the root project's classpath
    // (Android plugin brings Kotlin in); no per-module `version` here.
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

repositories {
    // dependencyResolutionManagement in settings.gradle.kts is FAIL_ON_PROJECT_REPOS,
    // so no per-module repos block needed — inherits google + mavenCentral + jitpack.
}

dependencies {
    // Compose Multiplatform desktop. compose.desktop.currentOs picks the
    // right native artifact (windows-x86_64, macos-arm64, macos-x86_64, etc.)
    // for the build host. Production packaging uses the same logic via the
    // compose.desktop.application{} block below.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Shared with Android — pure-JVM libraries the existing :app already uses.
    // Network: OkHttp for direct calls + Moshi for JSON parsing.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    // org.json is bundled into Android by default; on plain JVM we
    // need to pull it explicitly so JSONObject is available to the
    // hand-written parser in H5Api.kt.
    implementation("org.json:json:20240303")

    // Coroutines for async network calls.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // VLCJ for video playback. Bundled libvlc binaries are downloaded
    // separately by the user's VLC install for now; later we'll bundle
    // libvlc into the packaged installer.
    implementation(libs.vlcj)
}

compose.desktop {
    application {
        mainClass = "com.moviebox.desktop.MainKt"

        nativeDistributions {
            // Produce installers for Mac (.dmg) and Windows (.msi).
            // Linux .deb is automatic if anyone needs it later.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Vijana BaruBaru"
            // MAJOR must be > 0 per packager — start at 1.0.0.
            packageVersion = "1.0.0"
            description = "Vijana BaruBaru — desktop edition. Streams aoneroom catalog + dlhd live TV."
            vendor = "hexhoxhex"
            // App icon: stub for now; replace with .icns / .ico later.
        }
    }
}

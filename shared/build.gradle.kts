// Pure Kotlin JVM module — works on both Android (which runs JVM
// bytecode) and Desktop. No Android plugin, no JetBrains Compose plugin.
// Only the bits that are platform-agnostic live here:
//   - signing crypto (HMAC-MD5)
//   - shared constants
//   - H5 API client (logging + storage are abstracted via interfaces
//     so each app supplies its own implementation)
plugins {
    kotlin("jvm")
}

dependencies {
    // OkHttp + JSON parsing — same versions :app and :desktop use.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    // org.json — bundled into Android by default but not pure JVM, so
    // the consumer that needs it pulls it explicitly. :app gets it for
    // free; :desktop already declares it. Shared module avoids
    // depending on org.json directly to keep the dep surface minimal.
}

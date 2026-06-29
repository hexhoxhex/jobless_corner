package com.moviebox.tv.net

/**
 * Shim — the actual signing implementation lives in `:shared/net/Crypto.kt`
 * so a fix to signing applies to BOTH :app (Android) and :desktop (JVM)
 * at once. This typealias keeps every existing `import com.moviebox.tv.net.Crypto`
 * compiling without changes; calls like `Crypto.clientToken(ts)` and
 * `Crypto.trSignature(...)` resolve through to the shared object.
 *
 * If you find yourself wanting to edit this file: don't. Edit
 * `:shared/src/main/kotlin/com/moviebox/shared/net/Crypto.kt` instead.
 */
typealias Crypto = com.moviebox.shared.net.Crypto

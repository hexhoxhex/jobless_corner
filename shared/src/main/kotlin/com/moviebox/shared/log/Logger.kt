package com.moviebox.shared.log

/**
 * Minimal logger interface so the shared code can emit diagnostic lines
 * without knowing whether it's running on Android (`android.util.Log`)
 * or Desktop (stdout). The active impl is installed once at startup by
 * each app via [Log.install].
 */
interface Logger {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
}

/** Default impl: print to stdout/stderr. Used by Desktop and as a
 *  pre-init fallback before the Android app's onCreate runs. */
class PrintlnLogger : Logger {
    override fun i(tag: String, message: String) {
        println("[$tag] $message")
    }
    override fun w(tag: String, message: String, throwable: Throwable?) {
        System.err.println("[W/$tag] $message" + (throwable?.let { " (${it.message})" } ?: ""))
    }
}

/** Global singleton. Each app installs its own impl on startup. */
object Log {
    @Volatile private var delegate: Logger = PrintlnLogger()

    fun install(logger: Logger) { delegate = logger }

    fun i(tag: String, message: String) = delegate.i(tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        delegate.w(tag, message, throwable)
}

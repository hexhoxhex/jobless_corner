package com.moviebox.tv

import android.app.Application
import android.content.Intent
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlin.system.exitProcess

/**
 * Tuned Coil image loader so posters are cached (memory + disk) and decoded
 * lazily — avoids re-downloading every cover on each scroll, which was the
 * main source of lag.
 */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        // Debug / telemetry plumbing — resets in-memory session log, leaves
        // persisted daily rollups intact. NetworkMonitor starts listening
        // for connectivity changes immediately.
        com.moviebox.tv.debug.Telemetry.init(this)
        com.moviebox.tv.debug.NetworkMonitor.start(this)
        // Mint the premium atp:3 session token at startup (off the first-frame
        // path) so the FIRST search/detail already carries the bearer that
        // unlocks full results — instead of running degraded ("nothing found"
        // / "doesn't exist") until a play happens to warm it. See
        // [H5PlayResolver.warmSession].
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { com.moviebox.tv.net.H5PlayResolver.warmSession() },
            800,
        )
        // Pick up the published provider config (which sources are live, and
        // in what order). Reads the cache instantly and refreshes in the
        // background, so a source that dies upstream can be disabled for
        // existing installs without an app update.
        Thread {
            kotlinx.coroutines.runBlocking {
                runCatching { com.moviebox.tv.data.ProviderConfig.warm(this@App) }
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        /** App-context handle for static helpers that need a Context but live
         *  outside the activity/VM (e.g. the headless WebView play resolver). */
        @Volatile lateinit var instance: App
    }

    /** Show a friendly crash screen instead of the system "app stopped". */
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e("VijanaBaruBaru", "Uncaught exception", throwable)
            runCatching {
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK,
                        )
                        .putExtra(
                            CrashActivity.EXTRA_ERROR,
                            throwable.message ?: throwable.toString(),
                        )
                )
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        }
    }
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .allowRgb565(true) // half the memory per poster where alpha isn't needed
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
}

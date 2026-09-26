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
        // The remote's access list, initialised HERE rather than from the
        // Compose tree.
        //
        // MainActivity.onCreate starts the remote server eagerly, but
        // RemoteAccess.init() was called from a LaunchedEffect in AppRoot —
        // i.e. after the first composition. Every remote request goes through
        // RemoteAccess to authorise, so for the ~30 s between the server
        // accepting connections and Compose getting there, every single
        // request answered "lateinit property prefs has not been
        // initialized". Open the phone remote just after the TV app starts
        // and it looked dead. init() is idempotent, so the AppRoot call
        // stays and simply finds it done.
        runCatching { com.moviebox.tv.remote.RemoteAccess.init(this) }
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
        // Settle the live wrapper host, on a thread of its own. It is one
        // redirect-following GET, but against a host that can take tens of
        // seconds to answer — and everything below runs in sequence, so
        // doing it there would have delayed arming the match reminders by
        // that long on every boot. Alarms are the one thing that must not
        // wait.
        Thread {
            runCatching { com.moviebox.tv.data.live.LiveResolver.refreshWrapperBase() }
        }.apply { isDaemon = true }.start()

        Thread {
            kotlinx.coroutines.runBlocking {
                runCatching { com.moviebox.tv.data.ProviderConfig.warm(this@App) }
                // Arm match reminders without waiting for anyone to open the
                // Live tab. The VM only fetches the schedule when Live is
                // visited, so a TV that boots to the home screen would
                // otherwise never schedule a single alarm — which is exactly
                // the case a reminder is for. No-ops when nothing is
                // followed, so it costs an idle install nothing.
                runCatching { com.moviebox.tv.reminders.ReminderWarm.run(this@App) }
                // And keep them armed when nobody opens the app again:
                // a reboot wipes alarms, and tomorrow's fixtures are not
                // in today's schedule. See ScheduleSyncWorker.
                runCatching {
                    com.moviebox.tv.reminders.ScheduleSyncWorker.schedule(this@App)
                }
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
    /**
     * Release what we can when the TV is short of memory.
     *
     * Android asks every app to trim before it starts killing them; we were
     * ignoring it and holding the poster cache regardless. On this device
     * that matters — it runs at ~96% memory used with heavy swapping, and
     * swap-in stalls are what make playback hitch.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching {
                coil.Coil.imageLoader(this).memoryCache?.clear()
                Log.i("App", "onTrimMemory($level) — dropped the image cache")
            }
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
                    // 0.15, not 0.25. Measured on the TV: 1.8 GB total with
                    // ~65 MB free and 515 MB already pushed to swap, and the
                    // CPU 283% idle. On a box in that state the cheap trade is
                    // memory for a little re-decoding — holding a quarter of
                    // our heap in posters costs the whole device more than it
                    // saves us.
                    .maxSizePercent(0.15)
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

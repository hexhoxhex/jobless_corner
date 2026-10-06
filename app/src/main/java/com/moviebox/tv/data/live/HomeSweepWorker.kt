package com.moviebox.tv.data.live

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moviebox.tv.remote.RemoteController
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Checks channels from the TV's own network while nobody is watching, and
 * writes the answers to [HomeHealthLedger].
 *
 * CI can no longer do this: the wrapper source throttles datacentre IPs to the
 * point that the refresh resolved 0 of 899 channels on every run (2026-10-06),
 * leaving 126 channels with no verdict and hidden from every user. From a home
 * connection the same channels resolve normally.
 *
 * It is deliberately timid, because the home IP is also the one the viewer
 * watches on, and that source punishes volume: after sustained fetching it
 * stops accepting TCP from the address for minutes at a time, which from the
 * sofa looks exactly like "the stream just stops" (measured 2026-09-26).
 *
 *  - **Never while something is playing.** Checked before every probe; if a
 *    viewer starts watching mid-run, the run ends there.
 *  - **One channel at a time**, with a pause between them. These pages are
 *    trickled out at a shared ~13 KB/s, so parallel probes only starve each
 *    other.
 *  - **Bounded**: a handful of channels per run, well inside WorkManager's
 *    ten-minute limit, hourly.
 *  - **Uses the playback path itself** ([LiveStreamProxy.verify] = resolve +
 *    parse the master), so "ok" means exactly "this TV could start it".
 *  - **Refusal is not a verdict.** A run starts by resolving a canary — a
 *    channel known to work — to prove the source is answering us at all. A
 *    failure only becomes "down" once a canary (or any success) after it
 *    shows the source was still answering; if the canaries stop resolving,
 *    the unvouched failures are discarded and the run ends.
 *
 * Order: channels with no home verdict first — the unclassified ones before
 * the rest — then whatever was checked longest ago.
 */
class HomeSweepWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (RemoteController.isPlaying) {
            Log.i(TAG, "skipped — something is playing")
            return Result.success()
        }
        val channels = runCatching { LiveTvRepository().channels(includeAll = true) }
            .getOrNull()
        if (channels.isNullOrEmpty()) return Result.retry()

        val queue = channels.sortedWith(
            compareBy<Channel>(
                { HomeHealthLedger.lastCheckedAt(ctx, it.id) != 0L },
                { it.status == "ok" },
                { HomeHealthLedger.lastCheckedAt(ctx, it.id) },
            ),
        ).toMutableList()

        // Canaries: channels expected to work, used to prove the source is
        // answering US before any failure is believed. Without them the
        // ordering above is a trap — the unclassified channels go first, they
        // may simply not exist on the source, and "everything failed" from a
        // batch of them looks identical to "the source is refusing us". The
        // run would stop, record nothing, and start on the same channels next
        // hour, forever. Measured on the first run: four unclassified
        // channels in a row failed, two of them in under a second.
        //
        // Prefer channels this TV has itself seen work, most recent first;
        // then whatever the catalogue calls ok (which may be stale, hence
        // trying a few).
        val canaries = channels
            .filter { HomeHealthLedger.statusOf(ctx, it.id) == "ok" }
            .sortedByDescending { HomeHealthLedger.lastCheckedAt(ctx, it.id) }
            .plus(channels.filter { it.status == "ok" }.shuffled())
            .distinctBy { it.id }
            .toMutableList()

        val proxy = LiveStreamProxy(LiveResolver())
        val started = SystemClock.elapsedRealtime()
        var probes = 0
        var ok = 0
        var down = 0
        var discarded = 0
        val pending = mutableListOf<String>()   // failures not yet vouched for
        var stopReason = "batch complete"

        suspend fun probe(ch: Channel): Boolean {
            probes++
            val master = runCatching { proxy.verify(ch.id) }.getOrNull()
            if (master != null) {
                ok++
                // Written straight away, so a run stopped early keeps them.
                HomeHealthLedger.record(ctx, ch.id, ok = true, host = hostOf(master))
            }
            delay(SPACING_MS)
            return master != null
        }

        fun timeUp() = probes >= MAX_PER_RUN ||
            SystemClock.elapsedRealtime() - started > RUN_BUDGET_MS

        /** Is the source serving us right now? A working channel says so. */
        suspend fun sourceAnswers(): Boolean {
            repeat(CANARY_TRIES) {
                if (timeUp() || RemoteController.isPlaying) return false
                val c = canaries.removeFirstOrNull() ?: return false
                queue.removeAll { it.id == c.id }
                if (probe(c)) return true
            }
            return false
        }

        /** Failures since the last good canary become verdicts only if the
         *  source is still answering now; otherwise they say nothing. */
        suspend fun settlePending(): Boolean {
            if (pending.isEmpty()) return true
            val vouched = sourceAnswers()
            if (vouched) {
                pending.forEach {
                    HomeHealthLedger.record(ctx, it, ok = false, reason = "resolve-failed")
                }
                down += pending.size
            } else {
                discarded += pending.size
            }
            pending.clear()
            return vouched
        }

        if (!sourceAnswers()) {
            stopReason = "no canary resolved — the source is not serving us; nothing recorded"
        } else {
            while (queue.isNotEmpty()) {
                if (timeUp()) { stopReason = "budget"; break }
                if (RemoteController.isPlaying) {
                    stopReason = "viewer started watching"; break
                }
                val ch = queue.removeAt(0)
                if (probe(ch)) {
                    // A success proves the source answered, so everything that
                    // failed before it in this stretch was a real failure.
                    pending.forEach {
                        HomeHealthLedger.record(ctx, it, ok = false, reason = "resolve-failed")
                    }
                    down += pending.size
                    pending.clear()
                } else {
                    pending += ch.id
                    if (pending.size >= RECHECK_AFTER_FAILS && !settlePending()) {
                        stopReason = "source stopped answering mid-run"
                        break
                    }
                }
            }
            if (pending.isNotEmpty()) settlePending()
        }

        Log.i(
            TAG,
            "home sweep: $probes probes, ok=$ok down=$down discarded=$discarded in " +
                "${(SystemClock.elapsedRealtime() - started) / 1000}s — $stopReason; " +
                "ledger now ${HomeHealthLedger.size(ctx)} channels",
        )
        return Result.success()
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()

    companion object {
        private const val TAG = "HomeSweep"
        private const val PERIODIC = "home-sweep"
        private const val NOW = "home-sweep-now"

        /** Channels per run. ~600 a day at the hourly cadence, so the whole
         *  catalogue is covered in about a day and a half, and the 126 with
         *  no verdict — which go first — within the first several hours. */
        private const val MAX_PER_RUN = 25

        /** Under WorkManager's ten-minute ceiling, with room for the one
         *  probe still in flight when the budget runs out. */
        private const val RUN_BUDGET_MS = 8 * 60_000L

        private const val SPACING_MS = 15_000L

        /** Canaries tried before concluding the source is not serving us.
         *  The catalogue's own "ok" can be stale, so one miss proves little. */
        private const val CANARY_TRIES = 3

        /** Consecutive failures before re-checking with a canary. */
        private const val RECHECK_AFTER_FAILS = 5

        private val NETWORK = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Idempotent: safe on every app start. KEEP, so a restart does not
         *  push the next run out; the initial delay keeps it clear of the
         *  first minutes after launch, when someone is likely starting to
         *  watch. */
        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<HomeSweepWorker>(1, TimeUnit.HOURS)
                        .setConstraints(NETWORK)
                        .setInitialDelay(10, TimeUnit.MINUTES)
                        .build(),
                )
            }
        }

        /** One run now — POST /api/live/health/sweep. */
        fun runNow(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    NOW,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<HomeSweepWorker>()
                        .setConstraints(NETWORK)
                        .build(),
                )
            }
        }
    }
}

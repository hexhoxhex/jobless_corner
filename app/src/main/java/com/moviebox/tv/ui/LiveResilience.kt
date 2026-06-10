package com.moviebox.tv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Adaptive resilience for live HLS playback. Watches the player's buffering
 * transitions and takes progressively heavier action when the stream
 * struggles:
 *
 *  - **Bitrate cap** after 3 stalls inside [WINDOW_MS]: tells the
 *    DefaultTrackSelector to prefer the lowest variant. ABR-aware
 *    streams (most dlhd / phantemlis catalog channels expose 1080p
 *    variants) drop to 360–480p so the chunk-fetch budget shrinks.
 *  - **Re-prepare** after [HEAVY_STALL_MS] of continuous buffering:
 *    calls `player.prepare()`, which refetches the manifest and
 *    restarts the load. Cheaper than a full seek and survives
 *    transient upstream stalls.
 *
 * Each action is announced via [announce] so the UI can show a small
 * "Stabilising…" hint instead of staying mute while the buffer rebuilds.
 *
 * Lifetime is one-per-VideoPlayer composable; recreated on isLive change.
 */
class LiveResilience {

    private var lastStallStartedAt: Long = 0L
    private val stallTimestamps = ArrayDeque<Long>()
    private var bitrateCapped = false
    private val main = Handler(Looper.getMainLooper())
    private var rePrepareCheck: Runnable? = null
    private var announcement: String? = null
    private var clearAnnouncementAt: Long = 0L

    fun onBufferingChanged(
        buffering: Boolean,
        player: Player,
        trackSelector: DefaultTrackSelector,
        announce: (String?) -> Unit,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (buffering) {
            lastStallStartedAt = now
            stallTimestamps.addLast(now)
            // Drop events older than the sliding window.
            while (stallTimestamps.isNotEmpty() &&
                now - stallTimestamps.first() > WINDOW_MS
            ) stallTimestamps.removeFirst()

            // Schedule the heavy-stall check exactly once per buffer event.
            rePrepareCheck?.let { main.removeCallbacks(it) }
            val deadlineStart = lastStallStartedAt
            rePrepareCheck = Runnable {
                // If we're still on the same stall after the deadline, the
                // load isn't recovering — refresh the manifest.
                if (lastStallStartedAt == deadlineStart &&
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    announce("Reconnecting…")
                    scheduleAnnouncementClear(announce)
                    runCatching { player.prepare() }
                }
            }.also { main.postDelayed(it, HEAVY_STALL_MS) }

            // Bitrate cap once we cross the stall-count threshold.
            if (!bitrateCapped && stallTimestamps.size >= STALL_THRESHOLD) {
                bitrateCapped = true
                runCatching {
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        // Cap to a forgiving ceiling rather than forcing the
                        // absolute lowest — keeps the picture watchable while
                        // halving the per-chunk byte budget.
                        .setMaxVideoBitrate(CAPPED_BITRATE_BPS)
                        .setMaxVideoSize(1280, 720)
                        .build()
                }
                announce("Lowering quality for smoother playback…")
                scheduleAnnouncementClear(announce)
            }
        } else {
            // Recovered. Cancel the pending re-prepare.
            rePrepareCheck?.let { main.removeCallbacks(it) }
            rePrepareCheck = null
            lastStallStartedAt = 0L
        }
    }

    /** Auto-clear the visible pill 4 seconds after the latest action so it
     *  doesn't sit there indefinitely once the stream stabilises. */
    private fun scheduleAnnouncementClear(announce: (String?) -> Unit) {
        clearAnnouncementAt = SystemClock.elapsedRealtime() + 4_000L
        main.postDelayed({
            if (SystemClock.elapsedRealtime() >= clearAnnouncementAt) {
                announce(null)
            }
        }, 4_000L)
    }

    companion object {
        /** Sliding-window length we count stalls inside. */
        private const val WINDOW_MS: Long = 30_000

        /** Stalls in [WINDOW_MS] before we cap the bitrate. */
        private const val STALL_THRESHOLD: Int = 3

        /** How long a single stall has to last before we re-prepare. */
        private const val HEAVY_STALL_MS: Long = 15_000

        /** Bitrate ceiling used after [STALL_THRESHOLD] stalls. */
        private const val CAPPED_BITRATE_BPS: Int = 2_500_000
    }
}

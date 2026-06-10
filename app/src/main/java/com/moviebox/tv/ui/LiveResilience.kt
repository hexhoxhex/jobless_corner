package com.moviebox.tv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Live playback health observer. **Does not interrupt playback** — the user
 * complaint about "reconnecting a lot" was the previous version calling
 * `player.prepare()` every 15 s of stall, which resets the buffer and starts
 * a fresh manifest fetch.
 *
 * The catalog's dlhd/donis streams are almost always **single-variant**
 * (one 720p60 H.264 rendition, no ABR ladder). So:
 *
 *  - **Bitrate caps do nothing** — there's no lower rendition to drop to.
 *  - **prepare() doesn't fix anything** — the same URL re-fetched in the
 *    same second yields the same chunk-fetch behaviour. All it does is
 *    introduce a visible 2–4 s rebuffer the user reads as "the app is
 *    fighting me" instead of "the network needs a beat to recover".
 *
 * What we keep:
 *
 *  - Single-stall **soft hint** ("Reconnecting…" deferred 30 s, only fires
 *    if the player is still buffering at that point AND the user has been
 *    on the same stall the entire time). The buffer config + OkHttp
 *    HTTP/2 stack should let ExoPlayer recover on its own well before
 *    that point in most cases.
 *  - `player.prepare()` ONLY as the last resort, after [HEAVY_STALL_MS]
 *    of CONTINUOUS buffering. Was 15 s, now 45 s — gives the LoadControl
 *    cushion (30 s minBuffer / 60 s maxBuffer) every chance to absorb
 *    transient upstream hiccups before we throw the buffer away.
 *
 * Lifetime is one-per-VideoPlayer composable; recreated on isLive change.
 */
class LiveResilience {

    private var lastStallStartedAt: Long = 0L
    private val main = Handler(Looper.getMainLooper())
    private var rePrepareCheck: Runnable? = null
    private var softHint: Runnable? = null
    private var clearAnnouncementAt: Long = 0L

    fun onBufferingChanged(
        buffering: Boolean,
        player: Player,
        @Suppress("UNUSED_PARAMETER") trackSelector: DefaultTrackSelector,
        announce: (String?) -> Unit,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (buffering) {
            lastStallStartedAt = now

            softHint?.let { main.removeCallbacks(it) }
            val deadlineStart = lastStallStartedAt
            softHint = Runnable {
                if (lastStallStartedAt == deadlineStart &&
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    announce("Reconnecting…")
                    scheduleAnnouncementClear(announce)
                }
            }.also { main.postDelayed(it, SOFT_HINT_MS) }

            rePrepareCheck?.let { main.removeCallbacks(it) }
            rePrepareCheck = Runnable {
                if (lastStallStartedAt == deadlineStart &&
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    runCatching { player.prepare() }
                }
            }.also { main.postDelayed(it, HEAVY_STALL_MS) }
        } else {
            // Recovered. Cancel pending hint + prepare.
            softHint?.let { main.removeCallbacks(it) }
            rePrepareCheck?.let { main.removeCallbacks(it) }
            softHint = null
            rePrepareCheck = null
            lastStallStartedAt = 0L
            announce(null)
        }
    }

    private fun scheduleAnnouncementClear(announce: (String?) -> Unit) {
        clearAnnouncementAt = SystemClock.elapsedRealtime() + 4_000L
        main.postDelayed({
            if (SystemClock.elapsedRealtime() >= clearAnnouncementAt) {
                announce(null)
            }
        }, 4_000L)
    }

    companion object {
        /** How long ExoPlayer has to be still buffering before the user sees
         *  the soft "Reconnecting…" hint. Until then the buffer drains
         *  silently — that's what the buffer is FOR. */
        private const val SOFT_HINT_MS: Long = 30_000

        /** How long a single stall has to last before we tear down and
         *  prepare() afresh. Last-resort only — see class doc. */
        private const val HEAVY_STALL_MS: Long = 45_000
    }
}

package com.moviebox.tv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Detects the **silent freeze** case: the player is supposed to be playing
 * (`playWhenReady == true`) but `currentPosition` has stopped advancing
 * and no error / STATE_ENDED has fired. Real example we caught on a
 * Comedy Central session — codec QIB dropped from 60 fps to a flat 10 fps,
 * then to zero, while ExoPlayer stayed in STATE_READY (or STATE_BUFFERING
 * with no transition out). None of the existing recovery paths
 * (`onPlayerError`, `STATE_ENDED`, the proxy stall detector) trigger,
 * because no error fires and the upstream playlist's `MEDIA-SEQUENCE` is
 * still inching forward.
 *
 * The watchdog ticks every [TICK_MS] ms. Each tick:
 *
 *  - Reads `player.currentPosition`.
 *  - If it has advanced since the last tick, reset the freeze counter.
 *  - If it has NOT advanced AND `player.playWhenReady` is `true` AND
 *    state is BUFFERING or READY, and we've been frozen for more than
 *    [FREEZE_THRESHOLD_MS], fire [onFreeze] and start the cooldown so
 *    we don't re-fire on the same stall.
 *
 * [onFreeze] is expected to do the full recovery cycle (invalidate the
 * proxy cache + `exo.seekTo(C.TIME_UNSET) + exo.prepare()`), matching the
 * onPlayerError path in PlayerScreen.
 *
 * Lifetime: one instance per live VideoPlayer composable. Call [start]
 * when the player is ready; [stop] when the composable disposes or the
 * stream switches to VOD.
 */
class LiveFreezeWatchdog {

    private val main = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var lastPosition: Long = 0L
    private var lastAdvanceAt: Long = 0L
    private var lastFiredAt: Long = 0L

    fun start(player: Player, onFreeze: () -> Unit) {
        stop()
        lastPosition = player.currentPosition.coerceAtLeast(0L)
        lastAdvanceAt = SystemClock.elapsedRealtime()
        lastFiredAt = 0L

        val r = object : Runnable {
            override fun run() {
                check(player, onFreeze)
                main.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        main.postDelayed(r, TICK_MS)
    }

    fun stop() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    private fun check(player: Player, onFreeze: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val pos = player.currentPosition.coerceAtLeast(0L)

        // 1. Position is advancing → reset.
        if (pos > lastPosition) {
            lastPosition = pos
            lastAdvanceAt = now
            return
        }

        // 2. Only consider "frozen" if the user has asked for playback
        //    AND the state is one where we expect frames to flow.
        val state = player.playbackState
        val active = player.playWhenReady &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        if (!active) {
            // Genuine pause or terminal state — reset so we don't count
            // the paused interval against the next active session.
            lastAdvanceAt = now
            return
        }

        // 3. Cooldown — only one recovery per [COOLDOWN_MS] window so we
        //    don't flap if the recovery itself takes a second or two.
        if (now - lastFiredAt < COOLDOWN_MS) return

        // 4. Frozen long enough → fire.
        val frozenFor = now - lastAdvanceAt
        if (frozenFor >= FREEZE_THRESHOLD_MS) {
            lastFiredAt = now
            // Re-anchor so the next tick starts a fresh countdown.
            lastAdvanceAt = now
            onFreeze()
        }
    }

    companion object {
        /** Polling interval. 2 s is fine-grained enough to react quickly
         *  without burning CPU on a TV that's just playing video. */
        private const val TICK_MS: Long = 2_000

        /** How long the player has to be "active but not advancing"
         *  before we declare a freeze. 20 s is longer than LiveResilience's
         *  soft hint (30 s) — wait, no — 20 s is **shorter** than the
         *  30 s soft hint, so we'll fire before the user sees the hint
         *  for a genuine silent-freeze. The deep 45 s minBuffer means
         *  ExoPlayer should normally have ridden through any 20 s
         *  upstream blip without going to BUFFERING at all; if position
         *  truly hasn't advanced for 20 s the upstream/decoder has lost
         *  the plot and we need to re-prepare. */
        private const val FREEZE_THRESHOLD_MS: Long = 20_000

        /** Don't re-fire within this window. The recovery itself
         *  (invalidate + seek + prepare + buffer + decode first frame)
         *  takes ~6 s on a healthy network; double that for safety. */
        private const val COOLDOWN_MS: Long = 30_000
    }
}

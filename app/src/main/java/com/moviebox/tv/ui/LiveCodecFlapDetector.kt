package com.moviebox.tv.ui

import android.os.SystemClock

/**
 * Tracks how often the video decoder is being re-created on the current
 * live stream. Some streams + the TV's hardware decoder (Realtek on the
 * G10, others on cheaper Android-TV SoCs) interact pathologically — the
 * hardware reads every minor segment-boundary or format-hint variation as
 * a full "format changed" event and tears the codec down + rebuilds it.
 * In real captures we've seen this happen ~once per second; the buffer
 * never refills, the watchdog fires, recovery loops endlessly.
 *
 * No reconnect path can fix that, because the content + hardware are the
 * fundamental mismatch. The cure is to ask ExoPlayer to use its
 * software-decoded extension renderer (FFmpeg / libgav1) instead. Then
 * the codec lifecycle is owned by libavcodec, not the SoC driver, and
 * the flap stops.
 *
 * This class is the trigger. PlayerScreen feeds `onDecoderInitialized`
 * for every decoder-creation event from `AnalyticsListener`. If we see
 * [THRESHOLD_INITS] within [WINDOW_MS], [isFlapping] starts returning
 * true and PlayerScreen flips a per-channel preference that drives a
 * one-shot rebuild of `exo` with the FFmpeg-preferred renderer factory.
 */
class LiveCodecFlapDetector {

    /** Timestamps (ms, SystemClock.elapsedRealtime) of recent decoder
     *  initializations. We keep at most [THRESHOLD_INITS + 1] entries —
     *  enough to know when we've crossed the threshold within the
     *  window. */
    private val recent = ArrayDeque<Long>(THRESHOLD_INITS + 1)

    fun onDecoderInitialized() {
        val now = SystemClock.elapsedRealtime()
        // Drop entries that fell out of the window.
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) {
            recent.removeFirst()
        }
        recent.addLast(now)
        // Cap size — we don't care about anything beyond the threshold;
        // keeping a longer history would just delay GC.
        while (recent.size > THRESHOLD_INITS + 1) {
            recent.removeFirst()
        }
    }

    fun isFlapping(): Boolean = recent.size >= THRESHOLD_INITS

    fun reset() {
        recent.clear()
    }

    companion object {
        /** Number of decoder-init events that flips us into "flapping" if
         *  they all land within [WINDOW_MS]. Legitimate playback creates
         *  the decoder ONCE at start + occasionally on DISCONTINUITY tags
         *  (maybe 1-2 per ad break). 8 in 12 s is well outside any
         *  healthy pattern. */
        private const val THRESHOLD_INITS = 8

        /** Rolling window for the flap count. Long enough to catch a
         *  ~1Hz tear-down storm; short enough that a single old hiccup
         *  doesn't poison the count for the rest of the session. */
        private const val WINDOW_MS: Long = 12_000
    }
}

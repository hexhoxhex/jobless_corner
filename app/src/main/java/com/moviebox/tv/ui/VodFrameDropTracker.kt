package com.moviebox.tv.ui

/**
 * Sliding-window dropped-frame accumulator for VOD playback.
 *
 * ExoPlayer's `AnalyticsListener.onDroppedVideoFrames` is called periodically
 * with batches of dropped frames + the elapsed wall-clock since the last
 * report. We sum those into a 8-second window; if drops in that window
 * exceed [DROPPED_RATIO_THRESHOLD] of the wall-clock budget at a nominal
 * 60 fps, the variant is too heavy for this device's decoder.
 *
 * The threshold is intentionally lenient (~12%) — sustained 12% drops are
 * already a visible slow-motion symptom (audio stays on its own clock,
 * video renderer falls behind one frame at a time and the user sees a
 * smoothness collapse). Stricter (e.g. 5%) would trigger on every cold-start
 * decoder warm-up; more lenient (25%) would keep the user watching broken
 * playback for longer than necessary.
 *
 * Once [observe] has fired true, it returns false until [reset] is called —
 * stops us from down-throttling repeatedly inside the same window or after
 * a successful tryDowngrade has already nudged the quality. PlayerScreen
 * calls [reset] from a LaunchedEffect(contentKey, mediaUrl) so a manual
 * quality switch or a new movie gives the new variant a fresh shot.
 */
class VodFrameDropTracker {

    private var windowDrops: Int = 0
    private var windowElapsedMs: Long = 0
    private var triggered: Boolean = false

    /**
     * Feed a single dropped-frames report. Returns `true` exactly once when
     * the accumulated window crosses the threshold — the caller should
     * then attempt a quality downgrade. Subsequent calls return false
     * until [reset].
     */
    fun observe(droppedFrames: Int, elapsedMs: Long): Boolean {
        if (triggered) return false
        windowDrops += droppedFrames
        windowElapsedMs += elapsedMs

        // Slide the window: if we've collected more than WINDOW_MS of data
        // without crossing the threshold, drop the oldest evidence by
        // halving both counters. Coarse but enough for this signal — the
        // alternative would be a per-report ring buffer.
        if (windowElapsedMs > WINDOW_MS * 2) {
            windowDrops /= 2
            windowElapsedMs /= 2
        }

        if (windowElapsedMs >= WINDOW_MS) {
            val budgetedFrames = (windowElapsedMs * NOMINAL_FPS / 1000).toInt()
            val ratio = if (budgetedFrames > 0)
                windowDrops.toFloat() / budgetedFrames else 0f
            if (ratio >= DROPPED_RATIO_THRESHOLD) {
                triggered = true
                return true
            }
        }
        return false
    }

    /** Forget all history. Called when the content or chosen variant changes. */
    fun reset() {
        windowDrops = 0
        windowElapsedMs = 0
        triggered = false
    }

    companion object {
        /** Sliding window length we evaluate the drop ratio over. */
        private const val WINDOW_MS: Long = 8_000

        /** Nominal frame rate we assume video targets. 60 fps is a safe
         *  ceiling — most content is 24/30/60; using 60 makes the ratio
         *  threshold conservative (we count against the highest plausible
         *  frame budget, so the trigger is harder to hit on 30 fps content). */
        private const val NOMINAL_FPS: Int = 60

        /** Sustained drop ratio above which we conclude the variant is too
         *  heavy for the decoder. 0.12 = 12% — visible slow-motion symptom
         *  without being so strict that the cold-start dropouts trigger us. */
        private const val DROPPED_RATIO_THRESHOLD: Float = 0.12f
    }
}

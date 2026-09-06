package com.moviebox.tv.data

import android.content.Context

/**
 * Runtime switches for the live playback path, flippable from the phone
 * remote so a suspected cause can be A/B tested on the same channel in one
 * build instead of two.
 *
 * Added while chasing "audio plays, screen stays black" on live: the panel
 * is a 60 Hz-locked Realtek, tunneling is on for every live channel, and
 * tunneled output bypasses ExoPlayer's render callbacks — so the usual
 * frame counters read zero whether or not anything is on screen. Guessing
 * between "too heavy a stream" and "tunneling" without a switch means a
 * rebuild per guess.
 */
object LiveTuning {

    private const val PREFS = "live_tuning"
    private const val K_NO_TUNNELING = "force_no_tunneling"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    /**
     * Force tunneling off for every live channel, overriding the fps
     * heuristic. Null-safe before [init].
     *
     * **Defaults to true.** Measured A/B on BBC One (2 min each, identical
     * conditions, rendered-frame counter): tunneling ON produced
     * **0/51 samples with video and audio that never started**; tunneling
     * OFF produced a first video frame at 36 s and real rendered frames.
     * Tunneling was originally enabled to dodge a SurfaceFlinger
     * compositing throttle, but on this panel it now yields no picture at
     * all, and the no-tunneling path already routes to TextureView which
     * was the other fix for that same throttle. A black screen is strictly
     * worse than choppy compositing.
     */
    @Volatile var forceNoTunneling: Boolean = true
        private set

    fun init(context: Context) {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        forceNoTunneling = p.getBoolean(K_NO_TUNNELING, true)
    }

    fun setForceNoTunneling(on: Boolean) {
        forceNoTunneling = on
        prefs?.edit()?.putBoolean(K_NO_TUNNELING, on)?.apply()
    }
}

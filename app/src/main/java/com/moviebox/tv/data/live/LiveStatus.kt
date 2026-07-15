package com.moviebox.tv.data.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-component status pipe for live-TV playback.
 *
 * Each stage of the "tap → picture" chain (resolver race, master fetch,
 * inner playlist, buffering, retry, auto-failover, final give-up) drops a
 * short human-readable note here. [PlayerScreen]'s loader overlay observes
 * [message] and shows it below the channel title, so users see "Fetching
 * streams…", "Reconnecting… (2/6)", "All streams offline — try another
 * channel", etc. instead of a mute spinner.
 *
 * A single global [MutableStateFlow] is fine because only one channel is
 * ever playing at a time; concurrent players would collide but the app
 * doesn't spawn those. Notes are cleared explicitly via [clear] on
 * success (first-frame decoded) — otherwise the last message would
 * linger over a playing video.
 */
object LiveStatus {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Post a status line. Call from any thread. Empty/null clears the
     *  overlay. Prefix conventions:
     *    - ▶  positive progress ("Resolving streams…")
     *    - ↻  retry ("Reconnecting…")
     *    - ⚠  transient trouble ("Stream blocked — trying alternate…")
     *    - ✗  fatal ("Stream unavailable")
     *  The overlay treats them all the same visually; the prefix is
     *  cosmetic. */
    fun note(msg: String?) {
        _message.value = msg
    }

    /** Convenience — clears the message. Fires on first-frame render so
     *  the overlay disappears completely when video actually starts. */
    fun clear() {
        _message.value = null
    }
}

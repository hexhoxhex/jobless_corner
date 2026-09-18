package com.moviebox.tv.data.live

import java.util.concurrent.ConcurrentHashMap

/**
 * Channel ids that are per-event feeds (the site mints them for a single
 * fixture) rather than fixed 24/7 channels.
 *
 * The distinction matters for which player routes are trustworthy. Measured
 * 2026-09-13: on 24/7 channel 1023 the /hub/ route returned the matching
 * Canal+ feed, but on event feed 8042 (Manchester United vs Manchester City)
 * /hub/ returned `tntsports1-uk` — a working stream of a DIFFERENT channel,
 * labelled as the match. Showing a viewer the wrong game under their game's
 * name is worse than failing. The /stream/ route carried the real fixture key.
 *
 * Event feeds are exactly the ids the catalog doesn't carry (it has none at
 * or above 5000), so they are registered when a schedule reference has to be
 * turned into a playable channel.
 */
object LiveEventFeeds {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun mark(channelId: String) {
        ids.add(channelId)
    }

    fun isEvent(channelId: String): Boolean = channelId in ids
}

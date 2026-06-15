package com.moviebox.tv.data.live

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One TV channel from the mkurugenzi_viewer catalog. The JSON has more
 * diagnostic fields than we use — Moshi just ignores the extras.
 */
@JsonClass(generateAdapter = true)
data class Channel(
    val id: String,
    val name: String,
    @Json(name = "stream_url") val streamUrl: String?,
    val status: String?,
    val logo: String?,
    val group: String?,
    @Json(name = "tvg_id") val tvgId: String?,
    /** Alternative iframe-style backends. Same shape as tester.html's
     *  `playViaIframe`: URL is `https://dlhd.pk/{path}/stream-{id}.php`. */
    val players: List<LivePlayer>? = null,
) {
    val isPlayable: Boolean get() = status == "ok" && !streamUrl.isNullOrBlank()
    /** Display name with title-case for the list UI. */
    val displayName: String get() = name.split(' ')
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    /** Available iframe player paths in catalog order. Empty = no fallback. */
    val playerPaths: List<String> get() =
        players?.filter { it.available }?.map { it.path } ?: emptyList()
}

@JsonClass(generateAdapter = true)
data class LivePlayer(
    val name: String,
    val path: String,
    @Json(name = "target_host") val targetHost: String?,
    val available: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class ScheduleEvent(
    val category: String,
    /** "HH:MM" 24-hour string as published by dlhd.pk. This is in **UK time**
     *  — dlhd.pk publishes its schedule from a UK perspective. We keep the
     *  raw string for fallback display, but always prefer [startUnix] when
     *  it is set. Without TZ conversion a Kenyan viewer (UTC+3) would see
     *  "17:00" for a show that's actually airing at 19:00 their time, which
     *  is the schedule-page confusion the user reported. */
    val time: String,
    val title: String,
    val channels: List<ScheduleChannelRef>,
    /** Authoritative event start time as a POSIX timestamp (seconds since
     *  epoch). Set by the scraper's `annotate_dates()` step. Use this to
     *  format the time in the **viewer's** local timezone instead of
     *  showing the raw UK-time [time] string. Null only on very old
     *  pre-annotation catalog dumps. */
    @Json(name = "start_unix") val startUnix: Long? = null,
    /** ISO date the event belongs to, e.g. "2026-06-15". Set by the
     *  scraper for events the catalog page assigned to that day. Useful
     *  for "tomorrow" disambiguation in the UI but not strictly required. */
    val date: String? = null,
)

@JsonClass(generateAdapter = true)
data class ScheduleChannelRef(
    val id: String,
    val name: String,
)

/** UI grouping: events bucketed by category, sorted by time inside each. */
data class ScheduleByCategory(
    val category: String,
    val events: List<ScheduleEvent>,
)

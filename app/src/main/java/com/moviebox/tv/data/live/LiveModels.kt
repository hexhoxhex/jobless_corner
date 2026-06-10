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
) {
    val isPlayable: Boolean get() = status == "ok" && !streamUrl.isNullOrBlank()
    /** Display name with title-case for the list UI. */
    val displayName: String get() = name.split(' ')
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

@JsonClass(generateAdapter = true)
data class ScheduleEvent(
    val category: String,
    /** "HH:MM" 24-hour, as published by dlhd.pk (UTC-ish, may shift slightly). */
    val time: String,
    val title: String,
    val channels: List<ScheduleChannelRef>,
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

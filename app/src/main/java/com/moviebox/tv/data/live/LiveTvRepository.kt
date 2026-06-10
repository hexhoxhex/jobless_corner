package com.moviebox.tv.data.live

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pulls channels.json + schedule.json from the published mkurugenzi_viewer
 * repo on GitHub raw. No auth, ~1.2 MB combined, CORS open. Cached in-memory
 * with separate TTLs because the schedule changes hourly while the channel
 * catalog turns over only when streams expire (~weeks).
 *
 * Replace BASE if you fork the data repo.
 */
class LiveTvRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val channelsAdapter = moshi.adapter<List<Channel>>(
        Types.newParameterizedType(List::class.java, Channel::class.java)
    )
    private val scheduleAdapter = moshi.adapter<List<ScheduleEvent>>(
        Types.newParameterizedType(List::class.java, ScheduleEvent::class.java)
    )

    @Volatile private var cachedChannels: List<Channel>? = null
    @Volatile private var cachedChannelsAt: Long = 0L
    @Volatile private var cachedSchedule: List<ScheduleEvent>? = null
    @Volatile private var cachedScheduleAt: Long = 0L

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} fetching $url")
            r.body?.string() ?: error("empty body from $url")
        }
    }

    /** Live channels currently playable. Defaults to status='ok' only. */
    suspend fun channels(force: Boolean = false, includeAll: Boolean = false): List<Channel> {
        val now = System.currentTimeMillis()
        val cached = cachedChannels
        if (!force && cached != null && now - cachedChannelsAt < CHANNELS_TTL_MS) {
            return cached.filterPlayable(includeAll)
        }
        val body = fetch(CHANNELS_URL)
        val parsed = channelsAdapter.fromJson(body) ?: emptyList()
        cachedChannels = parsed
        cachedChannelsAt = now
        return parsed.filterPlayable(includeAll)
    }

    /** Today's scheduled events. */
    suspend fun schedule(force: Boolean = false): List<ScheduleEvent> {
        val now = System.currentTimeMillis()
        val cached = cachedSchedule
        if (!force && cached != null && now - cachedScheduleAt < SCHEDULE_TTL_MS) {
            return cached
        }
        val body = fetch(SCHEDULE_URL)
        val parsed = scheduleAdapter.fromJson(body) ?: emptyList()
        cachedSchedule = parsed
        cachedScheduleAt = now
        return parsed
    }

    /** Distinct group names, in catalog order. Useful for the filter chips. */
    fun groups(channels: List<Channel>): List<String> =
        channels.mapNotNull { it.group }.distinct()

    /** Events bucketed by category, time-sorted within each bucket. */
    fun scheduleByCategory(schedule: List<ScheduleEvent>): List<ScheduleByCategory> {
        val buckets = LinkedHashMap<String, MutableList<ScheduleEvent>>()
        for (e in schedule) buckets.getOrPut(e.category) { mutableListOf() }.add(e)
        return buckets.map { (cat, evts) ->
            ScheduleByCategory(cat, evts.sortedBy { it.time })
        }
    }

    private fun List<Channel>.filterPlayable(includeAll: Boolean): List<Channel> =
        if (includeAll) this else filter { it.isPlayable }

    companion object {
        private const val REPO = "hexhoxhex/mkurugenzi_viewer"
        private const val BRANCH = "main"
        private const val BASE =
            "https://raw.githubusercontent.com/$REPO/$BRANCH"
        const val CHANNELS_URL = "$BASE/data/channels.json"
        const val SCHEDULE_URL = "$BASE/data/schedule.json"

        private const val CHANNELS_TTL_MS = 30 * 60 * 1000L   // 30 min
        private const val SCHEDULE_TTL_MS = 5 * 60 * 1000L    //  5 min
    }
}

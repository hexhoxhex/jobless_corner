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
    private val healthAdapter = moshi.adapter(HealthSnapshot::class.java)

    @Volatile private var cachedChannels: List<Channel>? = null
    @Volatile private var cachedChannelsAt: Long = 0L
    @Volatile private var cachedSchedule: List<ScheduleEvent>? = null
    @Volatile private var cachedScheduleAt: Long = 0L
    @Volatile private var cachedHealth: Map<String, HealthEntry>? = null
    @Volatile private var cachedHealthAt: Long = 0L

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

    /**
     * Find OTHER channels currently broadcasting the same event as
     * [failingChannelId]. Used by [MainViewModel.autoFailoverLive] when
     * the current channel keeps giving 5xx from its upstream — we look
     * up which scheduled event contains it, then hand back the sibling
     * channels in the same event so the app can try them.
     *
     * "Currently broadcasting" here means: event has a [ScheduleEvent
     * .startUnix] within the last 3 hours OR within the next 30 minutes.
     * Rough — schedule entries don't carry durations — but generous
     * enough to catch mid-match failures and pre-match warm-ups.
     *
     * @param failingChannelId  the channel that's failing
     * @param nowUnixSec       current epoch seconds (caller passes so
     *                         tests can inject a fixed time)
     * @return other Channel objects on the same event, prioritising
     *         those with `status=ok`. Empty list means no schedule
     *         match — caller shouldn't auto-failover.
     */
    fun alternatesForEventOn(
        failingChannelId: String,
        channels: List<Channel>,
        schedule: List<ScheduleEvent>,
        nowUnixSec: Long,
    ): List<Channel> {
        val event = schedule.firstOrNull { ev ->
            val includesFailing = ev.channels.any { it.id == failingChannelId }
            if (!includesFailing) return@firstOrNull false
            val start = ev.startUnix ?: return@firstOrNull false
            // ~3h window since start (mid-match) + 30 min pre-start warm-up.
            nowUnixSec in (start - 30 * 60)..(start + 3 * 60 * 60)
        } ?: return emptyList()

        val altIds = event.channels.mapNotNull { ref ->
            ref.id.takeIf { it != failingChannelId }
        }
        // Map ref → real Channel and prefer playable (status=ok) ones.
        return altIds.mapNotNull { id -> channels.firstOrNull { it.id == id } }
            .sortedByDescending { it.isPlayable }
    }

    private fun List<Channel>.filterPlayable(includeAll: Boolean): List<Channel> =
        if (includeAll) this else filter { it.isPlayable }

    /**
     * Latest deep-sweep health snapshot keyed by channel id. The sweep
     * (eyepapcorn_iptv `scripts/sweep_health.py`) runs every 15 min on
     * GitHub Actions and probes the full playback chain — including the
     * first segment byte — for every channel the scraper marked ok.
     *
     * **Advisory only**: this map is used by the UI to dim flagged
     * channels with a "Often offline" badge. It NEVER gates playback —
     * the user can still tap a "down" channel because the sweep runs
     * from a different network than the device and false positives are
     * cheap to overlook (the device retries on its own). Hiding based
     * on the sweep would risk blinding healthy channels for users
     * whose ISP differs from the GitHub Actions runner's egress.
     *
     * Returns empty map if health.json hasn't been published yet or
     * the fetch failed — caller treats absence as "no signal".
     */
    suspend fun health(force: Boolean = false): Map<String, HealthEntry> {
        val now = System.currentTimeMillis()
        val cached = cachedHealth
        if (!force && cached != null && now - cachedHealthAt < HEALTH_TTL_MS) {
            return cached
        }
        val map = runCatching {
            val body = fetch(HEALTH_URL)
            val snap = healthAdapter.fromJson(body) ?: return@runCatching emptyMap()
            // Sanity check: if EVERY swept channel is marked down, the
            // sweep run is broken (e.g. donis endpoint changed, runner
            // blocked) — not actually every CDN failing at once. Ignore
            // the snapshot entirely rather than dim every card in the
            // UI with a bogus "OFTEN OFFLINE" badge. Threshold is
            // "literally 100%" since even one or two ok channels is
            // evidence the sweep itself is working.
            val swept = snap.results.size
            val downCount = snap.results.count { it.status == "down" }
            if (swept > 0 && downCount == swept) {
                android.util.Log.w(
                    "LiveDiag",
                    "HEALTH ignoring snapshot — $downCount/$swept channels " +
                        "marked down. Sweep run is broken, not every CDN.",
                )
                emptyMap()
            } else {
                snap.results.associateBy { it.id }
            }
        }.getOrElse { emptyMap() }
        cachedHealth = map
        cachedHealthAt = now
        return map
    }

    companion object {
        private const val REPO = "hexhoxhex/mkurugenzi_viewer"
        private const val BRANCH = "main"
        private const val BASE =
            "https://raw.githubusercontent.com/$REPO/$BRANCH"
        const val CHANNELS_URL = "$BASE/data/channels.json"
        const val SCHEDULE_URL = "$BASE/data/schedule.json"
        const val HEALTH_URL = "$BASE/data/health.json"

        private const val CHANNELS_TTL_MS = 30 * 60 * 1000L   // 30 min
        private const val SCHEDULE_TTL_MS = 5 * 60 * 1000L    //  5 min
        private const val HEALTH_TTL_MS = 20 * 60 * 1000L     // 20 min
    }
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class HealthSnapshot(
    @com.squareup.moshi.Json(name = "swept_at") val sweptAt: Long = 0L,
    val results: List<HealthEntry> = emptyList(),
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class HealthEntry(
    val id: String,
    val name: String = "",
    /** "ok" or "down". The sweep treats "unreachable" as down. */
    val status: String,
    @com.squareup.moshi.Json(name = "fail_reason") val failReason: String? = null,
    @com.squareup.moshi.Json(name = "checked_at") val checkedAt: Long = 0L,
)

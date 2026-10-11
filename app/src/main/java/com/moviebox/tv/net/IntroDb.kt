package com.moviebox.tv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Where an episode's intro, recap and end credits are, from The Intro DB
 * (api.theintrodb.org) — a public, keyless database keyed by TMDB id. It is
 * what 7movies' player uses for its "Skip intro" button and to bring the next
 * episode forward at the credits.
 *
 * `GET /v3/media?tmdb_id=&duration_ms=[&season=&episode=]` answers
 * `{intro:[{start_ms,end_ms}], recap:[…], credits:[{start_ms,end_ms}]}`, or 404
 * when it has nothing for the title. The duration lets it return the times for
 * the cut being played.
 */
object IntroDb {

    data class Segment(val startMs: Long, val endMs: Long, val kind: String)

    /** [segments] are intros and recaps, earliest first; [creditsStartMs] is
     *  where the end credits begin, when known. */
    data class Times(val segments: List<Segment>, val creditsStartMs: Long?)

    private const val BASE = "https://api.theintrodb.org/v3/media"
    private const val TAG = "IntroDb"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Answers per title+episode+duration (to the second), including "nothing". */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Times>()
    private val NONE = Times(emptyList(), null)

    suspend fun lookup(
        tmdbId: Int, isTv: Boolean, season: Int, episode: Int, durationMs: Long,
    ): Times? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0 || durationMs <= 0) return@withContext null
        val key = "$tmdbId|$isTv|$season|$episode|${durationMs / 1000}"
        cache[key]?.let { return@withContext it.takeIf { t -> t !== NONE } }
        val url = buildString {
            append(BASE).append("?tmdb_id=").append(tmdbId)
            append("&duration_ms=").append(durationMs)
            if (isTv) append("&season=").append(season).append("&episode=").append(episode)
        }
        val times = runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                when {
                    r.code == 404 -> NONE
                    !r.isSuccessful -> null
                    else -> parse(JSONObject(r.body?.string().orEmpty()), durationMs)
                }
            }
        }.getOrNull() ?: return@withContext null
        cache[key] = times
        android.util.Log.i(
            TAG,
            "tmdb=$tmdbId s${season}e$episode: " +
                (if (times === NONE) "nothing" else
                    "${times.segments.size} segment(s), credits=${times.creditsStartMs}"),
        )
        times.takeIf { it !== NONE }
    }

    private fun parse(o: JSONObject, durationMs: Long): Times {
        fun spans(a: JSONArray?, kind: String) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val s = a?.optJSONObject(i) ?: return@mapNotNull null
            // A null start means "from the very beginning" (Rick and Morty
            // S1E3's intro is {start_ms: null, end_ms: 32468}).
            val start = if (s.isNull("start_ms")) 0L else s.optLong("start_ms", -1)
            val end = s.optLong("end_ms", -1)
            if (start < 0 || end <= start || end > durationMs) null
            else Segment(start, end, kind)
        }
        val segments = (spans(o.optJSONArray("intro"), "intro") +
            spans(o.optJSONArray("recap"), "recap")).sortedBy { it.startMs }
        // Credits start, kept only in the last 40% of the runtime: an earlier
        // mark is a bad row (vidrift's player applies the same rule).
        val credits = o.optJSONArray("credits")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("start_ms", -1) }
                .filter { it >= durationMs * 0.6 && it < durationMs }
                .minOrNull()
        }
        return Times(segments, credits)
    }
}

package com.moviebox.tv.data.live

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The live schedule read straight off the site's home page, as the
 * publisher (mkurugenzi_viewer `scraper.py fetch_schedule`) reads it.
 *
 * The published schedule.json is only as fresh as its last run, and GitHub
 * runs that "every 30 min" job every 4-9 hours. The site lists one UK day at
 * a time and fills it in through the morning, so the app went hours with
 * yesterday's events or a thin new day: at 22:13 UTC on 2026-11-17 the
 * published file was 2.5 h old and ran out about an hour later, and on the 17th a run
 * caught only the 3 events the site had listed so far. Reading the page here,
 * at most every [REFRESH_MS], keeps the schedule as current as the site.
 * One page (~550 KB, ~5 s on the TV's link), and the published file stays the
 * fallback when it can't be read.
 */
object SiteSchedule {
    private const val TAG = "LiveSchedule"
    private const val REFRESH_MS = 15 * 60_000L
    private const val FAILED_RETRY_MS = 5 * 60_000L
    private const val STALE_AFTER_S = 3 * 3600L

    private val CAT_RE = Regex(
        """<div class="schedule__catHeader"[^>]*>.*?<div class="card__meta">([^<]+)</div>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val EVENT_RE = Regex(
        """<div class="schedule__event">\s*<div class="schedule__eventHeader"[^>]*data-title="([^"]*)"[^>]*>""" +
            """.*?data-time="([^"]*)"[^>]*>([^<]+)</span>\s*<span class="schedule__eventTitle">([^<]+)</span>""" +
            """.*?<div class="schedule__channels">(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val CHAN_RE = Regex("""<a[^>]+href="/watch\.php\?id=(\d+)"[^>]*title="([^"]*)"""")
    private val DAY_RE = Regex("""<div class="schedule__dayTitle">([^<]+)</div>""")
    private val DAY_DATE_RE = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\s+(\d{4})""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(HostGate)
            .build()
    }

    @Volatile private var cached: List<ScheduleEvent>? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var failedAt = 0L

    /** The site's schedule, read at most every [REFRESH_MS]; null when it
     *  couldn't be read (the caller keeps the published one). */
    fun current(): List<ScheduleEvent>? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < REFRESH_MS) return it }
        if (now - failedAt < FAILED_RETRY_MS) return cached
        val t0 = now
        val events = runCatching { parse(fetchHome() ?: return@runCatching null) }.getOrNull()
        if (events.isNullOrEmpty()) {
            failedAt = now
            Log.w(TAG, "site schedule unreadable — keeping the published one")
            return cached
        }
        cached = events
        cachedAt = now
        Log.i(TAG, "site schedule: ${events.size} events in ${System.currentTimeMillis() - t0} ms")
        return events
    }

    private fun fetchHome(): String? {
        val req = Request.Builder().url(LiveResolver.wrapperBase() + "/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36")
            .build()
        return client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    /** The page's events, those more than [STALE_AFTER_S] old left out. */
    fun parse(text: String): List<ScheduleEvent> {
        val cats = CAT_RE.findAll(text).map { it.range.first to unescape(it.groupValues[1]).trim() }.toList()
        if (cats.isEmpty()) return emptyList()
        val days = DAY_RE.findAll(text).map { it.range.first to dayOf(unescape(it.groupValues[1])) }.toList()
        val nowS = System.currentTimeMillis() / 1000
        val out = ArrayList<ScheduleEvent>()
        for (m in EVENT_RE.findAll(text)) {
            val at = m.range.first
            val category = cats.lastOrNull { it.first < at }?.second ?: "Uncategorized"
            val (_, dataTime, timeText, title, channelsBlock) = m.destructured
            val time = dataTime.ifBlank { timeText }.trim()
            val day = days.lastOrNull { it.first < at }?.second
            val start = if (day != null) startOn(day, time) else startToday(time, nowS)
            if (start != null && start < nowS - STALE_AFTER_S) continue
            val seen = HashSet<String>()
            val channels = CHAN_RE.findAll(channelsBlock).mapNotNull { c ->
                val id = c.groupValues[1]
                if (!seen.add(id)) null else ScheduleChannelRef(id, unescape(c.groupValues[2]).trim())
            }.toList()
            out += ScheduleEvent(
                category = category,
                time = time,
                title = unescape(title).trim(),
                channels = channels,
                startUnix = start,
            )
        }
        return out
    }

    /** "Wednesday 18th Nov 2026 - Schedule Time UK GMT" -> (2026, 10, 18). */
    private fun dayOf(text: String): Triple<Int, Int, Int>? {
        val m = DAY_DATE_RE.find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].take(3).lowercase()).takeIf { it >= 0 } ?: return null
        return Triple(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
    }

    /** HH:MM on [day], in UTC: the page's headings say "Schedule Time UK GMT". */
    private fun startOn(day: Triple<Int, Int, Int>, time: String): Long? {
        val (hh, mm) = hhmm(time) ?: return null
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(day.first, day.second, day.third, hh, mm, 0)
        return c.timeInMillis / 1000
    }

    /** No day heading: today in UTC, or tomorrow if that is long past. */
    private fun startToday(time: String, nowS: Long): Long? {
        val (hh, mm) = hhmm(time) ?: return null
        var ts = nowS - nowS % 86400 + hh * 3600 + mm * 60
        if (ts < nowS - 12 * 3600) ts += 86400
        return ts
    }

    private fun hhmm(time: String): Pair<Int, Int>? {
        val parts = time.split(':')
        val hh = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val mm = parts.getOrNull(1)?.trim()?.take(2)?.toIntOrNull() ?: return null
        return if (hh in 0..23 && mm in 0..59) hh to mm else null
    }

    /** The page is double-encoded ("&amp;amp;"); undo it until it stops changing. */
    private fun unescape(s: String): String {
        var cur = s
        repeat(3) {
            val next = once(cur)
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    private fun once(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val end = if (c == '&') s.indexOf(';', i + 1) else -1
            if (end < 0 || end - i > 10) { sb.append(c); i++; continue }
            val name = s.substring(i + 1, end)
            val rep = when {
                name == "amp" -> "&"
                name == "lt" -> "<"
                name == "gt" -> ">"
                name == "quot" -> "\""
                name == "apos" -> "'"
                name.startsWith("#x") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (rep == null) { sb.append(c); i++ } else { sb.append(rep); i = end + 1 }
        }
        return sb.toString()
    }
}

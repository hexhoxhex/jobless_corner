package com.moviebox.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.tv.data.CaptionTrack
import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.net.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/*
 * Subtitles drawn by the app, not by ExoPlayer (2026-10-09).
 *
 * Viewers reported three problems, all confirmed: subtitles too early or
 * too late, subtitles from another episode or film, and the subtitle
 * setting changing by itself between episodes. Handing the files to
 * ExoPlayer could fix none of them:
 *  - its text-track choice is by LANGUAGE, so the source's English and an
 *    OpenSubtitles English could not both be offered, and whichever the
 *    player preferred won;
 *  - there is no timing offset for sideloaded subtitles;
 *  - its track preference silently carried into the next episode while the
 *    CC menu reset to "Off", so screen and menu disagreed.
 * Here the file for the chosen TRACK is fetched and parsed, and the cue for
 * the current position (plus the viewer's offset) is drawn over the video.
 * The same cues tell where the dialogue ends, which is where the closing
 * song starts: the next-episode countdown keys off that.
 */

data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/** Fetch, decode and parse subtitle files. Small LRU cache by URL. */
internal object SubtitleFiles {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val cache = object : LinkedHashMap<String, List<SubCue>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SubCue>>?) =
            size > 12
    }

    suspend fun load(url: String, lang: String): List<SubCue>? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[url] }?.let { return@withContext it }
        val bytes = runCatching {
            if (url.startsWith("file://") || url.startsWith("/")) {
                File(url.removePrefix("file://")).readBytes()
            } else {
                // Same headers the player sent for subtitles: some CDNs
                // refuse requests without the stream's Referer.
                val b = Request.Builder().url(url).header("User-Agent", UA)
                (Constants.mediaHeaders + StreamHeaders.current).forEach { (k, v) -> b.header(k, v) }
                http.newCall(b.get().build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
            }
        }.getOrNull() ?: return@withContext null
        val cues = parse(decode(bytes, lang))
        if (cues.isEmpty()) return@withContext null
        synchronized(cache) { cache[url] = cues }
        cues
    }

    /** UTF-8 when it is valid UTF-8 (or has a BOM); otherwise the usual
     *  legacy codepage for the language. ExoPlayer assumed UTF-8 and turned
     *  Arabic, Turkish and Cyrillic files from OpenSubtitles into garbage. */
    fun decode(b: ByteArray, lang: String): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { return strict.decode(ByteBuffer.wrap(b)).toString() }
        val cs = when (lang.take(2).lowercase()) {
            "ar", "fa", "ur" -> "windows-1256"
            "tr" -> "windows-1254"
            "ru", "uk", "bg", "sr", "mk", "be" -> "windows-1251"
            "el" -> "windows-1253"
            "he" -> "windows-1255"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs" -> "windows-1250"
            "vi" -> "windows-1258"
            "th" -> "TIS-620"
            "zh" -> "GB18030"
            "ko" -> "EUC-KR"
            "ja" -> "Shift_JIS"
            else -> "windows-1252"
        }
        return runCatching { String(b, Charset.forName(cs)) }
            .getOrElse { String(b, Charsets.ISO_8859_1) }
    }

    private val TIME = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val TAG = Regex("""<[^>]*>|\{\\[^}]*\}""")

    /** SRT and WebVTT: blocks separated by blank lines, a "-->" timing
     *  line, then the text. Tags (<i>, <font>, {\an8}) are dropped. */
    fun parse(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow < 0) { i++; continue }
            val a = TIME.find(line.substring(0, arrow))
            val b = TIME.find(line.substring(arrow + 3))
            i++
            if (a == null || b == null) continue
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (lines[i].contains("-->")) break
                // A file without blank lines between cues: the next cue's
                // number must not end up glued to this cue's text.
                if (lines[i].trim().all { it.isDigit() } &&
                    i + 1 < lines.size && lines[i + 1].contains("-->")
                ) break
                val t = lines[i].replace(TAG, "").replace("&nbsp;", " ")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("\\N", "\n").trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
                i++
            }
            val start = ms(a)
            val end = ms(b)
            if (sb.isNotEmpty() && end > start) out.add(SubCue(start, end, sb.toString()))
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun ms(m: MatchResult): Long {
        val (h, mi, s, f) = m.destructured
        val frac = f.padEnd(3, '0').take(3).toLong()
        return ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + frac
    }
}

/** Text to show at [posMs] (already offset-adjusted), or null. Joins
 *  overlapping cues (two speakers at once). */
internal fun List<SubCue>.textAt(posMs: Long): String? {
    if (isEmpty()) return null
    var lo = 0
    var hi = size - 1
    var last = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= posMs) { last = mid; lo = mid + 1 } else hi = mid - 1
    }
    if (last < 0) return null
    val parts = ArrayList<String>(2)
    var j = last
    while (j >= 0 && last - j < 6) {
        val c = this[j]
        if (c.startMs <= posMs && posMs < c.endMs) parts.add(0, c.text)
        j--
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** Last moment anyone speaks, as an estimate of where the closing song
 *  (and any "thank you for watching" card) starts. A trailing run of lyric
 *  cues counts as the song. Null when the file doesn't cover the episode
 *  or the remainder is implausible for credits. */
internal fun creditsStartFrom(cues: List<SubCue>, durationMs: Long): Long? {
    if (cues.isEmpty() || durationMs <= 0) return null
    val lyric = Regex("[♪♫♬]")
    var lastSpoken = cues.lastIndex
    while (lastSpoken >= 0 && lyric.containsMatchIn(cues[lastSpoken].text)) lastSpoken--
    val songFrom = if (lastSpoken < cues.lastIndex) cues[lastSpoken + 1].startMs else null
    val dialogueEnd = if (lastSpoken >= 0) cues[lastSpoken].endMs else return null
    val start = songFrom ?: (dialogueEnd + 2_000)
    val remaining = durationMs - start
    if (dialogueEnd < durationMs * 0.7) return null
    if (remaining !in CREDITS_MIN_MS..CREDITS_MAX_MS) return null
    return start
}

/** A subtitle file belongs to this video if it covers most of it and does
 *  not run past its end: another episode, or the same episode cut for a
 *  different frame rate (4% longer), fails one of the two. */
internal fun coversVideo(cues: List<SubCue>, durationMs: Long): Boolean {
    if (cues.isEmpty()) return false
    if (durationMs <= 0) return true
    val end = cues.maxOf { it.endMs }
    return end >= durationMs * 0.6 && end <= durationMs + 30_000
}

private const val CREDITS_MIN_MS = 25_000L
private const val CREDITS_MAX_MS = 8 * 60_000L

/**
 * The subtitle state of what is playing: which track is shown, its cues,
 * the timing offset, and where the credits start. One per app (owned by
 * the view model), so the TV's CC menu and the phone remote always agree.
 *
 * The language is remembered per show (every episode keeps it), and the
 * last choice is the default for a show not seen before. The offset is
 * remembered per FILE: measured on "The Early Spring", episode 1's file was
 * in sync and episode 2's was not, so a per-show offset is wrong for one.
 *
 * While a file is shown, [SpeechTap]'s audio is checked against it every
 * minute: a clear match at another offset is applied by itself (unless the
 * viewer set one by hand), and a file nothing lines up with — another
 * episode's subtitles under this one's name — is replaced by another file,
 * or hidden with a note when there is none.
 *
 * Thresholds from that series (z of the best offset against all others,
 * ±20 s): the right file scored 3.4 after 10 min heard, 4.5 after 20 and
 * 5.1 after 40; episode 1's file against episode 2's audio stayed at
 * 1.7-2.3 however long it listened.
 */
class SubtitleSession(context: Context, private val scope: CoroutineScope) {

    data class State(
        /** Id of the track chosen ([CaptionTrack.id]); null = off. */
        val selectedId: String? = null,
        /** Cues being drawn; empty while loading, off, or [mismatch]. */
        val cues: List<SubCue> = emptyList(),
        val offsetMs: Long = 0L,
        /** The offset was measured from the audio, not set by the viewer. */
        val autoSynced: Boolean = false,
        /** No file for this track matches the video (wrong episode / cut). */
        val mismatch: Boolean = false,
        /** The audio check confirmed this file belongs to the video. */
        val verified: Boolean = false,
        val loading: Boolean = false,
        /** Where the closing credits start in this episode, if known. */
        val creditsStartMs: Long? = null,
        /** True when [creditsStartMs] comes from the dialogue itself, so a
         *  "Play now" during the credits need not be learned. */
        val creditsFromSubs: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("subtitles", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var tracks: List<CaptionTrack> = emptyList()
    private var mediaKey: String? = null
    private var showKey = ""
    private var season = 0
    private var episode = 0
    private var durationMs = 0L
    private var autoChosen = false
    private var probeCues: List<SubCue>? = null
    private var loadJob: Job? = null
    private var probeJob: Job? = null
    private var syncJob: Job? = null

    /** The file being drawn and the track it belongs to. */
    private var shownTrack: CaptionTrack? = null
    private var shownUrl: String? = null
    private var shownCues: List<SubCue> = emptyList()
    /** Files already found not to match this video. */
    private val rejected = HashSet<String>()
    /** The viewer asked for a hidden track again: show it regardless. */
    private var forceShow = false

    /** The tracks of the item now playing, for the menus. */
    val available: List<CaptionTrack> get() = tracks

    /** A new item started (or the same one at another quality/dub: the
     *  caption list may differ, so everything is re-chosen). */
    fun bind(play: PlayInfo, show: String) {
        if (play.isLive) { clear(); return }
        val key = play.mediaUrl
        if (key == mediaKey) return
        mediaKey = key
        showKey = show
        season = play.season
        episode = play.episode
        tracks = play.captions
        durationMs = 0L
        probeCues = null
        rejected.clear()
        forceShow = false
        cancelJobs()
        SpeechTap.reset()
        shownTrack = null; shownUrl = null; shownCues = emptyList()
        _state.value = State()
        val wanted = prefs.getString("lang:$showKey", null) ?: prefs.getString("last", null)
        val track = if (wanted == null || wanted == OFF) null else pick(wanted)
        if (track != null) {
            autoChosen = true
            show(track, auto = true)
        }
        // Always: the source's own file is the trusted reading of where the
        // credits start, whatever is (or isn't) being shown.
        startProbe()
    }

    fun clear() {
        mediaKey = null
        tracks = emptyList()
        cancelJobs()
        shownTrack = null; shownUrl = null; shownCues = emptyList()
        _state.value = State()
    }

    /** Viewer picked a track (or null = Off), on the TV or the phone. [id]
     *  may be a bare language code from an older phone page. */
    fun select(id: String?) {
        val track = id?.takeIf { it.isNotBlank() }?.let { pick(it) }
        val value = track?.id ?: OFF
        prefs.edit().putString("lang:$showKey", value).putString("last", value).apply()
        autoChosen = false
        if (track == null) {
            loadJob?.cancel(); syncJob?.cancel()
            shownTrack = null; shownUrl = null; shownCues = emptyList()
            _state.value = _state.value.copy(
                selectedId = null, cues = emptyList(), loading = false,
                mismatch = false, autoSynced = false,
            )
            updateCredits()
        } else {
            // Picking a track that was hidden as not matching, again,
            // means "show it anyway".
            forceShow = _state.value.mismatch && track.id == _state.value.selectedId
            show(track, auto = false)
        }
    }

    /** Viewer's own timing. Stops the automatic check for this file. */
    fun setOffset(ms: Long) {
        val url = shownUrl ?: return
        val track = shownTrack ?: return
        val key = fileKey(track, url)
        prefs.edit().putLong("offset:$key", ms).putBoolean("manual:$key", true).apply()
        syncJob?.cancel()
        _state.value = _state.value.copy(offsetMs = ms, autoSynced = false)
        updateCredits()
    }

    /** The player learned the duration. Re-checks an automatic choice
     *  against it (a file for another episode is swapped for one that
     *  fits) and works out the credits. */
    fun onDuration(ms: Long) {
        if (ms <= 0 || ms == durationMs) return
        durationMs = ms
        val track = shownTrack
        if (track != null && shownCues.isNotEmpty() && !coversVideo(shownCues, ms)) {
            show(track, auto = autoChosen)
            return
        }
        updateCredits()
    }

    /** "Play now" pressed during the end of an episode with [remainingMs]
     *  left. Without subtitles to read the credits from, remember it: the
     *  next episodes of this show count down at the same point. */
    fun noteNextPressed(remainingMs: Long) {
        if (_state.value.creditsFromSubs) return
        if (remainingMs !in 15_000L..CREDITS_MAX_MS) return
        prefs.edit().putLong("credits:$showKey", remainingMs).apply()
    }

    private fun cancelJobs() {
        loadJob?.cancel(); probeJob?.cancel(); syncJob?.cancel()
    }

    private fun show(track: CaptionTrack, auto: Boolean) {
        loadJob?.cancel()
        syncJob?.cancel()
        _state.value = _state.value.copy(
            selectedId = track.id, loading = true, cues = emptyList(),
            mismatch = false, autoSynced = false, verified = false,
        )
        loadJob = scope.launch {
            // Candidate files, best first: this track and its alternates;
            // for an automatic choice also the other tracks of the language.
            val order = buildList {
                add(track)
                if (auto) addAll(tracks.filter { it.code == track.code && it.id != track.id })
            }
            var chosen: Triple<CaptionTrack, String, List<SubCue>>? = null
            var fallback: Triple<CaptionTrack, String, List<SubCue>>? = null
            loop@ for (t in order) {
                for (url in listOf(t.url) + t.alternates) {
                    if (url in rejected && !forceShow) continue
                    val cues = SubtitleFiles.load(url, t.code) ?: continue
                    if (fallback == null) fallback = Triple(t, url, cues)
                    if (coversVideo(cues, durationMs)) {
                        chosen = Triple(t, url, cues)
                        break@loop
                    }
                }
            }
            val picked = chosen ?: fallback
            if (picked == null) {
                // Every file was found not to match (or none loads).
                shownTrack = null; shownUrl = null; shownCues = emptyList()
                _state.value = _state.value.copy(
                    loading = false, cues = emptyList(), mismatch = rejected.isNotEmpty(),
                )
                updateCredits()
                return@launch
            }
            val (t, url, cues) = picked
            shownTrack = t; shownUrl = url; shownCues = cues
            val key = fileKey(t, url)
            val manual = prefs.getBoolean("manual:$key", false)
            _state.value = _state.value.copy(
                selectedId = t.id, cues = cues, loading = false,
                offsetMs = prefs.getLong("offset:$key", 0L),
                autoSynced = prefs.contains("offset:$key") && !manual,
            )
            updateCredits()
            if (!manual && !forceShow) startSync(t, url, cues)
        }
    }

    /**
     * Every minute, line the cues up against the audio heard so far.
     *  - the same best offset (within 0.3 s) twice in a row with
     *    z >= [Z_APPLY] becomes this file's offset;
     *  - after [MISMATCH_AFTER_MS] heard, a best z under [Z_MISMATCH] means
     *    the file is for another video: try the next one, or hide the
     *    subtitles with a note.
     */
    private fun startSync(track: CaptionTrack, url: String, cues: List<SubCue>) {
        syncJob?.cancel()
        syncJob = scope.launch {
            var previous: SyncResult? = null
            var settled = false
            while (true) {
                // Once confirmed, only an occasional look for drift.
                kotlinx.coroutines.delay(if (settled) 5 * SYNC_EVERY_MS else SYNC_EVERY_MS)
                val frames = SpeechTap.snapshot()
                val r = withContext(Dispatchers.Default) { estimateSync(cues, frames) } ?: continue
                android.util.Log.i(
                    "SubSync",
                    "${track.id} file=${url.substringBefore('?').takeLast(16)} " +
                        "best=${r.offsetMs}ms z=%.2f heard=%ds".format(r.z, r.heardMs / 1000),
                )
                val prev = previous
                previous = r
                // Not before [MIN_HEARD_TO_APPLY]: on "The Early Spring" the
                // first 5 minutes (opening song, piracy notice) gave a false
                // -15.9 s peak that only gave way to the true one after ~10.
                if (r.heardMs >= MIN_HEARD_TO_APPLY &&
                    r.z >= Z_APPLY && prev != null && prev.z >= Z_APPLY &&
                    kotlin.math.abs(prev.offsetMs - r.offsetMs) <= 300
                ) {
                    if (!settled || kotlin.math.abs(_state.value.offsetMs - r.offsetMs) > 250) {
                        prefs.edit().putLong("offset:${fileKey(track, url)}", r.offsetMs).apply()
                        _state.value = _state.value.copy(
                            offsetMs = r.offsetMs, autoSynced = true, verified = true,
                        )
                        updateCredits()
                    }
                    settled = true
                } else if (!settled && r.heardMs >= MISMATCH_AFTER_MS && r.z < Z_MISMATCH) {
                    android.util.Log.w("SubSync", "${track.id} does not match this video; dropping file")
                    rejected.add(url)
                    show(track, auto = true)
                    return@launch
                }
            }
        }
    }

    /** Read the source's own subtitle file quietly, only to find where the
     *  dialogue ends: its English first, else any. (OpenSubtitles files are
     *  not used for this until the audio check has confirmed them.) */
    private fun startProbe() {
        val t = tracks.firstOrNull { !it.external && it.code.startsWith("en") }
            ?: tracks.firstOrNull { !it.external }
        if (t == null) { updateCredits(); return }
        probeJob = scope.launch {
            probeCues = SubtitleFiles.load(t.url, t.code)
            updateCredits()
        }
    }

    private fun updateCredits() {
        val st = _state.value
        // Only subtitles known to belong to this video may say where its
        // story ends: the source's own, or a file the audio check has
        // confirmed. "The Early Spring" E2's English file (really E1's)
        // put the end of the dialogue at 41:04 of a 48:35 episode — the
        // countdown would have cut 7 minutes of story.
        val trusted = shownTrack?.external == false || st.verified
        // On the video's timeline: shown subtitles carry their offset
        // (later subtitles = later speech); the quiet probe (always the
        // source's own file) has none.
        val fromShown = if (st.cues.isNotEmpty() && trusted) {
            creditsStartFrom(st.cues, durationMs - st.offsetMs)?.plus(st.offsetMs)
        } else null
        val fromSubs = fromShown ?: probeCues?.let { creditsStartFrom(it, durationMs) }
        val learned = prefs.getLong("credits:$showKey", 0L)
            .takeIf { it > 0 && durationMs > 0 }?.let { durationMs - it }
        _state.value = st.copy(
            creditsStartMs = fromSubs ?: learned,
            creditsFromSubs = fromSubs != null,
        )
    }

    /** The track for an id, or for a bare language code the source's own
     *  track first. Ids of the other kind fall back to the same language. */
    private fun pick(id: String): CaptionTrack? {
        tracks.firstOrNull { it.id == id }?.let { return it }
        val code = id.substringBefore('~')
        return tracks.firstOrNull { it.code == code && !it.external }
            ?: tracks.firstOrNull { it.code == code }
    }

    /** Stable name of one subtitle file: OpenSubtitles file ids are stable;
     *  a source's caption URLs are signed per request, so those are named
     *  by show, episode and language instead. */
    private fun fileKey(t: CaptionTrack, url: String): String =
        if (t.external) "os:" + url.substringBefore('?').substringAfterLast('/')
        else "src:$showKey:S${season}E$episode:${t.code}"

    companion object {
        private const val OFF = "off"
        private const val SYNC_EVERY_MS = 60_000L
        private const val Z_APPLY = 3.2
        private const val Z_MISMATCH = 2.6
        private const val MISMATCH_AFTER_MS = 15 * 60_000L
        private const val MIN_HEARD_TO_APPLY = 8 * 60_000L

        /** Same show across episodes and subjectId rotations. */
        fun showKeyOf(title: String): String =
            title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

/** Subtitle text drawn over the video: large, white, outlined, on a soft
 *  dark box — readable at 3 m on any picture. */
@Composable
internal fun SubtitleText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .widthIn(max = 900.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        style = TextStyle(
            color = Color.White,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            shadow = Shadow(Color.Black, blurRadius = 4f),
        ),
    )
}

/** Offset choices for the Sync menu: positive = subtitles later. */
internal val SUB_OFFSETS: List<Long> = listOf(
    -5_000, -3_000, -2_000, -1_500, -1_000, -500, 0, 500, 1_000, 1_500, 2_000, 3_000, 5_000,
).map { it.toLong() }

internal fun offsetLabel(ms: Long): String = when {
    ms == 0L -> "In sync"
    ms < 0 -> "%.1f s earlier".format(java.util.Locale.US, -ms / 1000.0)
    else -> "%.1f s later".format(java.util.Locale.US, ms / 1000.0)
}

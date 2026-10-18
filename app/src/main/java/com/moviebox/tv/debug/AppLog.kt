package com.moviebox.tv.debug

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The app's own log, kept so a viewer can hand it over when something goes
 * wrong ("it's stuck on Loading from…", "the sound is off").
 *
 * The TV's system log is a small ring shared with everything else on the
 * box: on 2026-10-15 it held two minutes of this app's lines. So a thread
 * follows `logcat` for this process (an app may always read its own lines)
 * and keeps them in memory and in two rotating files, which also survive a
 * crash or a restart.
 *
 * Lines are cleaned as they arrive, before they are stored anywhere:
 * links are cut to their host (signed URLs carry tokens), and session
 * tokens, cookies, keys, IP addresses and e-mail addresses are blanked. Names
 * of what was played stay in — "Family Guy S12E5 came in Spanish" can't be
 * diagnosed without them — and the remote says so before anything is shared.
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val MAX_LINES = 6_000
    private const val FILE_LIMIT_BYTES = 1_000_000L
    private const val DIR = "logs"
    private const val CURRENT = "app.log"
    private const val PREVIOUS = "app.1.log"

    private val lines = ArrayDeque<String>()
    @Volatile private var started = false
    private lateinit var dir: File

    fun start(context: Context) {
        if (started) return
        started = true
        dir = File(context.filesDir, DIR).apply { mkdirs() }
        Thread({ follow() }, "AppLog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Follow this process's log until the process ends. logcat can be
     *  killed by the system; start it again after a pause. */
    private fun follow() {
        val pid = android.os.Process.myPid()
        // Time of the last line taken, so a restarted logcat picks up after
        // it instead of replaying the whole system buffer into the file.
        var lastTime: String? = null
        while (true) {
            var proc: Process? = null
            try {
                val cmd = mutableListOf("logcat", "-v", "time", "--pid=$pid")
                lastTime?.let { cmd += listOf("-T", it) }
                proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = File(dir, CURRENT)
                var writer = java.io.FileWriter(out, true).buffered()
                var size = out.length()
                var lastFlush = System.currentTimeMillis()
                proc.inputStream.bufferedReader().useLines { seq ->
                    for (raw in seq) {
                        if (raw.startsWith("--------- beginning of")) continue
                        if (raw.length > 18 && raw[2] == '-' && raw[5] == ' ') {
                            lastTime = raw.substring(0, 18)
                        }
                        val line = clean(raw)
                        synchronized(lines) {
                            lines.addLast(line)
                            while (lines.size > MAX_LINES) lines.removeFirst()
                        }
                        writer.write(line); writer.write("\n")
                        size += line.length + 1
                        if (size > FILE_LIMIT_BYTES) {
                            writer.close()
                            File(dir, PREVIOUS).delete()
                            out.renameTo(File(dir, PREVIOUS))
                            writer = java.io.FileWriter(File(dir, CURRENT), true).buffered()
                            size = 0
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 2_000) {
                            writer.flush(); lastFlush = now
                        }
                    }
                }
                runCatching { writer.close() }
            } catch (t: Throwable) {
                Log.w(TAG, "log reader stopped: ${t.javaClass.simpleName}")
            } finally {
                runCatching { proc?.destroy() }
            }
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { return }
        }
    }

    /** The most recent [n] lines kept in memory; with [problemsOnly], just
     *  warnings, errors and lines that name a failure. */
    fun recent(n: Int, problemsOnly: Boolean): List<String> {
        val all = synchronized(lines) { lines.toList() }
        val picked = if (problemsOnly) all.filter(::isProblem) else all
        return picked.takeLast(n.coerceIn(1, MAX_LINES))
    }

    private val PROBLEM_RE = Regex(
        """fail|error|exception|stall|freez|broken|timeout|timed out|""" +
            """not available|no source|giving up|dead|refus|rebuffer|""" +
            """re-prepare|recovery|discontinu|(?<![\d.:-])\b[45]\d\d\b(?![\d.])""",
        RegexOption.IGNORE_CASE,
    )

    /** Tags whose problems are worth reading: the app's own, the player's
     *  errors, and crashes. Everything else in this process is the TV's own
     *  components, which log warnings all day without anything being wrong
     *  ("FMQ grantorIdx", codec set-up, GPU, "A resource failed to call
     *  end", the remote's dropped connections): a user's log from 2026-10-18
     *  summed up as six of those and nothing else, and they differ from TV
     *  to TV, so they can't be listed. A new tag in the app belongs here. */
    private val OWN_TAGS = setOf(
        "App", "AppLog", "EpisodeEnum", "Failover", "FourKHdHub", "H5", "H5Api",
        "H5Resolver", "HomeSweep", "Icefy", "IntroDb", "JsIframeResolver", "LiveDiag",
        "LiveStreamProxy", "OpenSubs", "PersonSearch", "ProviderConfig", "Reminders",
        "RemoteAccess", "Scrub", "Search", "SeasonProbe", "SevenMovies", "SubSync",
        "UpdateChecker", "UpdateInstaller", "VidNest", "VijanaBaruBaru", "VixSrc",
        "VodDiag", "WatchNext",
    )
    private val PLAYER_TAGS = setOf(
        "ExoPlayerImplInternal", "ExoPlayerImpl", "DecoderAudioRenderer",
        "MediaCodecRenderer", "MediaCodecVideoRenderer", "MediaCodecAudioRenderer",
        "DefaultAudioSink", "AndroidRuntime",
    )

    private fun isProblem(line: String): Boolean {
        // "10-15 10:36:44.665 W/AudioTrack( 5993): ..." — level after the time.
        val lvl = line.getOrNull(19)
        if (lvl == 'V' || lvl == 'D') return false
        val tag = line.substring(minOf(21, line.length)).substringBefore('(').trim()
        if (tag !in OWN_TAGS && tag !in PLAYER_TAGS) return false
        // Only the message: the time's milliseconds (".489") read as an
        // HTTP status otherwise.
        val msg = line.substringAfter("): ", line)
        // Stack frames: the exception's own line already says what happened.
        if (msg.startsWith("\tat ") || msg.trimStart().startsWith("at ") ||
            msg.trimStart().startsWith("... ")
        ) return false
        return lvl == 'W' || lvl == 'E' || lvl == 'F' || PROBLEM_RE.containsMatchIn(msg)
    }

    /**
     * The whole log as one text file: a short header (app version, TV
     * model, Android version, what is playing), then the saved files (which
     * reach back across restarts), then the last crash if there was one.
     */
    fun exportText(context: Context, nowPlaying: String): String {
        val sb = StringBuilder()
        sb.append("Vijana BaruBaru log\n")
        sb.append("Made: ").append(java.util.Date()).append('\n')
        sb.append("App: v").append(appVersion(context)).append('\n')
        sb.append("TV: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Network: ").append(NetworkMonitor.state.value.name.lowercase()).append('\n')
        if (nowPlaying.isNotBlank()) sb.append("Now: ").append(clean(nowPlaying)).append('\n')
        sb.append("Links, tokens, cookies, keys and IP addresses are removed.\n\n")
        val saved = if (::dir.isInitialized) {
            listOf(File(dir, PREVIOUS), File(dir, CURRENT))
                .filter { it.isFile }
                .joinToString("") { f -> runCatching { f.readText() }.getOrDefault("") }
        } else ""
        if (saved.isNotBlank()) sb.append(saved)
        else synchronized(lines) { lines.forEach { sb.append(it).append('\n') } }
        val crash = File(context.filesDir, com.moviebox.tv.App.LAST_CRASH_FILE)
        if (crash.isFile) {
            sb.append("\n----- last crash -----\n")
            sb.append(clean(runCatching { crash.readText() }.getOrDefault("")))
            sb.append('\n')
        }
        return sb.toString()
    }

    fun fileName(context: Context): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "vijanabarubaru-log-v${appVersion(context)}-$day.txt"
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    // ---- cleaning -------------------------------------------------------

    private val URL_RE = Regex("""\b(https?)://([^/\s"'<>?#]+)[^\s"'<>]*""")
    private val JWT_RE = Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}""")
    private val BEARER_RE = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val SECRET_KV_RE = Regex(
        """(?i)\b(mb_token|token|access_token|refresh_token|playbacktoken|auth|""" +
            """authorization|cookie|set-cookie|password|passwd|secret|signature|sig|""" +
            """apikey|api_key|key|limitedcode|uid|userid|session|sessionid)""" +
            """(\s*[=:]\s*)("[^"]*"|[^\s,;&"'\]})]+)""",
    )
    private val IPV4_RE = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val EMAIL_RE = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val BLOB_RE = Regex("""[A-Za-z0-9+/_-]{40,}={0,2}""")

    /** Remove anything that could identify the viewer or unlock their
     *  sessions. Order matters: links first, so their tokens go with them. */
    fun clean(line: String): String {
        var s = line
        s = URL_RE.replace(s) { m -> "${m.groupValues[1]}://${m.groupValues[2]}/…" }
        s = JWT_RE.replace(s, "<token>")
        s = BEARER_RE.replace(s, "Bearer <token>")
        s = SECRET_KV_RE.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}<removed>" }
        s = EMAIL_RE.replace(s, "<email>")
        s = IPV4_RE.replace(s, "<ip>")
        // Long runs that mix letters and digits are tokens and ids; long
        // plain words ("libframework-connectivity-tiramisu-jni") stay.
        s = BLOB_RE.replace(s) { m ->
            val v = m.value
            if (v.any { it.isDigit() } && v.any { it.isLetter() }) "<…>" else v
        }
        return s
    }
}

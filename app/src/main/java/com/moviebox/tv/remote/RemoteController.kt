package com.moviebox.tv.remote

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.Repository
import com.moviebox.tv.data.SubjectType
import com.moviebox.tv.data.live.Channel
import com.moviebox.tv.data.live.ScheduleEvent
import com.moviebox.tv.data.tmdb.TmdbRepository
import com.moviebox.tv.data.local.DownloadEntity
import com.moviebox.tv.data.local.WatchHistoryEntity
import com.moviebox.tv.ui.MainViewModel
import java.lang.ref.WeakReference

/**
 * Bridge between the embedded remote server (background threads) and the app's
 * player + ViewModel (main thread). The player is never touched off the main
 * thread: reads come from cached @Volatile fields, commands are posted to the
 * main looper.
 */
object RemoteController {

    private val main = Handler(Looper.getMainLooper())
    private val repo = Repository()
    private val tmdb = TmdbRepository()

    /** Slice id: one of "trending" | "popular_movies" | "popular_tv" |
     *  "netflix" | "hbo" | "disney" | "prime" | "apple" | "hulu" |
     *  "genre_movie:<csv ids>" | "genre_tv:<csv ids>". */
    suspend fun browse(slice: String): List<Item> = runCatching {
        val rows = when {
            slice == "trending"        -> tmdb.trending()
            slice == "popular_movies"  -> tmdb.popularMovies()
            slice == "popular_tv"      -> tmdb.popularTv()
            slice == "netflix" -> tmdb.byNetwork(TmdbRepository.Networks.NETFLIX)
            slice == "hbo"     -> tmdb.byNetwork(TmdbRepository.Networks.HBO)
            slice == "disney"  -> tmdb.byNetwork(TmdbRepository.Networks.DISNEY_PLUS)
            slice == "prime"   -> tmdb.byNetwork(TmdbRepository.Networks.PRIME)
            slice == "apple"   -> tmdb.byNetwork(TmdbRepository.Networks.APPLE_TV)
            slice == "hulu"    -> tmdb.byNetwork(TmdbRepository.Networks.HULU)
            slice.startsWith("genre_movie:") -> tmdb.byGenre(
                slice.removePrefix("genre_movie:")
                    .split(",").mapNotNull { it.toIntOrNull() }, tv = false,
            )
            slice.startsWith("genre_tv:") -> tmdb.byGenre(
                slice.removePrefix("genre_tv:")
                    .split(",").mapNotNull { it.toIntOrNull() }, tv = true,
            )
            else -> emptyList()
        }
        // Drop titles already known not to resolve to a real aoneroom stream,
        // so the phone's Browse rows stop showing things that "don't exist".
        rows.filter { !com.moviebox.tv.data.UnavailableCatalog.isUnavailable(it.subjectId) }
    }.getOrDefault(emptyList())

    suspend fun movieGenres() = runCatching { tmdb.movieGenres() }
        .getOrDefault(emptyList())
    suspend fun tvGenres() = runCatching { tmdb.tvGenres() }
        .getOrDefault(emptyList())

    @Volatile var player: Player? = null
    @Volatile var audioManager: AudioManager? = null

    /** True while PlayerScreen's overlay (transport buttons, progress
     *  bar, title row, episode picker) is on screen. Read by
     *  [MainActivity.dispatchKeyEvent] to decide whether DPAD_LEFT/RIGHT
     *  should seek (overlay hidden — Netflix-style invisible-surface
     *  seek) or pass through to the Compose focus tree so the user
     *  navigates the overlay buttons (overlay visible). Defaults to true
     *  because PlayerScreen mounts with the overlay open on entry. */
    @Volatile var playerOverlayVisible: Boolean = true
    private var vmRef: WeakReference<MainViewModel>? = null

    @Volatile var nowPlayingTitle: String = ""
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
    @Volatile var isPlaying: Boolean = false
    /** Current series episode coordinates. Null for movies + live TV. The
     *  phone SPA reads these out of /api/state to decide whether to show
     *  the "Prev / Next ep" controls — they'd be no-ops on a movie so we
     *  hide them entirely there. */
    @Volatile var currentSeason: Int? = null
    @Volatile var currentEpisode: Int? = null
    @Volatile var selectedQuality: String = ""
    @Volatile var availableQualities: List<String> = emptyList()
    @Volatile var selectedDub: String = ""
    @Volatile var availableDubs: List<String> = emptyList()

    /** Effective (aoneroom) subjectId / type / year of whatever's on the
     *  player, so the phone's episode picker can enumerate the real episodes
     *  of the *currently playing* series. detailItem is re-stamped to the
     *  bridged aoneroom id at play time, so this is already the right id. */
    val nowPlayingSubjectId: String? get() = vm?.state?.value?.detailItem?.subjectId
    val nowPlayingType: Int get() = vm?.state?.value?.detailItem?.type?.code ?: 0
    val nowPlayingYear: Int? get() = vm?.state?.value?.detailItem?.year

    private val vm: MainViewModel? get() = vmRef?.get()

    fun bind(viewModel: MainViewModel, am: AudioManager) {
        vmRef = WeakReference(viewModel)
        audioManager = am
    }

    fun updatePlayback(title: String, pos: Long, dur: Long, playing: Boolean) {
        nowPlayingTitle = title
        positionMs = pos
        durationMs = dur
        isPlaying = playing
    }

    fun clearPlayback() {
        player = null
        nowPlayingTitle = ""
        positionMs = 0; durationMs = 0; isPlaying = false
        selectedQuality = ""; availableQualities = emptyList()
        selectedDub = ""; availableDubs = emptyList()
        currentSeason = null; currentEpisode = null
    }

    /** Mirror the currently-playing (season, episode) into RemoteController.
     *  Pass null/null for movies + live so the SPA hides the episode
     *  controls. Called by PlayerScreen when entry / episode change. */
    fun updateEpisode(se: Int?, ep: Int?) {
        currentSeason = se; currentEpisode = ep
    }

    fun updatePlayTracks(
        selectedQ: String, qs: List<String>,
        selectedD: String, ds: List<String>,
    ) {
        selectedQuality = selectedQ; availableQualities = qs
        selectedDub = selectedD;     availableDubs = ds
    }

    fun pickQuality(label: String) = main.post { vm?.changeQuality(label) }
    fun pickDub(name: String) = main.post { vm?.changeDub(name) }

    /**
     * First authenticated request from any phone: dismiss the D-pad-suggestion
     * tip AND close the QR overlay so the TV gets out of the user's way.
     */
    fun onClientActive() = main.post {
        vm?.dismissSuggestion()
        vm?.closeRemote()
    }

    // -- transport ---------------------------------------------------------

    fun playPause() = main.post {
        player?.let { it.playWhenReady = !it.isPlaying }
    }

    fun seekTo(ms: Long) = main.post { player?.seekTo(ms.coerceAtLeast(0)) }

    fun seekBy(deltaMs: Long) = main.post {
        player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
    }

    // -- volume ------------------------------------------------------------

    fun volumeUp() = audioManager?.adjustStreamVolume(
        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI,
    )

    fun volumeDown() = audioManager?.adjustStreamVolume(
        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI,
    )

    fun volumePercent(): Int {
        val am = audioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max == 0) return 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(pct: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (pct.coerceIn(0, 100) * max / 100), 0,
        )
    }

    // -- browse / play -----------------------------------------------------

    suspend fun search(query: String): List<Item> =
        runCatching { repo.search(query) }.getOrDefault(emptyList())

    suspend fun details(subjectId: String): com.moviebox.tv.data.Details? =
        runCatching { repo.details(subjectId) }.getOrNull()

    /** Real season → episode-numbers map for the phone's episode picker.
     *  Bridges a tmdb: id to its aoneroom record first (resolveByTitle), then
     *  returns the ENUMERATED episodes that actually exist — no phantom
     *  Seasons 1-8 / arbitrary episode numbers. Falls back to the catalog's
     *  declared maxEp only if the enumeration walk hasn't completed yet. */
    suspend fun episodes(
        subjectId: String, title: String?, year: Int?, isSeries: Boolean,
    ): Map<Int, List<Int>> = runCatching {
        val realId = if (subjectId.startsWith("tmdb:")) {
            repo.resolveByTitle(title.orEmpty(), year, isSeries)?.subjectId
                ?: return@runCatching emptyMap()
        } else subjectId
        // Prefer the real enumerated list if the background walk has cached it
        // (warm for the currently-playing series); never trigger a fresh ~19-
        // call walk inside a request — fall back to the fast declared maxEp.
        val cached = repo.cachedEpisodes(realId)
        if (!cached.isNullOrEmpty()) return@runCatching cached
        val d = repo.details(realId)
        d.seasons.associate { it.season to (1..it.episodes).toList() }
            .filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    fun playOnTv(
        subjectId: String, title: String, coverUrl: String?, type: Int,
        season: Int?, episode: Int?, year: Int? = null,
    ) = main.post {
        vm?.remotePlay(subjectId, title, coverUrl, type, season, episode, year)
    }

    // -- library -----------------------------------------------------------

    fun history(): List<WatchHistoryEntity> = vm?.continueWatching?.value ?: emptyList()
    fun downloads(): List<DownloadEntity> = vm?.downloads?.value ?: emptyList()
    fun deleteHistory(key: String) = main.post { vm?.removeHistory(key) }
    fun clearHistory() = main.post { vm?.clearHistory() }

    fun startDownload(
        subjectId: String, title: String, coverUrl: String?, type: Int,
        season: Int?, episode: Int?,
    ) = main.post {
        val item = Item(
            subjectId = subjectId, title = title,
            type = SubjectType.fromCode(type),
            year = null, rating = null, coverUrl = coverUrl, seasonCount = 0,
        )
        if (season == null) vm?.downloadMovie(item)
        else vm?.downloadEpisode(item, season, episode ?: 1)
    }

    fun deleteDownload(key: String) = main.post {
        val d = vm?.downloads?.value?.firstOrNull { it.key == key } ?: return@post
        vm?.removeDownload(d)
    }

    // -- live TV -----------------------------------------------------------

    /** Snapshot of the live channel catalog held by the VM. May be empty if
     *  the LIVE tab hasn't been opened yet — caller should invoke
     *  [ensureLiveLoaded] and retry shortly after. */
    fun liveChannels(): List<Channel> = vm?.state?.value?.liveChannels ?: emptyList()

    fun liveSchedule(): List<ScheduleEvent> =
        vm?.state?.value?.liveSchedule ?: emptyList()

    /** Latest deep-sweep health map (data/health.json). Keyed by channel
     *  id. SPA's /api/live/channels reads this to attach a "sweep" hint
     *  to each card so the phone can show the same "Often offline"
     *  advisory the TV grid does. */
    fun liveSweep(): Map<String, com.moviebox.tv.data.live.HealthEntry> =
        vm?.state?.value?.channelSweep ?: emptyMap()

    /** True after the first successful fetch — lets the SPA stop polling. */
    fun liveLoaded(): Boolean = (vm?.state?.value?.liveChannels?.isNotEmpty()) == true

    /** Kick off a load on the VM if we don't have channels yet OR the catalog
     *  has gone stale. Cheap to call repeatedly — the VM guards against
     *  concurrent loads and no-ops while the catalog is still fresh. The
     *  staleness reload is what lets the app recover from a transient bad
     *  publish without being killed and relaunched. */
    fun ensureLiveLoaded() = main.post {
        vm?.loadLiveIfStale()
    }

    /** Force an immediate catalog re-pull, bypassing the cache. Recovery
     *  hatch wired to /api/live/channels?force=1 — lets the phone refresh a
     *  stuck/stale channel list without restarting the TV app. The reload is
     *  async; the SPA picks up the new list on its next poll. */
    fun forceLiveReload() = main.post { vm?.loadLive(force = true) }

    /** Advance to the next episode in the currently-playing series.
     *  No-op for live or for movies. Triggered by the phone remote SPA
     *  "Next ⏭" button — gave the user a way to skip ahead without
     *  fumbling for the TV remote mid-show. */
    fun nextEpisode() = main.post { vm?.nextEpisode() }

    /** Drop back to the previous episode. Symmetric counterpart of
     *  [nextEpisode]. Wraps backward across season boundaries. */
    fun prevEpisode() = main.post { vm?.prevEpisode() }

    /** SPA-triggered "Restart live playback" — bounces the LiveStreamProxy
     *  socket and resets the failure counter. Recovery hatch for the
     *  long-haul stuck-channel case where the proxy's socket has
     *  silently degraded. */
    fun resetLivePlayback() = main.post { vm?.resetLivePlayback() }

    /** Close the player and return to the previous screen — equivalent
     *  to the user tapping Back. Lets the phone remote get out of a
     *  playback the user picked up by accident. */
    fun closePlayer() = main.post { vm?.back() }

    /** Play a live channel by its catalog id. */
    fun playLiveChannel(channelId: String) = main.post {
        vm?.playScheduleChannel(channelId)
    }

    /** Snapshot the latest update-check result for the SPA. JSON. */
    fun pendingUpdateJson(): String =
        vm?.pendingUpdateJson() ?: """{"available":false}"""
}

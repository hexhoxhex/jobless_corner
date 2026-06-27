package com.moviebox.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moviebox.tv.data.CaptionTrack
import com.moviebox.tv.data.Details
import com.moviebox.tv.data.HomeContent
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.PlayInfo
import com.moviebox.tv.data.Quality
import com.moviebox.tv.data.Recommendations
import com.moviebox.tv.data.Repository
import com.moviebox.tv.data.SubjectType
import com.moviebox.tv.data.TastePrefs
import com.moviebox.tv.data.UnavailableCatalog
import com.moviebox.tv.data.live.Channel
import com.moviebox.tv.data.live.LiveResolver
import com.moviebox.tv.data.live.LiveTvRepository
import com.moviebox.tv.data.live.ScheduleEvent
import com.moviebox.tv.data.local.AppDatabase
import com.moviebox.tv.data.local.ChannelHealthEntity
import com.moviebox.tv.data.local.DownloadEntity
import com.moviebox.tv.data.local.DownloadStatus
import com.moviebox.tv.data.local.FavouriteEntity
import com.moviebox.tv.data.local.WatchHistoryEntity
import com.moviebox.tv.download.DownloadService
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab { HOME, LIVE, SEARCH, DOWNLOADS, FAVOURITES }
enum class LiveSubTab { CHANNELS, SCHEDULE }
enum class Screen { TABS, DETAIL, PLAYER }

/** Pre-flight check that runs after a detail loads so the Play button can
 *  reflect actual availability before the user taps. */
enum class Availability { UNKNOWN, CHECKING, AVAILABLE, UNAVAILABLE }

private const val DEFAULT_QUALITY = "720p"

/** How many native-HLS failures before we conclude the proxy + ExoPlayer
 *  path is genuinely broken and try the WebView iframe fallback. With the
 *  proxy in place this should almost never fire — it only matters if the
 *  proxy itself is unable to resolve, e.g. donis is down end-to-end.
 *  Raised 6 → 10: with the proxy's last-good cache absorbing transient
 *  upstream 5xx, a real upstream-down event is the only thing that
 *  drives this counter up. A 5-minute session was hitting 6 too easily
 *  on borderline-flaky channels and dumping the user into the (Adscore-
 *  blocked) WebView path. */
// v0.1.78: 10 → 25. Mid-game CDN hiccups (load spikes during live sports) were
// running this counter up to 10 in a few minutes and dumping the user into the
// raw dlhd iframe page mid-retry — the "shows daddy live page when reconnecting"
// complaint. The native resolver retry path almost always recovers; reserve the
// WebView path for genuinely-broken streams.
private const val MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW: Int = 25

/** Probe up to this season number when discovering hidden seasons that
 *  the H5 detail endpoint under-reports. ~5 covers most network shows
 *  without spamming the origin. */
private const val HIDDEN_SEASON_PROBE_MAX: Int = 6

/** Rolling window for the failure counter. A channel that's been quiet
 *  for this long is presumed healthy again — reset the counter so a
 *  brand-new failure doesn't immediately trigger fallback based on
 *  ancient history. */
private const val FAILURE_WINDOW_MS: Long = 5 * 60 * 1000L  // 5 minutes

/** Window after a content switch during which progress saves are dropped,
 *  to keep the previous item's stale tail position from corrupting the new
 *  item's resume point. Covers the resolve (~1-2s) + the player's media
 *  swap; the new item's real saves start flowing ~5s in, well after. */
private const val CONTENT_SWITCH_SUPPRESS_MS: Long = 3_000L

/** How long the live catalog may sit before [MainViewModel.loadLiveIfStale]
 *  re-pulls channels.json. The app used to fetch the catalog exactly once per
 *  launch and keep it forever, so a single bad publish (a throttled scrape
 *  that marked most channels "down") blanked the grid until the app was
 *  killed. Reloading a stale catalog lets the app recover on its own. */
private const val LIVE_RELOAD_INTERVAL_MS: Long = 10 * 60 * 1000L  // 10 minutes

data class UiState(
    val tab: Tab = Tab.HOME,
    val screen: Screen = Screen.TABS,
    val home: HomeContent? = null,
    val homeLoading: Boolean = false,
    val query: String = "",
    val searchType: SubjectType = SubjectType.ALL,
    val searchResults: List<Item> = emptyList(),
    val searchLoading: Boolean = false,
    val detailItem: Item? = null,
    val details: Details? = null,
    val detailLoading: Boolean = false,
    val play: PlayInfo? = null,
    val playLoading: Boolean = false,
    val autoplayNext: Boolean = true,
    val currentSe: Int? = null,
    val currentEp: Int? = null,
    val resumeMs: Long = 0,
    val showRemote: Boolean = false,
    val suggestRemote: Boolean = false,
    val showSettings: Boolean = false,
    val error: String? = null,
    val availability: Availability = Availability.UNKNOWN,
    // ---- Live TV state ----
    val liveSubTab: LiveSubTab = LiveSubTab.CHANNELS,
    val liveChannels: List<Channel> = emptyList(),
    val liveSchedule: List<ScheduleEvent> = emptyList(),
    val liveLoading: Boolean = false,
    val liveError: String? = null,
    /** null = "All". Otherwise the selected `group` value (e.g. "USA (DADDY LIVE)"). */
    val liveGroup: String? = null,
    val liveQuery: String = "",
    /** Current network state from [com.moviebox.tv.debug.NetworkMonitor].
     *  Used by HomeScreen to render the quiet banner / full-page error. */
    val networkState: com.moviebox.tv.debug.NetworkMonitor.State =
        com.moviebox.tv.debug.NetworkMonitor.State.Online,
    /** Update found on GitHub releases, if any. Null until the launch-time
     *  UpdateChecker resolves; null again if we're already on latest. */
    val updateAvailable: com.moviebox.tv.debug.UpdateChecker.Result? = null,
    /** User dismissed the update banner on the TV for this version — don't
     *  re-show it for the rest of this session. */
    val updateDismissedFor: String? = null,
    /** Channel whose stream we're currently trying to play. Used by the
     *  PlayerScreen to spin up the WebView fallback when direct HLS fails. */
    val currentLiveChannel: Channel? = null,
    /** When true, render LiveWebPlayer instead of the native ExoPlayer for
     *  the active live stream. Flipped by [MainViewModel.fallbackToWebPlayer]
     *  after the HLS path errors. */
    val useLiveWebPlayer: Boolean = false,

    /** Monotonic counter bumped each time a channel is added to the
     *  software-decoder set. PlayerScreen includes it in its `remember`
     *  key for ExoPlayer so a hardware-flap-during-playback triggers a
     *  one-shot rebuild with the FFmpeg-preferred renderer factory. The
     *  *value* doesn't matter; only that it changes. */
    val softwareDecoderRevision: Int = 0,

    /** Per-channel playback health (keyed by channelId). Populated from
     *  Room's `channel_health` table. Empty entries mean the channel has
     *  no recorded trouble — Compose looks it up by id to decide whether
     *  to show the "Often unstable" badge on the grid card. */
    val channelHealth: Map<String, ChannelHealthEntity> = emptyMap(),
    /** Latest deep-sweep results from data/health.json. Advisory only —
     *  drives a "Often offline" badge on channel cards. Channels not in
     *  this map have no recent sweep data; treat as "unknown" (no badge,
     *  no penalty). */
    val channelSweep: Map<String, com.moviebox.tv.data.live.HealthEntry> = emptyMap(),
    /** Set of channelIds the user has starred. Drives the star overlay
     *  on each ChannelCard and the "Favourites" strip at the top of the
     *  LIVE tab. */
    val liveFavouriteIds: Set<String> = emptySet(),
    /** Pinned channels in starred-order (newest first). Used to render
     *  the favourites strip without joining ids back to the catalog. */
    val liveFavouriteChannels: List<com.moviebox.tv.data.local.LiveFavouriteEntity> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository()
    private val liveRepo = LiveTvRepository()
    private val liveResolver = LiveResolver()
    private val liveProxy = com.moviebox.tv.data.live.LiveStreamProxy(liveResolver)
    private val updateChecker = com.moviebox.tv.debug.UpdateChecker()
    @Volatile private var pendingUpdate: com.moviebox.tv.debug.UpdateChecker.Result? = null
    fun pendingUpdateJson(): String =
        pendingUpdate?.toJson() ?: """{"available":false}"""
    private val db = AppDatabase.get(app)
    private val favDao = db.favourites()
    private val watchDao = db.watchHistory()
    private val downloadDao = db.downloads()
    private val channelHealthDao = db.channelHealth()
    private val liveFavDao = db.liveFavourites()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    override fun onCleared() {
        super.onCleared()
        // Release the live-stream proxy socket. ViewModel is destroyed when
        // the user leaves the app — without this the bound port lingers
        // until process death, which is benign but noisy in logcat.
        runCatching { liveProxy.stop() }
    }

    val favourites: StateFlow<List<Item>> = favDao.all()
        .map { list ->
            val deny = TastePrefs.denyLanguages()
            list.map { it.toItem() }
                .filter { Repository.keepByLanguage(it.title, deny) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favouriteIds: StateFlow<Set<String>> = favDao.allIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Live-channel pins. Drives the "favourites strip" at the top of the
     *  Live tab and the star icon on each ChannelCard. */
    val liveFavourites: StateFlow<List<com.moviebox.tv.data.local.LiveFavouriteEntity>> =
        liveFavDao.all()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liveFavouriteIds: StateFlow<Set<String>> = liveFavDao.allIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleLiveFavourite(channel: com.moviebox.tv.data.live.Channel) {
        viewModelScope.launch {
            if (liveFavDao.isFavourite(channel.id)) {
                liveFavDao.remove(channel.id)
            } else {
                liveFavDao.add(
                    com.moviebox.tv.data.local.LiveFavouriteEntity(
                        channelId = channel.id,
                        name = channel.name,
                        logoUrl = channel.logo,
                        group = channel.group,
                        addedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    val continueWatching: StateFlow<List<WatchHistoryEntity>> =
        watchDao.continueWatching()
            .map { list ->
                val deny = TastePrefs.denyLanguages()
                list.asSequence()
                    .filter { Repository.keepByLanguage(it.title, deny) }
                    // Drop subjectIds we've already learned are dead —
                    // a movie that surfaced once, was tapped, failed to
                    // resolve and got UnavailableCatalog.mark'd. Without
                    // this filter it would still appear in Continue
                    // Watching and the user would tap it again, get the
                    // same error ("the recent shows a thing that isn't
                    // actually there"). Same filter Repository uses for
                    // home rows — keeps the two surfaces consistent.
                    .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
                    // Collapse per-series: only the newest unfinished
                    // episode per subjectId survives. Without this the
                    // Continue Watching row shows every episode the user
                    // ever touched — the "WHY are we showing all recent
                    // episodes" complaint. Movies are unaffected (a movie
                    // has exactly one row per subjectId anyway). Sequence
                    // is already newest-first from the DAO, so distinctBy
                    // keeps the freshest entry.
                    .distinctBy { it.subjectId }
                    .take(30)
                    .toList()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val downloads: StateFlow<List<DownloadEntity>> =
        downloadDao.all()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Set of "subjectId|se|ep" keys the user has FINISHED (watched to
     *  within 20 s of the end). The episode picker reads this to draw a
     *  watched check on completed episodes and a "Season complete" mark
     *  when every present episode in a season is finished. */
    val watchedKeys: StateFlow<Set<String>> =
        watchDao.all()
            .map { history ->
                history.asSequence()
                    .filter { it.finished }
                    .map {
                        WatchHistoryEntity.keyOf(
                            it.subjectId,
                            it.season.takeIf { s -> s > 0 },
                            it.episode.takeIf { e -> e > 0 },
                        )
                    }
                    .toSet()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // One-shot reset of bounce counters on this version. v0.1.42
        // added the ffmpeg audio decoder — channels that were stuck
        // web-only because of TCL's 24 kHz audio rejection (FOX USA,
        // FOXNY USA) can now play natively. Without this wipe, the
        // cached webOnlyHint=true from yesterday's failures sends them
        // straight to the WebView fallback and the ffmpeg path never
        // gets exercised. Gated on a SharedPref flag so it only runs
        // once per install — subsequent boots use the normal learned
        // health.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prefs = app.getSharedPreferences("migrations", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("reset_health_ffmpeg_v1", false)) {
                runCatching { channelHealthDao.resetAllBounces() }
                prefs.edit().putBoolean("reset_health_ffmpeg_v1", true).apply()
                android.util.Log.i("LiveDiag",
                    "VM one-shot: reset channel-health bounce counts " +
                        "(ffmpeg renderer now available)")
            }
        }
        // Start the LiveStreamProxy eagerly so its 127.0.0.1 socket is
        // bound by the time the user picks their first channel. The lazy
        // start-on-first-playChannel path was prone to a race: if the
        // user tapped a channel before the NanoHTTPD bind completed,
        // [LiveStreamProxy.proxyUrl] returned null and playback fell
        // through to the catalog's cached (often expired) stream URL —
        // observed on the TV after a fresh launch where logcat showed
        // no PROXY events and no listening port in /proc/$PID/net/tcp.
        // Calling start() here is idempotent and cheap (~5 ms to bind).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { liveProxy.start() }
        }
        // Mirror live favourites into UiState so Compose can read it
        // synchronously without each ChannelCard subscribing to a Flow.
        viewModelScope.launch {
            liveFavDao.all().collect { rows ->
                _state.update {
                    it.copy(
                        liveFavouriteChannels = rows,
                        liveFavouriteIds = rows.map { f -> f.channelId }.toSet(),
                    )
                }
            }
        }
        // Mirror channel health into UiState so Compose can read it
        // synchronously from `state.channelHealth[ch.id]` without each
        // ChannelCard subscribing to its own DAO query.
        viewModelScope.launch {
            channelHealthDao.all().collect { rows ->
                val map = rows.associateBy { it.channelId }
                _state.update { it.copy(channelHealth = map) }
            }
        }
    }

    private val homeFlow = MutableStateFlow<HomeContent?>(null)

    /** Personalized picks scored from the watch history's taste profile. */
    val recommendations: StateFlow<List<Item>> =
        combine(homeFlow, watchDao.all()) { home, history ->
            val pool = home?.let {
                it.rows.flatMap { r -> r.items } + it.heroes.map { h -> h.item }
            } ?: emptyList()
            val deny = TastePrefs.denyLanguages()
            Recommendations.recommend(pool, history)
                .filter { Repository.keepByLanguage(it.title, deny) }
                // Drop titles we've already learned don't bridge to a real
                // aoneroom stream (released but not on the source yet) — so
                // the auto-advance never lands on a dead pick. The future-
                // release filter in TmdbRepository.toItem handles the
                // "not yet out" case; this handles "out but not available".
                .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** subjectId → last-seen cover URL. Populated whenever we encounter an
     *  Item with a cover (search hits, openItem, remotePlay, history rows).
     *  Used as a fallback in saveProgress so an auto-advance to a brand new
     *  Item (no cover yet) doesn't wipe the Continue Watching poster. */
    private val knownCovers = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun coverFor(subjectId: String): String? = knownCovers[subjectId]
    private fun rememberCover(item: Item?) {
        item?.subjectId?.let { id ->
            item.coverUrl?.takeIf { it.isNotBlank() }?.let { knownCovers[id] = it }
        }
    }

    private var subjectId: String = ""
    /**
     * If the current item was bridged from TMDB, holds the original tmdb id so
     * a later resolve() failure can mark it as unavailable.
     */
    private var pendingTmdbId: String? = null
    private var dub: String = "Original"
    // Default to 720p: smoother on weak hardware/connections and far more
    // likely to be hardware-decoded (H.264) than 1080p HEVC. Users can bump up.
    private var quality: String = DEFAULT_QUALITY

    val contentKey: String
        get() = "$subjectId|${_state.value.currentSe}|${_state.value.currentEp}"

    init {
        loadHome()
        backfillHistoryCovers()
        prevalidateRecommendations()
        // Fire one update check on launch. Public GitHub release lookup,
        // no token. Failures are silent — SPA / TV banner just won't show.
        viewModelScope.launch {
            val result = updateChecker.check(com.moviebox.tv.BuildConfig.VERSION_NAME)
            pendingUpdate = result
            if (result != null) {
                _state.update { it.copy(updateAvailable = result) }
                com.moviebox.tv.debug.Telemetry.note(
                    com.moviebox.tv.debug.Telemetry.Severity.INFO,
                    "Update available: ${result.name}",
                )
                com.moviebox.tv.debug.ProviderHealth.success("github")
            } else {
                com.moviebox.tv.debug.ProviderHealth.success("github")
            }
        }
        // Tick the NetworkMonitor + persist today's telemetry snapshot
        // every 10s. Cheap — just reads atomic counters into SharedPrefs.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                com.moviebox.tv.debug.NetworkMonitor.tick()
                com.moviebox.tv.debug.Telemetry.persistTodaySnapshot()
                // Expose network state to the UI so the home banner /
                // full-page error can react without each screen having to
                // observe the StateFlow.
                val netNow = com.moviebox.tv.debug.NetworkMonitor.state.value
                if (netNow != _state.value.networkState) {
                    _state.update { it.copy(networkState = netNow) }
                }
            }
        }
    }

    fun dismissUpdateBanner() {
        val v = _state.value.updateAvailable?.tag ?: return
        _state.update { it.copy(updateDismissedFor = v) }
    }

    fun openUpdateInBrowser(context: android.content.Context) {
        val u = _state.value.updateAvailable ?: return
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(u.apkUrl.ifBlank { u.htmlUrl }),
        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    /** One-tap in-app update: download via DownloadManager, then pop the
     *  system install confirmation. Falls back to the browser path if the
     *  release didn't expose a .apk asset (e.g. tag pushed before the CI
     *  artifact uploaded). */
    fun installUpdate(context: android.content.Context) {
        val u = _state.value.updateAvailable ?: return
        if (u.apkUrl.isBlank() || !u.apkUrl.endsWith(".apk", ignoreCase = true)) {
            openUpdateInBrowser(context); return
        }
        com.moviebox.tv.debug.UpdateInstaller.download(
            context, apkUrl = u.apkUrl, version = u.tag,
        )
    }

    /** One-shot startup migration: backfill missing covers in the watch
     *  history. Entries written before v0.1.83 stored coverUrl=null because
     *  the auto-advance path didn't preserve it — Continue Watching then
     *  rendered as a list of names with no posters. For each row with a
     *  blank cover we do a single H5 search by title, take the first hit's
     *  cover, and update the row. Bounded: at most one search per row, the
     *  whole thing runs once per launch, and any failure is silent. */
    /** Background-validate TMDB items in the recommendation pool: try the
     *  H5 search bridge once for each, and mark the failures as unavailable
     *  so they never reach the recommendations row. This is what stops the
     *  "Supergirl → Couldn't auto-match" loop at the source — before, items
     *  were only marked dead AFTER the user tapped one. Capped + throttled
     *  so it can't hammer the API on a fresh launch. */
    private fun prevalidateRecommendations() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Wait for the home feed to populate, then check the first ~30
            // TMDB items. Empirically that covers ~5 recommendation rows;
            // anything past that, the user can prune as they go.
            kotlinx.coroutines.delay(2_500)
            val home = homeFlow.value ?: return@launch
            val pool = (home.rows.flatMap { r -> r.items } + home.heroes.map { it.item })
                .distinctBy { it.subjectId }
                .filter { it.subjectId.startsWith("tmdb:") }
                .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
                .take(30)
            for (item in pool) {
                runCatching {
                    val match = repo.resolveByTitle(item.title, item.year, item.isSeries)
                    if (match == null) {
                        UnavailableCatalog.mark(item.subjectId)
                        android.util.Log.i(
                            "VodDiag",
                            "prevalidate: marked unavailable ${item.title}",
                        )
                    }
                }
                kotlinx.coroutines.delay(120)  // be a good citizen
            }
        }
    }

    private fun backfillHistoryCovers() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rows = runCatching { watchDao.allOnce() }.getOrNull() ?: return@launch
            // First pass: pre-populate knownCovers from rows that ALREADY have
            // a cover. That way /api/history's in-process fallback can serve
            // posters for the missing rows immediately, even before the async
            // search-backfill below finishes.
            rows.forEach { r ->
                r.coverUrl?.takeIf { it.isNotBlank() }?.let { knownCovers[r.subjectId] = it }
            }
            val missing = rows.filter { it.coverUrl.isNullOrBlank() && it.title.isNotBlank() }
            if (missing.isEmpty()) return@launch
            android.util.Log.i(
                "VodDiag",
                "backfillHistoryCovers: ${missing.size} rows missing covers",
            )
            for (row in missing.take(50)) {  // hard cap so a huge history can't hammer the API
                runCatching {
                    val items = com.moviebox.tv.net.H5Api.search(row.title, perPage = 5)
                    // Prefer an exact subjectId match, then a year match, then the top hit.
                    val pick = items.firstOrNull { it.subjectId == row.subjectId }
                        ?: items.firstOrNull { row.year != null && it.year == row.year }
                        ?: items.firstOrNull()
                    val cover = pick?.coverUrl?.takeIf { it.isNotBlank() }
                    if (cover != null) {
                        knownCovers[row.subjectId] = cover
                        watchDao.upsert(row.copy(coverUrl = cover))
                    }
                }
                kotlinx.coroutines.delay(80)  // gentle, don't burst the H5 search rate limit
            }
        }
    }

    fun loadHome() {
        _state.update { it.copy(homeLoading = true, error = null) }
        viewModelScope.launch {
            // Start with an empty content so the UI paints immediately, then
            // fill each row as its fetch returns — beats waiting for the slowest.
            val initial = com.moviebox.tv.data.HomeContent(
                heroes = emptyList(), rows = emptyList(),
            )
            homeFlow.value = initial
            _state.update { it.copy(home = initial, homeLoading = false) }
            repo.streamHome(
                onHero = { hero ->
                    _state.update { s ->
                        val cur = s.home ?: initial
                        val next = cur.copy(heroes = cur.heroes + hero)
                        homeFlow.value = next
                        s.copy(home = next)
                    }
                },
                onRow = { row ->
                    _state.update { s ->
                        val cur = s.home ?: initial
                        val next = cur.copy(rows = cur.rows + row)
                        homeFlow.value = next
                        s.copy(home = next)
                    }
                },
                onError = { e ->
                    _state.update { it.copy(error = e.message) }
                },
            )
        }
    }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.LIVE) loadLiveIfStale()
    }

    // ---- Live TV ----

    /** elapsedRealtime of the last successful catalog load. Drives
     *  [loadLiveIfStale]. */
    @Volatile private var liveLoadedAt = 0L

    /** Reload the catalog if we've never loaded it or the last good load is
     *  older than [LIVE_RELOAD_INTERVAL_MS]. Cheap to call on every LIVE-tab
     *  open / SPA poll — it no-ops while fresh. The first load uses the normal
     *  TTL path; a stale reload forces past the repo cache so a genuinely
     *  newer channels.json always wins. */
    fun loadLiveIfStale() {
        val s = _state.value
        if (s.liveLoading) return
        val stale = s.liveChannels.isEmpty() ||
            android.os.SystemClock.elapsedRealtime() - liveLoadedAt > LIVE_RELOAD_INTERVAL_MS
        if (stale) loadLive(force = liveLoadedAt != 0L)
    }

    fun loadLive(force: Boolean = false) {
        if (_state.value.liveLoading) return
        _state.update { it.copy(liveLoading = true, liveError = null) }
        viewModelScope.launch {
            runCatching {
                val channels = liveRepo.channels(force = force)
                val schedule = liveRepo.schedule(force = force)
                channels to schedule
            }.onSuccess { (c, s) ->
                liveLoadedAt = android.os.SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(
                        liveChannels = c, liveSchedule = s, liveLoading = false,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(liveLoading = false, liveError = e.message)
                }
            }
            // Fetch the deep-sweep health snapshot in parallel. Best-effort
            // — if data/health.json hasn't been published yet (sweep cron
            // hasn't run), the map stays empty and nothing changes about
            // the live grid. Never blocks the user's live tab from
            // rendering.
            runCatching {
                val sweep = liveRepo.health(force = force)
                if (sweep.isNotEmpty()) {
                    _state.update { it.copy(channelSweep = sweep) }
                }
            }
        }
    }

    fun selectLiveSubTab(t: LiveSubTab) = _state.update { it.copy(liveSubTab = t) }
    fun selectLiveGroup(g: String?) = _state.update { it.copy(liveGroup = g) }
    fun onLiveQuery(q: String) = _state.update { it.copy(liveQuery = q) }

    /** Open the player for a TV channel.
     *
     *  Resolves a fresh, IP-signed stream URL via [LiveResolver] first; falls
     *  back to the catalog's cached [Channel.streamUrl] if the resolver
     *  fails. The resolver is necessary because the catalog's signed URLs
     *  are IP-bound to the scraper, not the playback device — see the
     *  IP-binding confirmation in `LIVE_TV_BUG_REPORT.md` §0.
     */
    /** How many recovery attempts have fired in the current rolling window
     *  for the current channel. Counts watchdog freezes and Source errors
     *  alike. Drives the WebView fallback threshold via
     *  [shouldFallbackToWebPlayer]. Resets on channel change, OR if the
     *  last failure was longer than [FAILURE_WINDOW_MS] ago (channel
     *  presumed recovered). */
    private var liveResolveFailures = 0

    /** Wall-clock ms of the last [refreshLiveStream] call, used for the
     *  rolling window reset. */
    private var lastResolveFailureAt = 0L

    /** Set of channelIds we've seen the TV's hardware decoder flap on
     *  this app session — see [LiveCodecFlapDetector]. Playback for any
     *  channel in this set is built with the FFmpeg software renderer
     *  preferred over the SoC's MediaCodec, sidestepping the
     *  segment-boundary tear-down storm we observed on the Realtek
     *  hardware path. In-memory for now; survives until app restart. */
    private val softwareDecoderChannels = mutableSetOf<String>()

    fun preferSoftwareDecoderFor(channelId: String): Boolean =
        channelId in softwareDecoderChannels

    /** Called by PlayerScreen when [LiveCodecFlapDetector] crosses its
     *  threshold. Adding the channel to the set + emitting a state
     *  update triggers the PlayerScreen's `remember(...)` key to change,
     *  which rebuilds ExoPlayer with the FFmpeg-preferred renderer. */
    fun markChannelAsCodecFlapping(channelId: String) {
        if (softwareDecoderChannels.add(channelId)) {
            com.moviebox.tv.debug.Telemetry.note(
                com.moviebox.tv.debug.Telemetry.Severity.WARN,
                "Codec flap on $channelId — switching to software decoder",
            )
            // Bump a state field so the Compose tree recomposes; the
            // value itself doesn't matter, only that it changed.
            _state.update { it.copy(softwareDecoderRevision = it.softwareDecoderRevision + 1) }
        }
    }

    fun playChannel(ch: Channel) {
        // If this channel has bounced ≥3 times back-to-back, the native
        // HLS path almost certainly won't work this time either — skip
        // the 90-s cascade and open the WebView fallback directly. A
        // single successful playback (>2 min) resets the counter and
        // re-enables the native attempt on the next tap.
        val healthRow = _state.value.channelHealth[ch.id]
        val webOnly = healthRow?.webOnlyHint == true
        // Flip into PLAYER immediately with a placeholder so the user sees
        // the player chrome populate while we resolve. mediaUrl stays blank
        // until the resolver lands — PlayerScreen waits on the blank URL
        // before constructing ExoPlayer.
        _state.update {
            it.copy(
                screen = Screen.PLAYER,
                play = PlayInfo(
                    title = ch.displayName,
                    mediaUrl = "",
                    selected = "LIVE",
                    qualities = emptyList(),
                    captions = emptyList(),
                    dubs = emptyList(),
                    selectedDub = "",
                    season = 0, episode = 0, episodeTitle = "",
                    durationSec = 0,
                    isLive = true,
                    subtitle = ch.group ?: "Live TV",
                ),
                playLoading = !webOnly,
                resumeMs = 0L,
                detailItem = null,
                details = null,
                currentSe = null, currentEp = null,
                error = null,
                currentLiveChannel = ch,
                useLiveWebPlayer = webOnly,
            )
        }
        liveResolveFailures = 0
        lastResolveFailureAt = 0L
        // Run the success-recorder on EVERY play, web or native. If the
        // channel is still selected after 2 min, mark it healthy — this
        // both unsticks unstableHint badges and clears webOnlyHint so
        // the native cascade is tried again next time.
        scheduleHealthSuccess(ch.id)
        if (webOnly) {
            // No resolve needed — PlayerScreen will render LiveWebPlayer
            // straight away. Telemetry still fires so debug rollups see
            // the play attempt.
            com.moviebox.tv.debug.Telemetry.onPlayStart(
                kind = "live", title = ch.displayName, channelId = ch.id,
            )
            com.moviebox.tv.debug.Telemetry.note(
                com.moviebox.tv.debug.Telemetry.Severity.INFO,
                "Channel ${ch.id} flagged web-only after " +
                    "${healthRow?.bounceCount} bounce(s); skipping native cascade",
            )
            return
        }
        viewModelScope.launch {
            // Spin up the proxy and prime its cache for this channel — the
            // first inner-playlist fetch triggers a sync resolve which we
            // do here on the IO dispatcher rather than blocking the
            // first /inner request from the player. If the proxy can't
            // resolve at all (network down at tap time), fall back to the
            // catalog's cached URL so the user still has *something* to
            // try, even if it's an expired token.
            val proxyReady = liveProxy.start()
            com.moviebox.tv.debug.Telemetry.onPlayStart(
                kind = "live", title = ch.displayName, channelId = ch.id,
            )
            val resolved = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    liveResolver.resolveStream(ch.id)
                }
            }.getOrNull()
            // Prefer the proxy URL (which transparently refreshes tokens
            // forever); fall back to the directly-resolved URL if the
            // proxy didn't come up; final fallback is the catalog's
            // stored URL even if it might be expired — at least the user
            // sees something attempt to play instead of staring at a
            // loading spinner with no feedback.
            //
            // IMPORTANT — the gate used to be `proxyReady && resolved != null`,
            // which meant a failure of the pre-resolve here (transient
            // donis 5xx, briefly-dead daddy endpoint, anything) skipped
            // the proxy entirely and pushed the user onto the catalog's
            // IP-bound URL — which then 403s on any device that doesn't
            // share the scraper's egress IP. The proxy already does its
            // own resolve on the first /inner request, so the pre-resolve
            // is purely a "prime the cache" optimisation. Don't let it
            // gate the proxy URL.
            val proxyUrl = if (proxyReady) liveProxy.proxyUrl(ch.id) else null
            val finalUrl = proxyUrl
                ?: resolved
                ?: ch.streamUrl
                ?: ""
            if (proxyUrl == null && resolved != null) {
                com.moviebox.tv.debug.Telemetry.note(
                    com.moviebox.tv.debug.Telemetry.Severity.WARN,
                    "Live proxy unavailable; using direct CDN URL " +
                        "(token expiry will end this session in ~60 min)",
                )
            }
            if (finalUrl.isBlank()) {
                _state.update { s ->
                    s.copy(
                        playLoading = false,
                        error = "Couldn't resolve \"${ch.displayName}\".",
                        screen = Screen.TABS, tab = Tab.LIVE, play = null,
                        currentLiveChannel = null, useLiveWebPlayer = false,
                    )
                }
                return@launch
            }
            _state.update { s ->
                if (s.currentLiveChannel?.id != ch.id) {
                    s
                } else {
                    s.copy(
                        play = s.play?.copy(mediaUrl = finalUrl),
                        playLoading = false,
                    )
                }
            }
            // No periodic refresh loop needed — the proxy refreshes its
            // token cache transparently while serving inner-playlist
            // requests. ExoPlayer never sees the URL change.
        }
    }

    /** PlayerScreen calls this when the native HLS player hits an
     *  InvalidResponseCodeException 401/403/410. With the LiveStreamProxy
     *  in place, this is now extremely rare — the proxy refreshes its
     *  cached upstream URL transparently before tokens expire. But the
     *  proxy itself could be down (port bind failed, etc.) or donis could
     *  be unreachable at refresh time, so we still surface a manual retry.
     *
     *  The retry just bumps the failure counter; PlayerScreen will reload
     *  the same proxy URL via its existing source-error retry, and the
     *  next /inner request will force a fresh resolve. */
    fun refreshLiveStream(): Boolean {
        val now = System.currentTimeMillis()
        val chId = _state.value.currentLiveChannel?.id ?: "?"
        val window = now - lastResolveFailureAt
        // Rolling-window reset: if the last failure was a while ago, the
        // channel has been healthy enough that we shouldn't hold the
        // earlier flaps against it. Lets a channel that briefly hiccupped
        // once an hour ago use its full retry budget on the new flap.
        if (window > FAILURE_WINDOW_MS) {
            liveResolveFailures = 0
            lastResolveFailureAt = 0L
        }
        liveResolveFailures += 1
        lastResolveFailureAt = now
        android.util.Log.w(
            "LiveDiag",
            "VM ch=$chId refreshLiveStream count=$liveResolveFailures " +
                "/${MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW} " +
                "windowSinceLastFailMs=$window",
        )
        // Drop the proxy's cached resolution + the resolver's cached host so
        // the next /master fetch from ExoPlayer is forced to re-resolve from
        // scratch. Closes the death loop the user described as
        // "works → reconnects → works → reconnects → finally stops":
        // before this call, the proxy kept handing back the same dead
        // upstream URL on every prepare() retry, and only the proxy's own
        // 401/403/410 detection inside /inner would invalidate. Non-auth
        // failures (PlaylistStuck, mid-segment HTTP weirdness, transient
        // origin 5xx that fell off the retry budget) never triggered an
        // invalidation, so the player retried indefinitely on poisoned cache.
        runCatching { liveProxy.invalidate(chId) }
        return true
    }

    /** Truly-last-resort fallback. Only called after refreshLiveStream has
     *  failed [MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW] times in a row, which
     *  for the default 30-second base means ~10 minutes of unable-to-resolve
     *  before we give up on the native player and try the iframe pages. */
    fun fallbackToWebPlayer() {
        if (_state.value.currentLiveChannel == null) return
        android.util.Log.w(
            "LiveDiag",
            "VM ch=${_state.value.currentLiveChannel?.id} FALLBACK_WEB " +
                "after=$liveResolveFailures failures",
        )
        _state.update { it.copy(useLiveWebPlayer = true) }
    }

    /** Skip the resilience cascade entirely and switch to the WebView
     *  fallback right now. Used by PlayerScreen when it catches an
     *  unrecoverable native-player error (e.g.
     *  ERROR_CODE_AUDIO_TRACK_INIT_FAILED on FOXNY USA — the stream's
     *  24 kHz audio config is permanently rejected by this TCL's audio
     *  system, no number of retries will fix it). Bypasses the
     *  10-failure threshold of [shouldFallbackToWebPlayer]. */
    fun forceFallbackToWebPlayer() {
        if (_state.value.currentLiveChannel == null) return
        android.util.Log.w(
            "LiveDiag",
            "VM ch=${_state.value.currentLiveChannel?.id} FORCE_FALLBACK_WEB " +
                "(unrecoverable native error)",
        )
        _state.update { it.copy(useLiveWebPlayer = true) }
    }

    /** Used by PlayerScreen to decide whether to fall back. */
    fun shouldFallbackToWebPlayer(): Boolean =
        liveResolveFailures >= MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW

    /** Per-channel job that records playback success after 30 s of
     *  uninterrupted "this channel is still selected" time. Cancelled
     *  if the user navigates away before that window elapses. v0.1.43:
     *  dropped from 120 s → 30 s. The old threshold meant a channel
     *  could hit BehindLiveWindow at 60 s, recover, hit it again at
     *  120 s, recover, hit it again at 180 s — never recording a
     *  success because the 120 s job kept getting reset by the
     *  recovery seek. With the success flag never set, prior bounce
     *  counts from earlier sessions accumulate. 30 s is enough
     *  evidence the channel is live + the proxy is healthy. */
    private var healthSuccessJob: kotlinx.coroutines.Job? = null

    private fun scheduleHealthSuccess(channelId: String) {
        healthSuccessJob?.cancel()
        healthSuccessJob = viewModelScope.launch {
            kotlinx.coroutines.delay(30_000)
            val stillOn = _state.value.currentLiveChannel?.id == channelId
            if (stillOn) {
                // BUG FIX: previously this recorded success to the DAO but did
                // NOT reset liveResolveFailures. So cumulative transient blips
                // across multiple healthy-recovery cycles slowly counted toward
                // MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW. After ~3 brief outages
                // the user got doom-looped into the WebView (Adscore-blocked =
                // permanent black screen). Two minutes of healthy play is
                // strong evidence the channel is fine — give it a fresh budget.
                liveResolveFailures = 0
                lastResolveFailureAt = 0L
                runCatching {
                    channelHealthDao.recordSuccess(
                        channelId, System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /** Called by [LiveWebPlayer] via [PlayerScreen] when the cascade has
     *  exhausted: native HLS tried [MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW]
     *  times AND the WebView iframe also failed for all backends.
     *
     *  We record one bounce per cascade-exhaustion. After [ChannelHealthEntity
     *  .UNSTABLE_THRESHOLD] the grid shows a badge, after [ChannelHealthEntity
     *  .WEB_ONLY_THRESHOLD] the next tap skips the native attempt entirely. */
    fun recordChannelBounce(channelId: String) {
        viewModelScope.launch {
            runCatching {
                channelHealthDao.recordBounce(
                    channelId, System.currentTimeMillis(),
                )
            }
        }
    }

    /** From a schedule chip — look up the channel by id and play it.
     *  Also the entry point for the mobile-remote `/api/live/play` endpoint:
     *  if the user invokes it before the LIVE tab has loaded, we load the
     *  catalog first, then play. Without that, hitting the API from cold
     *  silently no-ops because `liveChannels` is empty. */
    fun playScheduleChannel(channelId: String) {
        val cached = _state.value.liveChannels.firstOrNull { it.id == channelId }
        if (cached != null) {
            playChannel(cached); return
        }
        viewModelScope.launch {
            val channels = runCatching { liveRepo.channels() }.getOrNull() ?: return@launch
            // Also warm the schedule cache. The Schedule tab won't show
            // anything otherwise (the SPA + the TV's UI both read from
            // `state.liveSchedule` which is set by the same loadLive flow
            // the user usually triggers via the LIVE tab).
            val schedule = runCatching { liveRepo.schedule() }.getOrNull().orEmpty()
            _state.update { it.copy(liveChannels = channels, liveSchedule = schedule) }
            android.util.Log.i(
                "LiveDiag",
                "VM playScheduleChannel cold-load: channels=${channels.size} " +
                    "schedule=${schedule.size}",
            )
            val ch = channels.firstOrNull { it.id == channelId } ?: return@launch
            playChannel(ch)
        }
    }


    private var searchJob: kotlinx.coroutines.Job? = null

    /** Live, debounced search — starts pulling results as the user types. */
    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.update { it.copy(searchResults = emptyList(), searchLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            search()
        }
    }

    fun onSearchType(t: SubjectType) = _state.update { it.copy(searchType = t) }
    fun setAutoplay(v: Boolean) = _state.update { it.copy(autoplayNext = v) }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(searchLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.search(q, _state.value.searchType) }
                .onSuccess { r ->
                    _state.update { it.copy(searchResults = r, searchLoading = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(searchLoading = false, searchResults = emptyList(),
                            error = e.message)
                    }
                }
        }
    }

    fun openItem(item: Item) {
        rememberCover(item)
        dub = "Original"
        quality = DEFAULT_QUALITY
        _state.update {
            it.copy(
                screen = Screen.DETAIL, detailItem = item, details = null,
                detailLoading = true, currentSe = null, currentEp = null,
                play = null, error = null,
                availability = Availability.UNKNOWN,
            )
        }
        viewModelScope.launch {
            // TMDB picks need bridging to an aoneroom subjectId before either
            // details or playback can resolve — aoneroom doesn't know "tmdb:*".
            pendingTmdbId =
                if (item.subjectId.startsWith("tmdb:")) item.subjectId else null
            val resolvedId = if (item.subjectId.startsWith("tmdb:")) {
                val match = runCatching {
                    repo.resolveByTitle(item.title, item.year, item.isSeries)
                }.getOrNull()
                if (match == null) {
                    // Mark the TMDB pick as un-bridgeable so the recommendations
                    // row stops surfacing it on the next refresh — the same
                    // "Couldn't auto-match" loop kept hitting the user. Push
                    // them into Search with the title pre-filled so they can
                    // pick another record themselves.
                    UnavailableCatalog.mark(item.subjectId)
                    _state.update {
                        it.copy(
                            screen = Screen.TABS,
                            tab = Tab.SEARCH,
                            query = item.title,
                            searchType = SubjectType.ALL,
                            detailLoading = false,
                            detailItem = null,
                            error = "Couldn't auto-match \"${item.title}\" — " +
                                "pick the right entry from search.",
                        )
                    }
                    onQuery(item.title) // kick off the debounced live search
                    return@launch
                }
                // Update the item the UI shows so the rest of the flow keeps
                // working with a real id.
                _state.update {
                    it.copy(detailItem = item.copy(subjectId = match.subjectId))
                }
                match.subjectId
            } else item.subjectId

            subjectId = resolvedId
            runCatching { repo.details(resolvedId, titleHint = item.title) }
                .onSuccess { d ->
                    _state.update { it.copy(details = d, detailLoading = false) }
                    // Verify in the background — the Play button reflects the
                    // result before the user even reaches for it.
                    precheckPlayback(resolvedId, d)
                    // Enumerate the REAL episode list in the background so
                    // phantom trailing episodes are hidden before the user
                    // ever taps one.
                    enumerateEpisodesInBackground(resolvedId, d)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(detailLoading = false, error = e.message)
                    }
                }
        }
    }

    /**
     * Idempotent details load. Used by [DetailScreen]'s self-heal when the
     * user lands on Detail from the player's back button without details
     * ever having been fetched — typical of a Continue Watching resume that
     * went straight into the player.
     *
     * Cheap no-op when details for this item are already loaded or in
     * flight. Does NOT touch currentSe/currentEp (back from player wants
     * to remember which episode was playing so the picker auto-scrolls to
     * the right row).
     */
    fun ensureDetails(item: Item) {
        val cur = _state.value
        if (cur.detailLoading) return
        if (cur.details?.subjectId == item.subjectId) return
        _state.update { it.copy(detailLoading = true) }
        viewModelScope.launch {
            runCatching { repo.details(item.subjectId, titleHint = item.title) }
                .onSuccess { d ->
                    _state.update {
                        if (it.detailItem?.subjectId == item.subjectId) {
                            it.copy(details = d, detailLoading = false)
                        } else it
                    }
                    precheckPlayback(item.subjectId, d)
                    enumerateEpisodesInBackground(item.subjectId, d)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(detailLoading = false, error = e.message)
                    }
                }
        }
    }

    /** Background walk of aoneroom's file listing to replace each
     *  season's maxEp-based episode count with the authoritative real
     *  set. Non-blocking: details render immediately with maxEp, then
     *  the grid refines (phantom episodes drop out) when this finishes.
     *  Best-effort — on any failure the maxEp fallback stands. */
    private fun enumerateEpisodesInBackground(resolvedId: String, d: Details) {
        if (!d.isSeries || d.seasons.isEmpty()) return
        viewModelScope.launch {
            // Probe for hidden seasons in parallel — doesn't depend on the
            // episode-enumeration walk succeeding. enumerateEpisodes hits
            // the legacy mobile API which is 441-gated now; probing only
            // uses the H5 play endpoint we already rely on.
            launch { probeHiddenSeasons(resolvedId, d.subjectId) }
            val realMap = runCatching { repo.enumerateEpisodes(resolvedId) }
                .getOrNull() ?: return@launch  // walk failed/truncated → keep maxEp
            // Guard against the user having navigated away mid-walk.
            val cur = _state.value
            if (cur.details?.subjectId != d.subjectId ||
                cur.detailItem?.subjectId != resolvedId
            ) return@launch
            // The walk completed (enumerateEpisodes only returns non-null
            // on a full walk), so absence of a season from realMap is
            // AUTHORITATIVE: that season is entirely phantom (Family Guy
            // S11/S20/S21 — maxEp claims they exist, aoneroom has zero
            // files). Set realEpisodes for EVERY season: the real list,
            // or an empty list for phantom seasons. The UI then drops
            // empty seasons from the picker.
            val refined = d.seasons.map { s ->
                s.copy(realEpisodes = realMap[s.season] ?: emptyList())
            }
            val dropped = d.seasons.count { realMap[it.season].isNullOrEmpty() }
            android.util.Log.i(
                "EpisodeEnum",
                "${d.title}: refined ${d.seasons.size} seasons " +
                    "($dropped phantom seasons dropped)",
            )
            _state.update { st ->
                // Only apply if the details in state are still THIS series
                // (re-check after the suspend point).
                if (st.details?.subjectId == d.subjectId) {
                    st.copy(details = st.details.copy(seasons = refined))
                } else st
            }
        }
    }

    /**
     * Try play(subjectId, se=N, ep=1) for N above the highest known season.
     * Each call that returns streams reveals a hidden season the H5 detail
     * endpoint didn't list. Adds discovered seasons to state.details so the
     * picker dropdown shows them. The subject-level fallback is restricted
     * to S1E1, so a streams=true response for se=N here is a real S(N)
     * resource — no risk of confusing the show's subject-level S1E1 with a
     * fake S(N) entry.
     *
     * Bounded: probes at most [HIDDEN_SEASON_PROBE_MAX] seasons above the
     * highest known one, and stops on the first consecutive gap (two empty
     * responses in a row).
     */
    private suspend fun probeHiddenSeasons(resolvedId: String, expectedDetailId: String) {
        val cur = _state.value
        val known = cur.details?.seasons.orEmpty().mapNotNull { it.season }
            .toMutableList()
        val highest = known.maxOrNull() ?: 0
        if (highest >= HIDDEN_SEASON_PROBE_MAX) return
        val discovered = mutableListOf<Int>()
        var consecutiveGaps = 0
        for (se in (highest + 1)..HIDDEN_SEASON_PROBE_MAX) {
            val result = runCatching {
                com.moviebox.tv.net.H5Api.play(
                    subjectId = resolvedId,
                    season = se, episode = 1,
                    detailPath = com.moviebox.tv.net.H5Api.detailPathFor(resolvedId) ?: "",
                )
            }.getOrNull()
            if (result != null && result.streams.isNotEmpty()) {
                discovered.add(se)
                consecutiveGaps = 0
                android.util.Log.i(
                    "SeasonProbe",
                    "$resolvedId hidden season discovered: S$se (streams=${result.streams.size})",
                )
            } else {
                consecutiveGaps += 1
                if (consecutiveGaps >= 2) break // two empties → no more seasons
            }
        }
        if (discovered.isEmpty()) return
        val cur2 = _state.value
        if (cur2.details?.subjectId != expectedDetailId ||
            cur2.detailItem?.subjectId != resolvedId
        ) return
        _state.update { st ->
            val curDetails = st.details ?: return@update st
            // Build new season entries. We don't know maxEp from the probe,
            // so we estimate from S1's count (typical for most series).
            // realEpisodes is left null so the user-visible flow can still
            // refine via enumeration if available.
            val s1Eps = curDetails.seasons.firstOrNull()?.episodes ?: 10
            val newSeasons = curDetails.seasons.toMutableList()
            for (se in discovered) {
                if (newSeasons.none { it.season == se }) {
                    newSeasons.add(curDetails.seasons.first().copy(
                        season = se, episodes = s1Eps, realEpisodes = null,
                    ))
                }
            }
            newSeasons.sortBy { it.season }
            st.copy(details = curDetails.copy(seasons = newSeasons))
        }
    }

    /**
     * Background availability probe. For movies, attempts to resolve the
     * stream URL; for series, probes S1E1 as a proxy for whether the show is
     * watchable at all. Cheap enough to run on every Detail open.
     */
    private fun precheckPlayback(resolvedId: String, d: Details) {
        _state.update { it.copy(availability = Availability.CHECKING) }
        viewModelScope.launch {
            // Default to S1 when a series has no season metadata. HBO-tier
            // titles (House of the Dragon, etc.) come back from H5 detail
            // with seasons=[] even though the play call accepts se=1 ep=1
            // and returns subject-level streams. Passing season=null
            // upstream skipped that path and the precheck spuriously
            // marked the show UNAVAILABLE.
            val s1 = d.seasons.firstOrNull()?.season ?: 1
            val title = d.title.ifBlank { _state.value.detailItem?.title.orEmpty() }
            val isSeries = d.isSeries
            suspend fun tryResolve(id: String) = runCatching {
                repo.resolvePlay(
                    subjectId = id,
                    resolution = quality,
                    season = if (isSeries) s1 else null,
                    episode = if (isSeries) 1 else null,
                    dub = dub,
                    titleHint = title,
                )
            }

            // 1. Try the tapped subjectId first.
            val first = tryResolve(resolvedId)
            var workingId: String? = if (first.isSuccess &&
                first.getOrNull()?.mediaUrl?.isNotBlank() == true) {
                resolvedId
            } else null

            // 2. If the tapped subjectId has no streams, AUTO-PICK from search
            //    instead of telling the user to "pick from search" manually.
            //    aoneroom's home/Popular Series rows surface different
            //    subjectIds than search — e.g. HotD shows on Popular Series
            //    as 5373384118887662624 (no streams) while search returns
            //    7721864815710718808 (works). We search the same title,
            //    iterate matching items, and swap to the first playable one.
            //    Transparent to the user — they see "Play" not "pick".
            if (workingId == null && title.isNotBlank()) {
                val matches = runCatching {
                    repo.search(keyword = title, type = SubjectType.ALL)
                }.getOrNull().orEmpty()
                val titleLower = title.lowercase().trim()
                val candidates = matches.asSequence()
                    .filter { it.subjectId != resolvedId }
                    .filter {
                        val t = it.title.lowercase().trim()
                        t == titleLower || t.startsWith(titleLower) ||
                            titleLower.startsWith(t)
                    }
                    .take(3) // bounded — 3 attempts × ~1s each = ~3s budget
                for (cand in candidates) {
                    android.util.Log.i(
                        "VodDiag",
                        "precheck fallback: tapped $resolvedId failed, " +
                            "trying $cand.subjectId for \"${cand.title}\"",
                    )
                    val r = tryResolve(cand.subjectId)
                    if (r.isSuccess && r.getOrNull()?.mediaUrl?.isNotBlank() == true) {
                        workingId = cand.subjectId
                        break
                    }
                }
            }

            // Be careful not to overwrite the user's state if they've moved on.
            val cur = _state.value
            if (cur.detailItem?.subjectId == resolvedId &&
                cur.screen == Screen.DETAIL
            ) {
                if (workingId != null && workingId != resolvedId) {
                    // SWAP: route future plays through the working subjectId.
                    // playMovie/playEpisode read from `this.subjectId`, and
                    // detailItem.subjectId drives the UI gating, so update
                    // both. Save a knownCover under the new id so any later
                    // refresh has the poster.
                    knownCovers[cur.detailItem.subjectId]
                        ?.let { knownCovers[workingId] = it }
                    subjectId = workingId
                    _state.update {
                        it.copy(
                            detailItem = it.detailItem?.copy(subjectId = workingId),
                            // Drop the OLD details (they belong to the dead
                            // subjectId — e.g. for HotD the Popular Series
                            // subjectId returns seasons=[] but the working
                            // search subjectId may return real seasons +
                            // episodes). Setting detailLoading=true makes
                            // the spinner appear while the reload below
                            // runs; SeriesEpisodes gates on details!=null
                            // so it won't render until the new ones arrive.
                            details = null,
                            detailLoading = true,
                            availability = Availability.AVAILABLE,
                        )
                    }
                    android.util.Log.i(
                        "VodDiag",
                        "precheck swapped $resolvedId → $workingId — reloading details",
                    )
                    // Reload details for the working subjectId so the
                    // episode picker can render. Without this the picker
                    // stayed empty after a swap because state.details still
                    // held the dead subjectId's (empty) season list.
                    runCatching {
                        repo.details(workingId, titleHint = title)
                    }.onSuccess { newD ->
                        val curNow = _state.value
                        if (curNow.detailItem?.subjectId == workingId &&
                            curNow.screen == Screen.DETAIL
                        ) {
                            _state.update {
                                it.copy(details = newD, detailLoading = false)
                            }
                            // Enumerate episodes for the working id too —
                            // the original enumeration ran against the
                            // dead id, so the picker would show phantom
                            // episode counts without this re-run.
                            enumerateEpisodesInBackground(workingId, newD)
                        }
                    }.onFailure {
                        _state.update { it.copy(detailLoading = false) }
                    }
                } else {
                    _state.update {
                        it.copy(
                            availability =
                                if (workingId != null) Availability.AVAILABLE
                                else Availability.UNAVAILABLE,
                        )
                    }
                }
            }
        }
    }

    /** From a verified-unavailable Detail, drop into Search with the title. */
    fun pickFromSearch() {
        val item = _state.value.detailItem ?: return
        _state.update {
            it.copy(
                screen = Screen.TABS,
                tab = Tab.SEARCH,
                query = item.title,
                searchType = SubjectType.ALL,
                detailItem = null,
                availability = Availability.UNKNOWN,
            )
        }
        onQuery(item.title)
    }

    fun playMovie() {
        _state.update { it.copy(currentSe = null, currentEp = null) }
        resolve()
    }

    /** Switch playback to (se, ep). Defaults to a fresh start; opt into
     *  resume-from-saved-position with [restoreResume]=true (used by the
     *  episode list inside the details screen where "continue where I
     *  left off" is the obvious expectation). The explicit Next/Prev
     *  buttons leave restoreResume at its default false — tapping Next
     *  has to start the next episode at 00:00, not 10 min in just
     *  because the user happens to have grazed that episode before.
     *  That was the "skip plays 10 mins forward" bug on the SPA + the
     *  in-app Up Next card. */
    fun playEpisode(se: Int, ep: Int, restoreResume: Boolean = false) {
        skipResumeNext = !restoreResume
        _state.update { it.copy(currentSe = se, currentEp = ep) }
        // Mirror to RemoteController *synchronously*, before the resolve
        // coroutine runs. The PlayerScreen LaunchedEffect that used to
        // do this fires on Compose recomposition, which can be a frame
        // (~16 ms) behind the state mutation — and during that frame
        // the SPA polling /api/state shows the OLD episode. Fixing the
        // mirror here means every poll sees the new episode the moment
        // the user tapped Next.
        com.moviebox.tv.remote.RemoteController.updateEpisode(se, ep)
        resolve()
    }

    /** One-shot flag consumed by [resolve] to decide whether to load the
     *  saved watch-history position. Set by [playEpisode] when the caller
     *  explicitly wants a fresh start. */
    @Volatile private var skipResumeNext: Boolean = false

    fun changeQuality(label: String) { quality = label; resolve() }
    fun changeDub(name: String) { dub = name; resolve() }

    /**
     * Player ran into a decoder error (e.g. 1080P HEVC on a soft decoder).
     * Drop one notch and retry. Returns true if a downgrade is possible —
     * the PlayerScreen uses that to decide whether to surface an error.
     */
    fun downgradeQuality(): Boolean {
        val p = _state.value.play ?: return false
        val available = p.qualities.mapNotNull {
            it.label.trimEnd('P', 'p').toIntOrNull()
        }.distinct().sorted()
        val current = p.selected.trimEnd('P', 'p').toIntOrNull() ?: return false
        val next = available.filter { it < current }.maxOrNull() ?: return false
        quality = "${next}P"
        resolve()
        return true
    }

    fun nextEpisode() {
        val s = _state.value
        val seasons = s.details?.seasons ?: return
        val se = s.currentSe ?: return
        val ep = s.currentEp ?: return
        val cur = seasons.firstOrNull { it.season == se } ?: return
        val next = if (ep < cur.episodes) se to (ep + 1)
        else seasons.getOrNull(seasons.indexOfFirst { it.season == se } + 1)
            ?.let { it.season to 1 }
        next?.let { playEpisode(it.first, it.second) }
    }

    /** Tear down + recreate the LiveStreamProxy + drop ExoPlayer state.
     *  Called by the SPA's "Restart playback" button when the device has
     *  been on a single playback long enough that the live subsystem has
     *  silently degraded (NanoHTTPD socket pile-up, refresh coroutines
     *  stuck, etc). Cheaper than a full force-stop because it leaves the
     *  user on the same screen with state intact. */
    fun resetLivePlayback() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { liveProxy.stop() }
            runCatching { liveProxy.start() }
            android.util.Log.w(
                "LiveDiag",
                "VM resetLivePlayback — proxy bounced",
            )
        }
        liveResolveFailures = 0
        lastResolveFailureAt = 0L
        _state.update { it.copy(useLiveWebPlayer = false) }
    }

    fun prevEpisode() {
        val s = _state.value
        val seasons = s.details?.seasons ?: return
        val se = s.currentSe ?: return
        val ep = s.currentEp ?: return
        val prev = if (ep > 1) se to (ep - 1)
        else {
            val idx = seasons.indexOfFirst { it.season == se }
            if (idx <= 0) null
            else seasons[idx - 1].let { it.season to it.episodes.coerceAtLeast(1) }
        }
        prev?.let { playEpisode(it.first, it.second) }
    }

    fun toggleFavourite(item: Item) {
        viewModelScope.launch {
            if (favouriteIds.value.contains(item.subjectId)) {
                favDao.remove(item.subjectId)
            } else {
                favDao.add(FavouriteEntity.from(item, System.currentTimeMillis()))
            }
        }
    }

    fun removeFavourite(subjectId: String) {
        viewModelScope.launch { favDao.remove(subjectId) }
    }

    fun openRemote() =
        _state.update { it.copy(showRemote = true, suggestRemote = false) }

    fun closeRemote() = _state.update { it.copy(showRemote = false) }

    fun openSettings() = _state.update { it.copy(showSettings = true) }
    fun closeSettings() = _state.update { it.copy(showSettings = false) }

    fun dismissSuggestion() = _state.update { it.copy(suggestRemote = false) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Push a user-visible error message — shows the existing top banner.
     *  Used by PlayerScreen when a live HLS stream fatally errors. */
    fun surfaceError(message: String) = _state.update { it.copy(error = message) }

    private var dpadCount = 0
    private var lastSuggestAt = 0L

    /** Called on D-pad input (TV remote). Occasionally suggests the phone remote. */
    fun onDpadUsed() {
        dpadCount++
        val now = System.currentTimeMillis()
        if (dpadCount >= 8 && now - lastSuggestAt > 180_000 &&
            !_state.value.showRemote
        ) {
            dpadCount = 0
            lastSuggestAt = now
            _state.update { it.copy(suggestRemote = true) }
        }
    }

    /** Play directly from the mobile remote (by subjectId). */
    fun remotePlay(
        subjectId: String, title: String, coverUrl: String?, type: Int,
        season: Int?, episode: Int?, year: Int? = null,
    ) {
        // Flip the TV INSTANTLY to a "loading the new pick" state. Previously
        // we left `play` populated from whatever the TV was already showing,
        // so the old movie kept playing in the foreground while the new
        // title resolved async — user perception: the new pick opens "in
        // the background". Same cleanup openItem() does for the direct path.
        // Remember cover early so even if the play call fails mid-flight,
        // a re-open via history still has the poster.
        coverUrl?.takeIf { it.isNotBlank() }?.let { knownCovers[subjectId] = it }
        _state.update {
            it.copy(
                screen = Screen.PLAYER,
                play = null,
                playLoading = true,
                resumeMs = 0L,
                detailItem = Item(
                    subjectId, title, SubjectType.fromCode(type), year, null,
                    coverUrl ?: knownCovers[subjectId], 0,
                ),
                details = null,
                currentSe = season,
                currentEp = episode,
                error = null,
                // Wipe live-TV transient state so a movie-after-channel
                // hand-off doesn't carry over the WebView fallback flag.
                currentLiveChannel = null,
                useLiveWebPlayer = false,
            )
        }
        viewModelScope.launch {
            pendingTmdbId =
                if (subjectId.startsWith("tmdb:")) subjectId else null
            // TMDB-browsed picks need to be resolved to an aoneroom subjectId.
            val effectiveId = if (subjectId.startsWith("tmdb:")) {
                val isSeries = subjectId.startsWith("tmdb:tv")
                val match = runCatching {
                    repo.resolveByTitle(title, year, isSeries)
                }.getOrNull()
                if (match == null) {
                    // Mark this TMDB pick as un-bridgeable so the recommendations
                    // row stops surfacing it on the next refresh — the "Supergirl
                    // → couldn't auto-match" loop the user kept hitting was the
                    // same item bubbling back to the top each time. Same as
                    // openItem: don't strand the user. Push the TV to Search
                    // with the title pre-filled so they can pick.
                    UnavailableCatalog.mark(subjectId)
                    _state.update {
                        it.copy(
                            screen = Screen.TABS,
                            tab = Tab.SEARCH,
                            query = title,
                            searchType = SubjectType.ALL,
                            playLoading = false,
                            error = "Couldn't auto-match \"$title\" — " +
                                "pick the right entry from search.",
                        )
                    }
                    onQuery(title)
                    return@launch
                }
                match.subjectId
            } else subjectId

            this@MainViewModel.subjectId = effectiveId
            dub = "Original"
            quality = DEFAULT_QUALITY
            // Re-stamp detailItem with the bridge's effective subjectId now
            // that we know it, so downstream code (favourites, history) keys
            // off the aoneroom id rather than the bare tmdb: prefix.
            _state.update {
                it.copy(
                    detailItem = Item(
                        effectiveId, title, SubjectType.fromCode(type), year, null,
                        coverUrl, 0,
                    ),
                )
            }
            if (season != null) {
                runCatching { repo.details(effectiveId, titleHint = title) }
                    .onSuccess { d ->
                        _state.update { it.copy(details = d) }
                        enumerateEpisodesInBackground(effectiveId, d)
                    }
            }
            resolve()
        }
    }

    fun back() {
        _state.update {
            when (it.screen) {
                Screen.PLAYER -> when {
                    it.play?.isLive == true -> {
                        liveResolveFailures = 0
                        lastResolveFailureAt = 0L
                        com.moviebox.tv.debug.Telemetry.onPlayStopped()
                        it.copy(
                            screen = Screen.TABS, tab = Tab.LIVE,
                            play = null,
                            currentLiveChannel = null,
                            useLiveWebPlayer = false,
                        )
                    }
                    // VOD: ANY detailItem means we have a show/movie to fall
                    // back to. Going to DETAIL even when details/currentSe
                    // are still loading lets the user reach the season/
                    // episode picker that this VM lazy-loads in the
                    // background. Previously this required currentSe OR
                    // details — for a Continue Watching resume of a series
                    // whose history row had season=0 (movies, but also old
                    // pre-v0.1.83 rows where season was lost), currentSe
                    // came back null and details hadn't loaded yet, so back
                    // dumped the user on the Home tab. Now we trust the
                    // detailItem alone — DetailScreen calls openItem when
                    // it mounts without details, which fills them in.
                    it.detailItem != null ->
                        it.copy(screen = Screen.DETAIL, play = null)
                    else -> it.copy(screen = Screen.TABS, play = null)
                }
                Screen.DETAIL -> it.copy(screen = Screen.TABS)
                Screen.TABS -> it
            }
        }
    }

    /** When the playing content last switched (episode/movie change). Used
     *  to suppress the stale "tail" progress save: during a switch the OLD
     *  player can fire one last onProgress with its near-end position AFTER
     *  state.play has already flipped to the NEW item, which would write the
     *  old position under the new item's key and make it resume mid-way.
     *  Saves within [CONTENT_SWITCH_SUPPRESS_MS] of a switch are dropped. */
    @Volatile private var contentSwitchAtMs = 0L

    private fun resolve() {
        contentSwitchAtMs = android.os.SystemClock.elapsedRealtime()
        _state.update { it.copy(playLoading = true, error = null, screen = Screen.PLAYER) }
        viewModelScope.launch {
            runCatching {
                repo.resolvePlay(
                    subjectId = subjectId, resolution = quality,
                    season = _state.value.currentSe, episode = _state.value.currentEp,
                    dub = dub,
                    // Hint lets the H5 lookup search by title when no
                    // detailPath is cached (e.g. resume from history,
                    // direct deep-link). Without it we'd land on the wrong
                    // detailPath and play the wrong movie.
                    titleHint = _state.value.detailItem?.title,
                )
            }.onSuccess { p ->
                pendingTmdbId = null
                quality = p.selected
                val key = WatchHistoryEntity.keyOf(
                    subjectId, _state.value.currentSe, _state.value.currentEp,
                )
                // Honour the one-shot "fresh start" request from
                // playEpisode(restoreResume=false). Consumed here so the
                // next ordinary resolve (quality/dub change) still gets
                // the saved position.
                val skipNow = skipResumeNext
                val resume = if (skipNow) 0L
                else watchDao.positionOf(key) ?: 0L
                skipResumeNext = false
                android.util.Log.i(
                    "VodDiag",
                    "resolve S${_state.value.currentSe}E${_state.value.currentEp} " +
                        "skipResume=$skipNow resume=${resume}ms",
                )
                _state.update {
                    it.copy(play = p, playLoading = false, resumeMs = resume)
                }
                com.moviebox.tv.debug.Telemetry.onPlayStart(
                    kind = "vod", title = p.title,
                )
            }.onFailure { e ->
                // If this play came from a TMDB-bridged item, the bridge picked
                // an aoneroom title but the stream is broken/unavailable —
                // remember the TMDB id is dead and bounce out gracefully.
                pendingTmdbId?.let { UnavailableCatalog.mark(it) }
                pendingTmdbId = null
                val cur = _state.value
                val fallback = when {
                    cur.detailItem != null -> Screen.DETAIL
                    else -> Screen.TABS
                }
                _state.update {
                    it.copy(
                        playLoading = false,
                        play = null,
                        screen = fallback,
                        error = e.message,
                    )
                }
            }
        }
    }

    /** Called periodically by the player to persist resume position. */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= 0) return
        // Drop saves that land right after a content switch — these are the
        // OLD player's final onProgress firing its near-end position while
        // state.play has already moved to the NEW item, which would corrupt
        // the new item's resume position (the "next episode starts from the
        // middle / skips minutes in" bug). The new item's own saves resume
        // a few seconds later once its real position is flowing.
        if (android.os.SystemClock.elapsedRealtime() - contentSwitchAtMs
            < CONTENT_SWITCH_SUPPRESS_MS
        ) return
        val s = _state.value
        val play = s.play ?: return
        val item = s.detailItem
        // BUG FIX: use play.season/episode (what's actually playing) rather
        // than state.currentSe/Ep. When the user taps the next episode,
        // playEpisode() updates currentSe/Ep IMMEDIATELY, but the previous
        // player's onDispose runs a final saveProgress *after* that — which
        // used to write the old episode's final position under the NEW
        // episode's key, causing the next episode to resume mid-way through.
        val saveSe = if (play.season > 0) play.season else null
        val saveEp = if (play.episode > 0) play.episode else null
        val key = WatchHistoryEntity.keyOf(subjectId, saveSe, saveEp)
        viewModelScope.launch {
            watchDao.upsert(
                WatchHistoryEntity(
                    key = key,
                    subjectId = subjectId,
                    title = item?.title ?: play.title,
                    // Fall back to the per-subjectId cover map so a re-save
                    // during an auto-advance (when detailItem briefly loses
                    // its cover) doesn't write null and erase the poster on
                    // Continue Watching.
                    coverUrl = item?.coverUrl ?: knownCovers[subjectId],
                    type = item?.type?.code ?: 0,
                    season = play.season,
                    episode = play.episode,
                    episodeTitle = play.episodeTitle,
                    dub = dub,
                    quality = quality,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                    genres = item?.genres?.joinToString(",").orEmpty(),
                    year = item?.year,
                )
            )
        }
    }

    /** Resume a Continue-Watching entry straight into the player. */
    fun resumeFrom(h: WatchHistoryEntity) {
        subjectId = h.subjectId
        dub = h.dub
        quality = h.quality.ifBlank { DEFAULT_QUALITY }
        _state.update {
            it.copy(
                detailItem = Item(
                    subjectId = h.subjectId, title = h.title,
                    type = SubjectType.fromCode(h.type), year = h.year,
                    rating = null, coverUrl = h.coverUrl, seasonCount = 0,
                    genres = h.genres.split(",").map { g -> g.trim() }
                        .filter { g -> g.isNotEmpty() },
                ),
                currentSe = h.season.takeIf { it > 0 },
                currentEp = h.episode.takeIf { it > 0 },
            )
        }
        // Load details too so autoplay-next works for series.
        if (h.season > 0) {
            viewModelScope.launch {
                runCatching { repo.details(h.subjectId, titleHint = h.title) }
                    .onSuccess { d ->
                        _state.update { it.copy(details = d) }
                        enumerateEpisodesInBackground(h.subjectId, d)
                    }
            }
        }
        resolve()
    }

    // -- downloads ---------------------------------------------------------

    fun downloadMovie(item: Item) = enqueueDownload(item, null, null)

    fun downloadEpisode(item: Item, season: Int, episode: Int) =
        enqueueDownload(item, season, episode)

    fun downloadSeason(item: Item, season: Int, episodes: Int) {
        for (ep in 1..episodes) enqueueDownload(item, season, ep)
    }

    private fun enqueueDownload(item: Item, season: Int?, episode: Int?) {
        viewModelScope.launch {
            val key = WatchHistoryEntity.keyOf(item.subjectId, season, episode)
            if (downloadDao.byKey(key) != null) return@launch
            val play = runCatching {
                repo.resolvePlay(item.subjectId, DEFAULT_QUALITY, season, episode, "Original", titleHint = item.title)
            }.getOrNull() ?: return@launch

            val app = getApplication<android.app.Application>()
            val dir = File(app.filesDir, "downloads")
            val file = File(dir, "${key.replace('|', '_')}.mp4")
            val subs = JSONArray()
            play.captions.forEach {
                subs.put(
                    JSONObject().put("code", it.code).put("name", it.name)
                        .put("url", it.url)
                )
            }
            downloadDao.upsert(
                DownloadEntity(
                    key = key, subjectId = item.subjectId, title = item.title,
                    episodeTitle = play.episodeTitle, coverUrl = item.coverUrl,
                    type = item.type.code, season = play.season,
                    episode = play.episode, quality = play.selected, dub = "Original",
                    mediaUrl = play.mediaUrl, subtitleJson = subs.toString(),
                    filePath = file.absolutePath, totalBytes = 0, downloadedBytes = 0,
                    status = DownloadStatus.QUEUED.name,
                    createdAt = System.currentTimeMillis(),
                    genres = item.genres.joinToString(","), year = item.year,
                )
            )
            DownloadService.start(app)
        }
    }

    fun removeDownload(entity: DownloadEntity) {
        viewModelScope.launch {
            downloadDao.remove(entity.key)
            runCatching {
                File(entity.filePath).delete()
                val arr = JSONArray(entity.subtitleJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.has("path")) File(o.getString("path")).delete()
                }
            }
        }
    }

    /** Play a completed download from local storage (works fully offline). */
    fun playDownload(entity: DownloadEntity) {
        subjectId = entity.subjectId
        dub = entity.dub
        quality = entity.quality
        val captions = mutableListOf<CaptionTrack>()
        runCatching {
            val arr = JSONArray(entity.subtitleJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val loc = if (o.has("path")) "file://${o.getString("path")}"
                else o.optString("url")
                captions.add(CaptionTrack(o.getString("code"), o.getString("name"), loc))
            }
        }
        val localUrl = "file://${entity.filePath}"
        val play = PlayInfo(
            title = entity.title, mediaUrl = localUrl, selected = entity.quality,
            qualities = listOf(Quality(entity.quality, localUrl)),
            captions = captions, dubs = emptyList(), selectedDub = entity.dub,
            season = entity.season, episode = entity.episode,
            episodeTitle = entity.episodeTitle, durationSec = 0,
        )
        viewModelScope.launch {
            val resume = watchDao.positionOf(
                WatchHistoryEntity.keyOf(
                    entity.subjectId, entity.season.takeIf { it > 0 },
                    entity.episode.takeIf { it > 0 },
                )
            ) ?: 0L
            _state.update {
                it.copy(
                    detailItem = Item(
                        entity.subjectId, entity.title,
                        SubjectType.fromCode(entity.type), entity.year, null,
                        entity.coverUrl, 0,
                    ),
                    currentSe = entity.season.takeIf { it > 0 },
                    currentEp = entity.episode.takeIf { it > 0 },
                    play = play, screen = Screen.PLAYER, playLoading = false,
                    resumeMs = resume,
                )
            }
        }
    }

    fun removeHistory(key: String) {
        viewModelScope.launch { watchDao.remove(key) }
    }

    fun clearHistory() {
        viewModelScope.launch { watchDao.clear() }
    }
}

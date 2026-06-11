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

// ---- Live-stream long-haul resilience tunables ----
/** Re-resolve the stream URL this often while a channel is playing. Chosen
 *  to be safely below the donis ~60-minute token expiry so the swap lands
 *  inside the still-valid window and ExoPlayer never sees a 401/403/410. */
private const val REFRESH_INTERVAL_MS: Long = 50L * 60 * 1000   // 50 minutes
/** Base backoff between failed re-resolves. Doubles each attempt up to the
 *  ceiling. 30 s is short enough that intermittent Wi-Fi drops feel snappy
 *  and long enough that we don't hammer donis if it's actually down. */
private const val BACKOFF_BASE_MS: Long = 30_000
private const val BACKOFF_MAX_SHIFT: Int = 4   // 30s * 2^4 = 8 min
private const val BACKOFF_CEILING_MS: Long = 8L * 60 * 1000   // 8 minutes
/** How many re-resolve failures before we conclude the native HLS path is
 *  truly broken and try the WebView iframe fallback. With a 30s base,
 *  this is roughly 10 minutes of trying before giving up. */
private const val MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW: Int = 6

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
    /** Channel whose stream we're currently trying to play. Used by the
     *  PlayerScreen to spin up the WebView fallback when direct HLS fails. */
    val currentLiveChannel: Channel? = null,
    /** When true, render LiveWebPlayer instead of the native ExoPlayer for
     *  the active live stream. Flipped by [MainViewModel.fallbackToWebPlayer]
     *  after the HLS path errors. */
    val useLiveWebPlayer: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository()
    private val liveRepo = LiveTvRepository()
    private val liveResolver = LiveResolver()
    private val db = AppDatabase.get(app)
    private val favDao = db.favourites()
    private val watchDao = db.watchHistory()
    private val downloadDao = db.downloads()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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

    val continueWatching: StateFlow<List<WatchHistoryEntity>> =
        watchDao.continueWatching()
            .map { list ->
                val deny = TastePrefs.denyLanguages()
                list.filter { Repository.keepByLanguage(it.title, deny) }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val downloads: StateFlow<List<DownloadEntity>> =
        downloadDao.all()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    init { loadHome() }

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
        if (tab == Tab.LIVE && _state.value.liveChannels.isEmpty()) loadLive()
    }

    // ---- Live TV ----

    fun loadLive(force: Boolean = false) {
        if (_state.value.liveLoading) return
        _state.update { it.copy(liveLoading = true, liveError = null) }
        viewModelScope.launch {
            runCatching {
                val channels = liveRepo.channels(force = force)
                val schedule = liveRepo.schedule(force = force)
                channels to schedule
            }.onSuccess { (c, s) ->
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
    /** Coroutine job for the periodic "refresh-before-token-expires" loop.
     *  Cancelled when the user leaves the channel (back() / new channel /
     *  new VOD selection). */
    private var liveRefreshJob: kotlinx.coroutines.Job? = null

    /** How many consecutive re-resolve attempts have failed for the current
     *  channel. Resets on a successful resolve. Used by backoff scheduler. */
    private var liveResolveFailures = 0

    fun playChannel(ch: Channel) {
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
                playLoading = true,
                resumeMs = 0L,
                detailItem = null,
                details = null,
                currentSe = null, currentEp = null,
                error = null,
                currentLiveChannel = ch,
                useLiveWebPlayer = false,
            )
        }
        liveResolveFailures = 0
        viewModelScope.launch {
            // Try the resolver first — gives us a URL signed for THIS
            // device's WAN egress IP. Falls back to the catalog cache only
            // if resolution truly failed.
            val resolved = runCatching { liveResolver.resolveStream(ch.id) }
                .getOrNull()
            val finalUrl = resolved ?: ch.streamUrl ?: ""
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
                    // User already moved on — drop the resolution.
                    s
                } else {
                    s.copy(
                        play = s.play?.copy(mediaUrl = finalUrl),
                        playLoading = false,
                    )
                }
            }
            // Start the long-haul refresh loop. Cancelled when we leave
            // this channel.
            startLiveRefreshLoop(ch)
        }
    }

    /** Long-haul refresh loop: every ~50 minutes, re-resolve the stream URL
     *  in the background and seamlessly swap it into play.mediaUrl so the
     *  current ~60-minute token is replaced *before* the CDN starts
     *  returning 401/403/410. ExoPlayer reloads the HLS source from the
     *  new URL transparently.
     *
     *  Designed so a user can leave a channel on for hours/days without
     *  ever seeing a "Couldn't connect" bounce — the resolver runs out of
     *  band, hands ExoPlayer a fresh URL well before the buffer runs dry. */
    private fun startLiveRefreshLoop(ch: Channel) {
        liveRefreshJob?.cancel()
        liveRefreshJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(REFRESH_INTERVAL_MS)
                if (_state.value.currentLiveChannel?.id != ch.id) return@launch
                runCatching { liveResolver.resolveStream(ch.id) }
                    .getOrNull()
                    ?.let { fresh ->
                        _state.update { s ->
                            if (s.currentLiveChannel?.id != ch.id) s
                            else s.copy(play = s.play?.copy(mediaUrl = fresh))
                        }
                    }
            }
        }
    }

    /** PlayerScreen calls this when the native HLS player hits an
     *  InvalidResponseCodeException 401/403/410 — overwhelmingly the
     *  token-expired case after a long session. Re-resolves the URL and
     *  swaps it; if the resolve fails, schedules a backoff retry.
     *
     *  Returns true if a refresh attempt was queued — the caller can use
     *  that to suppress the WebView fallback in the meantime. */
    fun refreshLiveStream(): Boolean {
        val ch = _state.value.currentLiveChannel ?: return false
        viewModelScope.launch {
            val resolved = runCatching { liveResolver.resolveStream(ch.id) }
                .getOrNull()
            if (_state.value.currentLiveChannel?.id != ch.id) return@launch
            if (resolved != null) {
                liveResolveFailures = 0
                _state.update { s ->
                    s.copy(play = s.play?.copy(mediaUrl = resolved))
                }
            } else {
                liveResolveFailures += 1
                // Backoff before next attempt. The user sees the existing
                // "Reconnecting…" pill while we retry; no bounce.
                val backoffMs = minOf(
                    BACKOFF_BASE_MS * (1L shl (liveResolveFailures - 1)
                        .coerceAtMost(BACKOFF_MAX_SHIFT)),
                    BACKOFF_CEILING_MS,
                )
                kotlinx.coroutines.delay(backoffMs)
                if (_state.value.currentLiveChannel?.id != ch.id) return@launch
                refreshLiveStream()   // tail-recurse, but coroutine-safe
            }
        }
        return true
    }

    /** Truly-last-resort fallback. Only called after refreshLiveStream has
     *  failed [MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW] times in a row, which
     *  for the default 30-second base means ~10 minutes of unable-to-resolve
     *  before we give up on the native player and try the iframe pages. */
    fun fallbackToWebPlayer() {
        if (_state.value.currentLiveChannel == null) return
        _state.update { it.copy(useLiveWebPlayer = true) }
    }

    /** Used by PlayerScreen to decide whether to fall back. */
    fun shouldFallbackToWebPlayer(): Boolean =
        liveResolveFailures >= MAX_RESOLVE_FAILURES_BEFORE_WEBVIEW

    /** From a schedule chip — look up the channel by id and play it. */
    fun playScheduleChannel(channelId: String) {
        val ch = _state.value.liveChannels.firstOrNull { it.id == channelId } ?: return
        playChannel(ch)
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
                    // Couldn't auto-match (e.g. aoneroom uses an abbreviation
                    // we can't reach by string similarity). Push the user into
                    // Search with the title pre-filled — they can pick the
                    // right record themselves. We do NOT mark the id as dead
                    // here, since the title may well exist under a different
                    // form.
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
            runCatching { repo.details(resolvedId) }
                .onSuccess { d ->
                    _state.update { it.copy(details = d, detailLoading = false) }
                    // Verify in the background — the Play button reflects the
                    // result before the user even reaches for it.
                    precheckPlayback(resolvedId, d)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(detailLoading = false, error = e.message)
                    }
                }
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
            val s1 = d.seasons.firstOrNull()?.season
            val result = runCatching {
                repo.resolvePlay(
                    subjectId = resolvedId,
                    resolution = quality,
                    season = if (d.isSeries) s1 else null,
                    episode = if (d.isSeries) 1 else null,
                    dub = dub,
                )
            }
            val ok = result.isSuccess &&
                (result.getOrNull()?.mediaUrl?.isNotBlank() == true)
            // Be careful not to overwrite the user's state if they've moved on.
            val cur = _state.value
            if (cur.detailItem?.subjectId == resolvedId &&
                cur.screen == Screen.DETAIL
            ) {
                _state.update {
                    it.copy(
                        availability =
                            if (ok) Availability.AVAILABLE
                            else Availability.UNAVAILABLE,
                    )
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

    fun playEpisode(se: Int, ep: Int) {
        _state.update { it.copy(currentSe = se, currentEp = ep) }
        resolve()
    }

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
        _state.update {
            it.copy(
                screen = Screen.PLAYER,
                play = null,
                playLoading = true,
                resumeMs = 0L,
                detailItem = Item(
                    subjectId, title, SubjectType.fromCode(type), year, null,
                    coverUrl, 0,
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
                    // Same as openItem: don't strand the user. Push the TV to
                    // Search with the title pre-filled so they can pick.
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
                runCatching { repo.details(effectiveId) }
                    .onSuccess { d -> _state.update { it.copy(details = d) } }
            }
            resolve()
        }
    }

    fun back() {
        _state.update {
            when (it.screen) {
                Screen.PLAYER -> when {
                    it.play?.isLive == true -> {
                        // Leaving a live channel cancels the long-haul
                        // refresh loop and clears the failure counter.
                        liveRefreshJob?.cancel()
                        liveRefreshJob = null
                        liveResolveFailures = 0
                        it.copy(
                            screen = Screen.TABS, tab = Tab.LIVE,
                            play = null,
                            currentLiveChannel = null,
                            useLiveWebPlayer = false,
                        )
                    }
                    it.details != null || it.currentSe != null ->
                        it.copy(screen = Screen.DETAIL, play = null)
                    else -> it.copy(screen = Screen.TABS, play = null)
                }
                Screen.DETAIL -> it.copy(screen = Screen.TABS)
                Screen.TABS -> it
            }
        }
    }

    private fun resolve() {
        _state.update { it.copy(playLoading = true, error = null, screen = Screen.PLAYER) }
        viewModelScope.launch {
            runCatching {
                repo.resolvePlay(
                    subjectId = subjectId, resolution = quality,
                    season = _state.value.currentSe, episode = _state.value.currentEp,
                    dub = dub,
                )
            }.onSuccess { p ->
                pendingTmdbId = null
                quality = p.selected
                val key = WatchHistoryEntity.keyOf(
                    subjectId, _state.value.currentSe, _state.value.currentEp,
                )
                val resume = watchDao.positionOf(key) ?: 0L
                _state.update {
                    it.copy(play = p, playLoading = false, resumeMs = resume)
                }
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
        val s = _state.value
        val play = s.play ?: return
        val item = s.detailItem
        val key = WatchHistoryEntity.keyOf(subjectId, s.currentSe, s.currentEp)
        viewModelScope.launch {
            watchDao.upsert(
                WatchHistoryEntity(
                    key = key,
                    subjectId = subjectId,
                    title = item?.title ?: play.title,
                    coverUrl = item?.coverUrl,
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
                runCatching { repo.details(h.subjectId) }
                    .onSuccess { d -> _state.update { it.copy(details = d) } }
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
                repo.resolvePlay(item.subjectId, DEFAULT_QUALITY, season, episode, "Original")
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

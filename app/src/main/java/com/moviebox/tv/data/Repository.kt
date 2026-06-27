package com.moviebox.tv.data

import com.moviebox.tv.data.dto.ApiResponse
import com.moviebox.tv.data.dto.DubDto
import com.moviebox.tv.data.dto.SearchRequest
import com.moviebox.tv.data.dto.Subject
import com.moviebox.tv.data.dto.VideoFile
import com.moviebox.tv.data.tmdb.TmdbItemDto
import com.moviebox.tv.data.tmdb.TmdbRepository
import com.moviebox.tv.data.tmdb.toItem
import com.moviebox.tv.net.ApiClient
import com.moviebox.tv.net.MovieBoxApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Turns user intent (search text, subjectId, season/episode, quality, dub) into
 * concrete CDN URLs by orchestrating the signed API. Ported from the Python
 * web resolver, including the "best == actual highest available" fix.
 */
class Repository(
    private val api: MovieBoxApi = ApiClient.api,
    private val tmdb: TmdbRepository = TmdbRepository(),
) {

    /** Per-process cache of completed episode enumerations, keyed by
     *  aoneroom subjectId. The walk is ~19 network round-trips for a long
     *  series; caching means re-opening the same series is instant. Only
     *  successful (complete) walks are cached — see enumerateEpisodes. */
    private val episodeMapCache =
        java.util.concurrent.ConcurrentHashMap<String, Map<Int, List<Int>>>()

    private fun <T> ApiResponse<T>.unwrap(): T {
        if (code == 0 && message == "ok" && data != null) return data
        // "ok" without data means aoneroom acknowledged the request but has
        // nothing — render as "not available" instead of leaking the protocol word.
        val raw = message ?: "Request failed (code=$code)"
        val friendly = if (raw.equals("ok", ignoreCase = true))
            "This title isn't available right now."
        else raw
        throw ApiException(friendly)
    }

    /**
     * Stream home rows from TMDB so the UI paints each row as it arrives — the
     * slowest fetch no longer blocks the whole home. Callbacks fire on the
     * caller's coroutine context.
     */
    suspend fun streamHome(
        onHero: (Hero) -> Unit,
        onRow: (HomeRow) -> Unit,
        onError: (Throwable) -> Unit,
    ): Unit = coroutineScope {
        val deny = TastePrefs.denyLanguages()
        // H5 path first — aoneroom's own server-curated home rows. Every item
        // is real catalog (no TMDB bridging needed) and the hasResource flag
        // filtered at the H5Api layer means we never surface dead titles in
        // browse. Falls back to TMDB if H5 returns nothing — the user can
        // still discover via TMDB while aoneroom is down.
        val h5Rows = runCatching {
            com.moviebox.tv.net.H5Api.home()
        }.getOrDefault(emptyList())
        if (h5Rows.isNotEmpty()) {
            // Heroes: first 5 from the dedicated trending fetch (richer
            // metadata than the banner items in operatingList[0]).
            val trending = runCatching { com.moviebox.tv.net.H5Api.trending() }
                .getOrDefault(emptyList())
                .filter { keepByLanguage(it.title, deny) }
            trending.take(5).forEach { item ->
                onHero(
                    Hero(
                        item = item,
                        backdropUrl = item.backdropUrl ?: item.coverUrl,
                        tagline = item.overview.orEmpty(),
                    )
                )
            }
            if (trending.isNotEmpty()) {
                onRow(HomeRow("🔥 Trending Now", trending.take(14)))
            }
            // Then every server-named row (Popular Movie, Superhero Series,
            // Teen Romance, …) in operatingList order.
            h5Rows.forEach { r ->
                val filtered = r.items
                    .filter { keepByLanguage(it.title, deny) }
                    .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
                    .take(14)
                if (filtered.isNotEmpty()) onRow(HomeRow(r.title, filtered))
            }
            return@coroutineScope
        }
        suspend fun row(
            title: String, isSeries: Boolean? = null,
            fetch: suspend () -> List<TmdbItemDto>,
        ) {
            runCatching {
                fetch()
                    .filter { keepByOriginalLanguage(it.originalLanguage, deny) }
                    .mapNotNull {
                        it.toItem(
                            forceTv = isSeries == true,
                            forceMovie = isSeries == false,
                        )
                    }
                    .filter { keepByLanguage(it.title, deny) }
                    .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
                    .take(14)
            }
                .onSuccess { items -> if (items.isNotEmpty()) onRow(HomeRow(title, items)) }
                .onFailure { onError(it) }
        }
        val trendingJob = async {
            val items = runCatching { tmdb.trendingRaw() }.getOrDefault(emptyList())
                .filter { keepByOriginalLanguage(it.originalLanguage, deny) }
                .mapNotNull { it.toItem() }
                .filter { keepByLanguage(it.title, deny) }
                .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
            items.take(5).forEach { item ->
                onHero(Hero(
                    item = item,
                    backdropUrl = item.backdropUrl ?: item.coverUrl,
                    tagline = item.overview.orEmpty(),
                ))
            }
            if (items.isNotEmpty()) onRow(HomeRow("🔥 Trending Now", items.take(14)))
        }
        val jobs = listOf(
            trendingJob,
            async { row("Popular Movies", isSeries = false) { tmdb.popularMoviesRaw() } },
            async { row("Popular Series", isSeries = true)  { tmdb.popularTvRaw() } },
            async { row("Netflix",     isSeries = true)  {
                tmdb.byNetworkRaw(TmdbRepository.Networks.NETFLIX)
            } },
            async { row("HBO",         isSeries = true)  {
                tmdb.byNetworkRaw(TmdbRepository.Networks.HBO)
            } },
            async { row("Disney+",     isSeries = true)  {
                tmdb.byNetworkRaw(TmdbRepository.Networks.DISNEY_PLUS)
            } },
            async { row("Prime Video", isSeries = true)  {
                tmdb.byNetworkRaw(TmdbRepository.Networks.PRIME)
            } },
            async { row("Apple TV+",   isSeries = true)  {
                tmdb.byNetworkRaw(TmdbRepository.Networks.APPLE_TV)
            } },
        )
        jobs.forEach { it.await() }
    }

    /**
     * Legacy synchronous home — used by [recommendations] which prefers a
     * single snapshot. Internally collects what [streamHome] would emit.
     */
    suspend fun home(): HomeContent = coroutineScope {
        val deny = TastePrefs.denyLanguages()

        val trending      = async { runCatching { tmdb.trending() }.getOrDefault(emptyList()) }
        val popularMovies = async { runCatching { tmdb.popularMovies() }.getOrDefault(emptyList()) }
        val popularTv     = async { runCatching { tmdb.popularTv() }.getOrDefault(emptyList()) }
        val netflix       = async { runCatching {
            tmdb.byNetwork(TmdbRepository.Networks.NETFLIX)
        }.getOrDefault(emptyList()) }
        val hbo           = async { runCatching {
            tmdb.byNetwork(TmdbRepository.Networks.HBO)
        }.getOrDefault(emptyList()) }
        val disney        = async { runCatching {
            tmdb.byNetwork(TmdbRepository.Networks.DISNEY_PLUS)
        }.getOrDefault(emptyList()) }

        val rows = listOf(
            HomeRow("🔥 Trending Now",   trending.await()),
            HomeRow("Popular Movies",              popularMovies.await()),
            HomeRow("Popular Series",              popularTv.await()),
            HomeRow("Netflix",                     netflix.await()),
            HomeRow("HBO",                         hbo.await()),
            HomeRow("Disney+",                     disney.await()),
        )
            .map { r ->
                r.copy(items = r.items.filter { keepByLanguage(it.title, deny) })
            }
            .filter { it.items.isNotEmpty() }

        // Heroes: pick a few visually striking ones from Trending.
        val heroes = trending.await().take(5).map { item ->
            Hero(
                item = item,
                backdropUrl = item.backdropUrl ?: item.coverUrl,
                tagline = item.overview.orEmpty(),
            )
        }

        HomeContent(heroes = heroes, rows = rows)
    }

    suspend fun search(
        keyword: String,
        type: SubjectType = SubjectType.ALL,
        page: Int = 1,
    ): List<Item> {
        // aoneroom's mobile search now returns code=441 "miss token". The H5
        // surface still serves search to guests and returns the same fields
        // we already model — see net/H5Api.kt for the swap rationale.
        val items = try {
            com.moviebox.tv.net.H5Api.search(
                keyword = keyword, page = page, perPage = PER_PAGE, subjectType = type.code,
            )
        } catch (e: Throwable) {
            android.util.Log.w("H5", "search($keyword) failed: ${e.message}", e)
            throw e
        }
        android.util.Log.i("H5", "search($keyword) -> ${items.size} items")
        val deny = TastePrefs.denyLanguages()
        return items
            .filter { keepByLanguage(it.title, deny) }
            // Skip items previously marked unavailable so the user
            // doesn't keep tapping on dead-on-arrival results.
            .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
    }

    /**
     * Map a TMDB-browsed pick to an aoneroom subjectId by title+year+type. Used
     * when the user plays an item picked from the Browse tab.
     */
    /**
     * Bridge a TMDB pick to an aoneroom subjectId by title + year. Layered
     * fallback: try the matching type, then the opposite type, then ALL —
     * aoneroom often classifies titles differently from TMDB (e.g. "Star Wars:
     * The Clone Wars" lives there as a movie even though TMDB also tracks the
     * TV series). Ranking always prefers the requested type when title + year
     * are tied.
     */
    suspend fun resolveByTitle(
        title: String,
        year: Int?,
        isSeries: Boolean,
    ): Item? {
        // Collect candidates across three searches, deduped by subjectId.
        val seen = mutableSetOf<String>()
        val pool = mutableListOf<Item>()
        suspend fun add(type: SubjectType) {
            runCatching { search(title, type) }.getOrDefault(emptyList())
                .forEach { if (seen.add(it.subjectId)) pool.add(it) }
        }
        val primary = if (isSeries) SubjectType.TV_SERIES else SubjectType.MOVIE
        val opposite = if (isSeries) SubjectType.MOVIE else SubjectType.TV_SERIES
        add(primary)
        if (pool.none { it.type.isSeries == isSeries }) add(opposite)
        if (pool.isEmpty()) add(SubjectType.ALL)
        if (pool.isEmpty()) return null

        // Rank: title distance first, then year distance, then matching type.
        val ranked = pool
            .map { c ->
                val td = titleDistance(c.title, title)
                val yd = if (year != null && c.year != null)
                    kotlin.math.abs(c.year - year) else null
                val typeMatch = c.type.isSeries == isSeries
                Quad(c, td, yd, typeMatch)
            }
            .filter { it.td <= 1 }
            .sortedWith(
                compareBy(
                    { it.td },                               // title dist asc
                    { it.yd ?: Int.MAX_VALUE },              // year dist asc
                    { if (it.typeMatch) 0 else 1 },          // matching type first
                )
            )
        val pick = ranked.firstOrNull() ?: return null
        // Reject only if BOTH years are known AND they're way off.
        if (year != null && pick.yd != null && pick.yd!! > 3) return null
        return pick.candidate
    }

    private data class Quad(
        val candidate: Item,
        val td: Int,
        val yd: Int?,
        val typeMatch: Boolean,
    )

    private fun titleDistance(a: String, b: String): Int {
        // Normalise: strip brackets, year suffixes, common articles.
        val na = normaliseTitle(a)
        val nb = normaliseTitle(b)
        if (na == nb) return 0
        if (na.startsWith(nb) || nb.startsWith(na)) return 1
        // Anything fuzzier than that is likely the wrong title.
        return 5
    }

    private fun normaliseTitle(s: String): String =
        s.lowercase()
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Walk aoneroom's full resource (file) listing for [subjectId] and
     * return the REAL episode numbers present per season, keyed by
     * season number. This is the authoritative answer to "which
     * episodes actually exist" — seasonInfo.maxEp lies (it's frequently
     * 1-2 higher than the real count, which is what surfaces as phantom
     * trailing episodes that fail to play).
     *
     * Dedupes across resolutions (the same episode appears once per
     * available resolution in the listing). Returns null if the walk
     * couldn't complete — a network error mid-walk, or a listing longer
     * than [MAX_ENUM_PAGES] pages — so callers fall back to maxEp rather
     * than risk hiding real episodes on incomplete data.
     */
    /** Peek the enumerated-episode cache WITHOUT triggering the ~19-call walk.
     *  The phone's episode picker uses this so a request never blocks on a
     *  cold enumeration — it falls back to the declared maxEp instead. The
     *  cache is warmed by enumerateEpisodesInBackground() at play time, so the
     *  in-playback picker usually gets the real, phantom-free list. */
    fun cachedEpisodes(subjectId: String): Map<Int, List<Int>>? =
        episodeMapCache[subjectId]

    suspend fun enumerateEpisodes(subjectId: String): Map<Int, List<Int>>? =
        coroutineScope {
            episodeMapCache[subjectId]?.let { return@coroutineScope it }
            val bucket = sortedMapOf<Int, MutableSet<Int>>()
            var page = 1
            var walked = 0
            while (true) {
                val data = runCatching {
                    api.resource(
                        subjectId,
                        resolution = ENUM_RESOLUTION_HINT,
                        page = page,
                        perPage = ENUM_PER_PAGE,
                    ).unwrap()
                }.onFailure {
                    android.util.Log.w("EpisodeEnum",
                        "page=$page resource() threw: ${it.message}")
                }.getOrNull() ?: return@coroutineScope null  // network fail
                for (f in data.list) {
                    if (f.se > 0 && f.ep > 0) {
                        bucket.getOrPut(f.se) { sortedSetOf() }.add(f.ep)
                    }
                }
                walked++
                val pager = data.pager
                if (pager == null || !pager.hasMore) break
                if (walked >= MAX_ENUM_PAGES) return@coroutineScope null  // truncated
                page = pager.nextPage.takeIf { it > 0 } ?: (page + 1)
            }
            if (bucket.isEmpty()) {
                null
            } else {
                val result = bucket.mapValues { (_, eps) -> eps.sorted() }
                episodeMapCache[subjectId] = result
                result
            }
        }

    suspend fun details(subjectId: String, titleHint: String? = null): Details {
        // H5 path: the legacy mobile itemDetails returns 441 "miss token". We
        // get every field the old path returned (title, description, year,
        // seasons, dubs) from the H5 /detail endpoint — same data, different
        // host. If we don't already have a detailPath cached from search,
        // search by title to find one (detail-rec returns recommendations,
        // not the subject itself, so it's not usable as a lookup).
        val dp = com.moviebox.tv.net.H5Api.detailPathFor(subjectId)
            ?: com.moviebox.tv.net.H5Api.lookupDetailPath(subjectId, titleHint)
        val h5 = dp?.let { com.moviebox.tv.net.H5Api.detail(it) }
        if (h5 != null) {
            val type = if (h5.isSeries) SubjectType.TV_SERIES else SubjectType.MOVIE
            // Prefer the enumerated episode list when it has already
            // completed; otherwise the H5 detail call's `resource.seasons`
            // (which mirrors the old mobile seasonInfo's `maxEp` + resolutions)
            // is good enough for the picker to render.
            val seasons = if (h5.isSeries) {
                val enumerated = runCatching { enumerateEpisodes(subjectId) }.getOrNull()
                if (!enumerated.isNullOrEmpty()) {
                    enumerated.entries.sortedBy { it.key }.map { (se, eps) ->
                        val h5se = h5.seasons.firstOrNull { it.season == se }
                        SeasonInfo(
                            season = se, episodes = eps.size,
                            resolutions = h5se?.resolutions ?: emptyList(),
                            realEpisodes = eps,
                        )
                    }
                } else {
                    h5.seasons.map {
                        SeasonInfo(season = it.season, episodes = it.maxEp, resolutions = it.resolutions)
                    }
                }
            } else emptyList()
            val dubs = h5.dubs.map {
                Dub(name = mapDubName(it.name), code = it.code, original = it.original)
            }
            return Details(
                subjectId = subjectId,
                title = h5.title,
                type = type,
                description = h5.description,
                year = h5.year,
                isSeries = h5.isSeries,
                seasons = seasons,
                dubs = dubs,
            )
        }
        // Mobile path — try, but never throw the 441 cascade. The player can
        // still resolve a stream via H5Api.play even if details came up empty.
        val d = runCatching { api.itemDetails(subjectId).unwrap() }.getOrNull()
        if (d != null) {
            val type = SubjectType.fromCode(d.subjectType)
            val seasons = if (type.isSeries) {
                runCatching { api.seasonInfo(subjectId).unwrap() }
                    .getOrNull()?.seasons?.map {
                        SeasonInfo(
                            season = it.se,
                            episodes = it.maxEp,
                            resolutions = it.resolutions.map { r -> r.resolution }
                                .distinct().sorted(),
                        )
                    } ?: emptyList()
            } else emptyList()
            return Details(
                subjectId = d.subjectId.ifBlank { subjectId },
                title = d.title,
                type = type,
                description = d.description.orEmpty(),
                year = parseYear(d.releaseDate),
                isSeries = type.isSeries,
                seasons = seasons,
                dubs = d.dubs.map { it.toDomain() },
            )
        }
        // Last resort: a minimal placeholder so the UI doesn't crash.
        return Details(
            subjectId = subjectId, title = "", type = SubjectType.MOVIE,
            description = "", year = null, isSeries = false,
            seasons = emptyList(), dubs = emptyList(),
        )
    }

    suspend fun resolvePlay(
        subjectId: String,
        resolution: String = "best",
        season: Int? = null,
        episode: Int? = null,
        dub: String = "Original",
        titleHint: String? = null,
    ): PlayInfo {
        // Get the real title + dubs from the H5 detail endpoint. The legacy
        // mobile itemDetails returns 441 now, so without this the player
        // showed the raw subjectId number instead of the movie name. Look the
        // detailPath up if we don't already have one cached from search.
        val dp = com.moviebox.tv.net.H5Api.detailPathFor(subjectId)
            ?: com.moviebox.tv.net.H5Api.lookupDetailPath(subjectId, titleHint)
        val h5Detail = dp?.let { com.moviebox.tv.net.H5Api.detail(it) }

        val (effectiveId, selectedDub, detailPath) = run {
            val matchDub = h5Detail?.dubs?.let { dubs ->
                if (dubs.isEmpty()) null
                else {
                    val byCode = dubs.firstOrNull { it.code.equals(dub, true) }
                    val byName = dubs.firstOrNull { it.name.equals(dub, true) }
                    val original = dubs.firstOrNull { it.original }
                    byCode ?: byName ?: original ?: dubs.first()
                }
            }
            if (matchDub != null) {
                Triple(matchDub.subjectId, mapDubName(matchDub.name), matchDub.detailPath.ifBlank { dp ?: "" })
            } else {
                Triple(subjectId, "Original", dp ?: com.moviebox.tv.net.H5Client.syntheticDetailPath(h5Detail?.title ?: subjectId))
            }
        }
        val dubs = h5Detail?.dubs?.map {
            Dub(name = mapDubName(it.name), code = it.code, original = it.original)
        } ?: emptyList()
        // Try the season/episode-specific resource first. For HBO/Netflix-tier
        // titles (House of the Dragon, The Last of Us, etc.) aoneroom's H5
        // surface registers the show under one subjectId but doesn't expose
        // per-episode resource files — the play call with se=1 ep=1 either
        // returns streams=0 OR throws server-side ("invalid season"). The
        // exact same call with se=0 ep=0 returns the subject-level resource
        // (4 quality variants, 3 dubs). That's the playable file the
        // official MovieBox APK hides behind "Download" because its
        // mobile-API surface has those titles gated to download_only; we
        // picked the H5 surface (after the country-code atp:3 bearer unlock
        // in v0.1.89) which doesn't carry that gate. Without this fallback
        // the user sees "Not available — pick from search" on every
        // HBO-tier title even though they're streamable.
        val firstAttempt = runCatching {
            com.moviebox.tv.net.H5Api.play(
                subjectId = effectiveId,
                season = season ?: 0,
                episode = episode ?: 0,
                detailPath = detailPath,
            )
        }
        firstAttempt.onSuccess {
            android.util.Log.i(
                "H5",
                "play($effectiveId, se=$season ep=$episode) streams=${it.streams.size} hasResource=${it.hasResource}",
            )
        }
        firstAttempt.onFailure {
            android.util.Log.w(
                "H5",
                "play($effectiveId, se=$season ep=$episode) failed: ${it.message}",
            )
        }
        // Subject-level fallback ONLY for S1E1. The H5 pattern this exists
        // for is "show registered as one subject-level playable" — so the
        // subject's single resource IS S1E1 by convention. Firing the
        // fallback for ANY (se, ep) that returns 0 streams would silently
        // play S1E1 when the user asked for S2E1 — silent content
        // corruption. Verified on HotD: aoneroom has S1 only; asking for
        // S2E1 must surface "not available", NOT silently swap to S1E1.
        val isS1E1 = (season == null || season == 1) && (episode == null || episode == 1)
        val needsFallback = isS1E1 && (
            firstAttempt.isFailure ||
                (firstAttempt.getOrNull()?.streams?.isEmpty() == true)
        )
        var play = if (needsFallback) {
            // Subject-level fallback. Fires whenever the (se, ep)-specific
            // call EITHER threw OR came back with no streams — both surface
            // as "no episode files" on aoneroom's H5 H5BO-tier titles.
            val fallback = runCatching {
                com.moviebox.tv.net.H5Api.play(
                    subjectId = effectiveId,
                    season = 0, episode = 0,
                    detailPath = detailPath,
                )
            }.getOrNull()
            if (fallback != null && fallback.streams.isNotEmpty()) {
                android.util.Log.i(
                    "H5",
                    "play($effectiveId) subject-level fallback streams=${fallback.streams.size}",
                )
                fallback
            } else {
                firstAttempt.getOrNull()
                    ?: throw (firstAttempt.exceptionOrNull()
                        ?: ApiException("This title isn't available right now."))
            }
        } else {
            firstAttempt.getOrThrow()
        }
        if (play.streams.isEmpty()) {
            throw ApiException("This title isn't available right now.")
        }
        // Pick the best stream the user asked for. "best" → highest resolution.
        val targetRes = resolution.filter { it.isDigit() }.toIntOrNull()
        val sorted = play.streams.sortedByDescending { it.resolution }
        val selectedStream = (targetRes?.let { t -> sorted.firstOrNull { it.resolution == t } })
            ?: sorted.first()
        val qualities = sorted.map { s ->
            Quality(
                label = if (s.resolution > 0) "${s.resolution}P" else s.format,
                mediaUrl = s.url,
            )
        }
        val captions = runCatching {
            api.extCaptions(effectiveId, selectedStream.id).unwrap()
                .extCaptions.map { CaptionTrack(it.lan, it.lanName, it.url) }
        }.getOrDefault(emptyList())

        // Real movie / series name from H5 detail; falls back to the subjectId
        // only as a last resort if the detail endpoint had nothing.
        val displayTitle = h5Detail?.title?.takeIf { it.isNotBlank() } ?: subjectId
        return PlayInfo(
            title = displayTitle,
            mediaUrl = selectedStream.url,
            selected = if (selectedStream.resolution > 0) "${selectedStream.resolution}P"
                       else selectedStream.format,
            qualities = qualities,
            captions = captions,
            dubs = dubs,
            selectedDub = selectedDub,
            season = season ?: 0,
            episode = episode ?: 0,
            episodeTitle = "",
            durationSec = selectedStream.durationSec,
        )
    }

    // -- internals ---------------------------------------------------------

    private suspend fun resolveMovie(
        subjectId: String,
        resolution: String,
    ): Pair<VideoFile, List<VideoFile>> {
        // Movies return every quality in one list regardless of the param.
        val data = api.resource(subjectId, resolution = 1080).unwrap()
        val files = data.list
        if (files.isEmpty()) throw ApiException("No media files for this item.")
        val available = files.map { it.resolution }.distinct().sorted()
        val target = pickResolution(resolution, available)
        val selected = files.firstOrNull { it.resolution == target }
            ?: files.maxByOrNull { it.resolution }!!
        return selected to files
    }

    private suspend fun resolveEpisode(
        subjectId: String,
        resolution: String,
        season: Int,
        episode: Int,
    ): Pair<VideoFile, List<Int>> {
        val seasonsData = api.seasonInfo(subjectId).unwrap()
        val seasons = seasonsData.seasons
        val target = seasons.firstOrNull { it.se == season }
            ?: throw ApiException("Season $season not found.")
        val available =
            target.resolutions.map { it.resolution }.distinct().sorted()
        val concrete = pickResolution(resolution, available)

        // Locate the page + in-page offset for this single episode.
        val before = seasons.filter { it.se < season }.sumOf { it.maxEp }
        val offsetEpisodes = before + (episode - 1)
        val page = offsetEpisodes / PER_PAGE + 1
        val offsetInPage = offsetEpisodes % PER_PAGE

        val data = api.resource(
            subjectId, resolution = concrete, page = page, perPage = PER_PAGE,
        ).unwrap()
        // Match by (se, ep) tag on the file rather than by positional offset
        // — aoneroom sometimes returns multiple resolutions of the SAME
        // episode in the list, which broke positional indexing and ended up
        // pointing at the same big file for every episode (visible to the
        // user as every episode showing the season's total runtime).
        val taggedMatch = data.list.firstOrNull { it.se == season && it.ep == episode }
        val file = taggedMatch
            ?: run {
                // No (se, ep) tag match. Positional fallback is dangerous
                // here — aoneroom's seasonInfo.maxEp counts go stale (see
                // bug report: "series at S3E1 sometimes plays from other
                // seasons"). When the page we computed via offsetEpisodes
                // returns a file whose embedded (se, ep) tag doesn't match
                // what we asked for, we previously silently played it
                // anyway. That's how the user got S1E5 when they meant
                // S3E1. Validate the positional pick instead.
                val positional = data.list.getOrNull(offsetInPage)
                if (positional == null) {
                    MissingEpisodeCatalog.mark(subjectId, season, episode)
                    throw ApiException(
                        "Episode $episode of season $season missing.",
                    )
                }
                val posSe = positional.se
                val posEp = positional.ep
                if (posSe != null && posEp != null &&
                    (posSe != season || posEp != episode)
                ) {
                    MissingEpisodeCatalog.mark(subjectId, season, episode)
                    throw ApiException(
                        "Episode $episode of season $season missing " +
                            "(positional fallback picked S${posSe}E${posEp}, " +
                            "refusing to silently play wrong content).",
                    )
                }
                positional
            }
        return file to available
    }

    companion object {
        private const val PER_PAGE = 20

        // --- Episode enumeration (enumerateEpisodes) ---
        /** Resolution arg for the listing walk. aoneroom returns files
         *  across ALL resolutions regardless of this value (it's a max/
         *  hint, not a filter — see resolveMovie), so 1080 just means
         *  "give me everything". We dedupe by (se, ep) anyway. */
        private const val ENUM_RESOLUTION_HINT = 1080
        /** Page size for the walk. The aoneroom resource endpoint 400s on
         *  anything above its default of 20, so 20 it is. */
        private const val ENUM_PER_PAGE = 20
        /** Safety cap. A long-running animated series (Family Guy: ~400
         *  episodes, and the listing returns one row per episode×
         *  resolution) can be large, so allow a generous walk — it's a
         *  one-time background task, cached after. 120 × 20 = 2400 rows.
         *  Beyond that we bail to maxEp rather than walk forever. */
        private const val MAX_ENUM_PAGES = 120

        // Full-name → ISO code so deny rules can be either form.
        private val LANG_ALIAS = mapOf(
            "hindi" to "hi", "tamil" to "ta", "telugu" to "te",
            "malayalam" to "ml", "kannada" to "kn", "bengali" to "bn",
            "urdu" to "ur", "marathi" to "mr", "punjabi" to "pa",
            "english" to "en", "spanish" to "es", "french" to "fr",
            "german" to "de", "japanese" to "ja", "korean" to "ko",
            "chinese" to "zh", "arabic" to "ar", "russian" to "ru",
            "portuguese" to "pt", "italian" to "it",
        )

        /** Keeps a title if no bracketed language tag matches the deny set. */
        fun keepByLanguage(title: String, deny: Set<String>): Boolean {
            if (deny.isEmpty()) return true
            val tags = Regex("[\\[(](\\w+)[\\])]").findAll(title)
                .map { it.groupValues[1].lowercase() }.toList()
            if (tags.isEmpty()) return true
            val denyNorm = deny.map { it.lowercase() }.toSet()
            return tags.none { it in denyNorm || LANG_ALIAS[it] in denyNorm }
        }

        /** Keeps a TMDB item if its original_language is not in the deny set. */
        fun keepByOriginalLanguage(originalLanguage: String?, deny: Set<String>): Boolean {
            if (deny.isEmpty() || originalLanguage.isNullOrBlank()) return true
            return originalLanguage.lowercase() !in deny.map { it.lowercase() }
        }

        fun pickResolution(requested: String, available: List<Int>): Int {
            if (available.isEmpty()) return 360
            return when (requested.lowercase()) {
                "best" -> available.max()
                "worst" -> available.min()
                else -> {
                    val want = requested.trimEnd('P', 'p').toIntOrNull()
                        ?: return available.max()
                    // Prefer the requested; fall back to the next lower; finally
                    // the lowest. Picking the *highest* on miss bites weak
                    // decoders (1080P HEVC on emulators / low-end TV boxes).
                    available.firstOrNull { it == want }
                        ?: available.filter { it < want }.maxOrNull()
                        ?: available.min()
                }
            }
        }

        fun mapDubName(raw: String): String =
            if (raw.lowercase().startsWith("original")) "Original"
            else raw.replace("dub", "", ignoreCase = true).trim()

        private fun pickDub(dubs: List<DubDto>, requested: String): DubDto {
            dubs.firstOrNull {
                mapDubName(it.lanName).equals(requested, true) ||
                    it.lanCode.equals(requested, true)
            }?.let { return it }
            if (requested.equals("Original", true)) {
                return dubs.firstOrNull { it.original } ?: dubs.first()
            }
            throw ApiException("No dub for '$requested'")
        }

        private fun parseYear(date: String?): Int? =
            date?.take(4)?.toIntOrNull()

        private fun coerceRating(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}

private fun DubDto.toDomain(): Dub =
    Dub(
        name = Repository.mapDubName(lanName),
        code = lanCode,
        original = original,
    )

private fun Subject.toItem(): Item = Item(
    subjectId = subjectId,
    title = title,
    type = SubjectType.fromCode(subjectType),
    year = releaseDate?.take(4)?.toIntOrNull(),
    rating = when (val r = imdbRatingValue) {
        is Number -> r.toDouble()
        is String -> r.toDoubleOrNull()
        else -> null
    },
    coverUrl = cover?.url,
    seasonCount = seasonCount,
    genres = parseGenres(genre),
    restrictKid = restrictKid,
)

private fun parseGenres(value: Any?): List<String> = when (value) {
    is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    is List<*> -> value.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

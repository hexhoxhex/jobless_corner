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
        val data = api.search(
            SearchRequest(keyword, page, PER_PAGE, type.code)
        ).unwrap()
        val deny = TastePrefs.denyLanguages()
        return data.items.map { it.toItem() }
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

    suspend fun details(subjectId: String): Details {
        val d = api.itemDetails(subjectId).unwrap()
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

    suspend fun resolvePlay(
        subjectId: String,
        resolution: String = "best",
        season: Int? = null,
        episode: Int? = null,
        dub: String = "Original",
    ): PlayInfo {
        val detail = api.itemDetails(subjectId).unwrap()
        val dubs = detail.dubs.map { it.toDomain() }

        val effectiveId: String
        val selectedDub: String
        if (detail.dubs.isNotEmpty()) {
            val target = pickDub(detail.dubs, dub)
            effectiveId = target.subjectId
            selectedDub = mapDubName(target.lanName)
        } else {
            effectiveId = subjectId
            selectedDub = "Original"
        }

        val selectedFile: VideoFile
        val qualities: List<Quality>

        if (season != null && episode != null) {
            val (file, available) =
                resolveEpisode(effectiveId, resolution, season, episode)
            selectedFile = file
            qualities = available.map { res ->
                Quality(
                    label = "${res}P",
                    mediaUrl = if (res == file.resolution) file.resourceLink
                    else null,
                )
            }
        } else {
            val files = resolveMovie(effectiveId, resolution)
            selectedFile = files.first
            qualities = files.second.sortedBy { it.resolution }.map {
                Quality("${it.resolution}P", it.resourceLink)
            }
        }

        val captions = runCatching {
            api.extCaptions(effectiveId, selectedFile.resourceId).unwrap()
                .extCaptions.map { CaptionTrack(it.lan, it.lanName, it.url) }
        }.getOrDefault(emptyList())

        return PlayInfo(
            title = detail.title.ifBlank { selectedFile.title },
            mediaUrl = selectedFile.resourceLink,
            selected = "${selectedFile.resolution}P",
            qualities = qualities,
            captions = captions,
            dubs = dubs,
            selectedDub = selectedDub,
            season = selectedFile.se,
            episode = selectedFile.ep,
            episodeTitle = selectedFile.title,
            durationSec = selectedFile.duration,
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

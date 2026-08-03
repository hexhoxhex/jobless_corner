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
            // Drop non-content junk. aoneroom's search index is polluted
            // with type-6 "shorts/music" rows (music mixes, "| Movieclips"
            // scene clips, fan tributes) and type-5 education/trailer rows
            // that aren't watchable titles — they crowd out the real movies
            // and series and make the catalog feel emptier than it is. Keep
            // only MOVIE + TV_SERIES (anime resolves to these too).
            .filter { it.type == SubjectType.MOVIE || it.type == SubjectType.TV_SERIES }
            .filter { keepByLanguage(it.title, deny) }
            // Skip items previously marked unavailable so the user
            // doesn't keep tapping on dead-on-arrival results.
            .filter { !UnavailableCatalog.isUnavailable(it.subjectId) }
    }

    /** User-facing search that defeats the upstream's literal substring
     *  matching. aoneroom indexes titles with their punctuation, so
     *  "spider man", "spider-man" and "spiderman" each return a DIFFERENT
     *  set (4 / 7 / 5 hits respectively — measured). Users shouldn't have to
     *  guess the hyphenation. We fan out over the obvious spelling variants
     *  in parallel and merge (dedup by subjectId, first-seen order kept) so
     *  the title surfaces however it's typed. A plain single-word query has
     *  one variant → one upstream call, so the extra cost is only paid where
     *  it actually broadens the result set. */
    suspend fun searchVariants(
        keyword: String,
        type: SubjectType = SubjectType.ALL,
    ): List<Item> {
        val variants = keywordVariants(keyword)
        val (mbLists, fourk, byPerson) = coroutineScope {
            // aoneroom spelling variants + the 4KHDHub provider, all in
            // parallel so the extra source doesn't add serial latency.
            val mb = variants.map { v ->
                async { runCatching { search(v, type) }.getOrDefault(emptyList()) }
            }
            val fk = async {
                runCatching { com.moviebox.tv.net.FourKHdHub.search(keyword) }
                    .getOrDefault(emptyList())
            }
            // Searching a PERSON — actor, director or producer — returns their
            // filmography. The name has to match exactly (see findPerson), so
            // an ordinary title search can't be hijacked by a similarly named
            // person. Runs in parallel, so it costs no extra latency.
            val person = async {
                runCatching {
                    lastPersonMatch = null
                    tmdb.findPerson(keyword)?.let { p ->
                        lastPersonMatch = p
                        tmdb.filmography(p.id, department = p.department)
                    }
                }.getOrNull().orEmpty()
            }
            Triple(mb.map { it.await() }, fk.await(), person.await())
        }
        val merged = LinkedHashMap<String, Item>()
        mbLists.forEach { list -> list.forEach { merged.putIfAbsent(it.subjectId, it) } }
        // Append 4KHDHub hits (separate `4k:` subjectId space — no collision
        // with aoneroom). These fill catalog gaps and often carry the full
        // season run where aoneroom only has a partial one, so they're worth
        // surfacing even when aoneroom already returned a same-named entry.
        fourk
            .filter { type == SubjectType.ALL || it.type == type }
            .forEach { merged.putIfAbsent(it.subjectId, it) }
        // A person's filmography goes FIRST when the catalogues found nothing
        // (searching "Tom Holland" has no title match, so those results are
        // the whole point) and after the title hits otherwise, so an actor who
        // shares a name with a film doesn't bury the film.
        val people = byPerson.filter { type == SubjectType.ALL || it.type == type }
        if (people.isNotEmpty()) {
            android.util.Log.i(
                "PersonSearch",
                "'$keyword' -> ${lastPersonMatch?.name} " +
                    "(${lastPersonMatch?.department}) ${people.size} titles",
            )
        }
        // A confident person match means the user typed a NAME, so their work
        // leads. The catalogues answer a name query with fuzzy junk — "Tom
        // Holland" returned Moana and Knucklebones — which would otherwise
        // bury the filmography the user actually asked for. Catalogue hits
        // still follow, in case one of them is the real target.
        return if (people.isEmpty()) merged.values.toList()
        else people + merged.values.filterNot { m -> people.any { it.subjectId == m.subjectId } }
    }

    /** The person whose filmography the last [searchVariants] surfaced, so the
     *  UI can label the row ("Films with Tom Holland"). Null when the last
     *  search wasn't a person. */
    @Volatile
    var lastPersonMatch: TmdbRepository.Person? = null
        private set

    /** Spelling variants to broaden a literal-match search. Toggles the
     *  space⇄hyphen⇄concatenated forms of the query; capped at 4 to bound
     *  the upstream fan-out. */
    private fun keywordVariants(raw: String): List<String> {
        val base = raw.trim()
        if (base.isEmpty()) return listOf(base)
        val out = LinkedHashSet<String>()
        out.add(base)
        if (' ' in base) {
            out.add(base.replace(' ', '-'))
            out.add(base.replace(" ", ""))
        }
        if ('-' in base) {
            out.add(base.replace('-', ' '))
            out.add(base.replace("-", ""))
        }
        return out.toList().take(4)
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
        // 4KHDHub provider items carry a `4k:` subjectId — route to its own
        // HTML-scrape detail path instead of the aoneroom H5 endpoint.
        if (subjectId.startsWith(com.moviebox.tv.net.FourKHdHub.PREFIX)) {
            return com.moviebox.tv.net.FourKHdHub.details(subjectId)
        }
        // TMDB-keyed items (browsed from TMDB rows, an actor's filmography, or
        // already pinned to a TMDB provider). Their seasons come from TMDB,
        // NOT from the aoneroom catalogue — a show the catalogue lacks used to
        // render an EMPTY season picker even though the TMDB-keyed providers
        // could play every episode (WandaVision: seasons=[]).
        tmdbKeyOf(subjectId)?.let { (tmdbId, isTv) ->
            val seasons = if (isTv) {
                runCatching { tmdb.seasonsOf(tmdbId) }.getOrDefault(emptyList())
                    .map { (se, count) ->
                        SeasonInfo(
                            season = se,
                            episodes = count,
                            resolutions = emptyList(),
                            realEpisodes = (1..count).toList(),
                        )
                    }
            } else emptyList()
            val meta = runCatching {
                tmdb.details(tmdbId, isTv)
            }.getOrNull()
            return Details(
                subjectId = subjectId,
                title = meta?.let { it.name ?: it.title }.orEmpty()
                    .ifBlank { titleHint.orEmpty() },
                type = if (isTv) SubjectType.TV_SERIES else SubjectType.MOVIE,
                description = meta?.overview.orEmpty(),
                year = (meta?.firstAirDate ?: meta?.releaseDate)
                    ?.take(4)?.toIntOrNull(),
                isSeries = isTv,
                seasons = seasons,
                dubs = emptyList(),
            )
        }
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

    /** TMDB-accurate metadata (poster, backdrop, overview, rating, trailer,
     *  cast) for a title — used to enrich a detail page in the background so
     *  the base source metadata renders instantly. Null on no confident match. */
    suspend fun enrichMetadata(
        title: String, year: Int?, isSeries: Boolean,
    ): com.moviebox.tv.data.tmdb.TmdbRepository.TmdbMeta? =
        runCatching { tmdb.enrich(title, year, isSeries) }.getOrNull()

    /** Which source served a stream — surfaced to the UI/remote so a title's
     *  origin is visible and switchable. */
    enum class Provider(val label: String) {
        // Order is the failover order for a title with no explicit source.
        // MovieBox first (streaming-sized files, measured smooth), then the
        // TMDB-keyed adaptive-HLS providers, then 4KHDHub, whose download-
        // oriented rips are the least suited to streaming.
        AONEROOM("MovieBox"),
        VIDNEST("VidNest"),
        ICEFY("Icefy"),
        VIXSRC("VixSrc"),
        FOURKHDHUB("4KHDHub");

        companion object {
            fun of(subjectId: String): Provider = when {
                subjectId.startsWith(com.moviebox.tv.net.VidNest.PREFIX) -> VIDNEST
                subjectId.startsWith(com.moviebox.tv.net.Icefy.PREFIX) -> ICEFY
                subjectId.startsWith(com.moviebox.tv.net.VixSrc.PREFIX) -> VIXSRC
                subjectId.startsWith(com.moviebox.tv.net.FourKHdHub.PREFIX) -> FOURKHDHUB
                else -> AONEROOM
            }
        }
    }

    /** Resolve a stream, FAILING OVER to other providers when the picked one
     *  can't serve it. Previously a dead aoneroom stream was terminal ("not
     *  available") even though 4KHDHub or VixSrc carried the same title — the
     *  user's "why doesn't it try another provider?". Order puts the requested
     *  provider first, then the rest by how well they stream here.
     *
     *  [only] pins a single provider (the remote's manual source picker) and
     *  disables failover, so an explicit choice is never silently overridden.
     */
    suspend fun resolvePlayAnyProvider(
        subjectId: String,
        title: String,
        year: Int?,
        isSeries: Boolean,
        resolution: String = "best",
        season: Int? = null,
        episode: Int? = null,
        dub: String = "Original",
        only: Provider? = null,
    ): PlayInfo {
        val first = only ?: Provider.of(subjectId)
        // Remote config decides which providers are live and in what order —
        // a source that dies is a published file edit, not an app release.
        // Falls back to the built-in list if the config is absent or bad.
        val available = ProviderConfig.apply(Provider.entries)
        val order = when {
            only != null -> listOf(only)
            // Whatever the item is already keyed to still goes first, even if
            // the config would rank it lower: the caller asked for that source.
            else -> (listOf(first) + available.filter { it != first })
                .filter { it == first || available.contains(it) }
        }
        var lastError: Throwable? = null
        var tried = 0
        val startedAt = android.os.SystemClock.elapsedRealtime()
        for (p in order) {
            tried++
            // Tell the viewer WHICH source is being tried. Resolving can take
            // a while (a provider whose mirrors are dead has to time out
            // before the next is attempted), and silence during that reads as
            // "nothing is happening". The overlay clears on first frame.
            com.moviebox.tv.data.live.LiveStatus.note(
                if (tried == 1) "▶ Finding a source…"
                else "↻ Not on ${order[tried - 2].label} — checking ${p.label}…",
            )
            // Bound the whole chain. Each additional provider adds its own
            // failure latency — 4KHDHub can spend ~90 s timing out dead
            // mirrors — so without a budget a title no source carries would
            // leave the viewer staring at a spinner for minutes. Stop looking
            // once the budget is gone and report honestly.
            if (android.os.SystemClock.elapsedRealtime() - startedAt > CHAIN_BUDGET_MS) {
                android.util.Log.w(
                    "Failover",
                    "budget spent after $tried provider(s) for '$title' — stopping",
                )
                break
            }
            val id = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                    idForProvider(p, subjectId, title, year, isSeries)
                }
            }.getOrNull() ?: continue
            com.moviebox.tv.data.live.LiveStatus.note("▶ Loading from ${p.label}…")
            val attempt = runCatching {
                // Per-provider cap: one slow source must not eat the budget
                // the others need. Generous enough for the aoneroom WebView
                // resolver, which legitimately takes 10-20 s on a cold start.
                kotlinx.coroutines.withTimeout(PER_PROVIDER_TIMEOUT_MS) {
                    resolvePlay(
                        subjectId = id, resolution = resolution, season = season,
                        episode = episode, dub = dub, titleHint = title,
                    )
                }
            }
            attempt.onSuccess {
                android.util.Log.i("Failover", "served '$title' via ${p.label}")
                return it.copy(provider = p.label, providerSubjectId = id)
            }
            lastError = attempt.exceptionOrNull()
            android.util.Log.w(
                "Failover",
                "${p.label} failed for '$title': ${lastError?.message} — trying next",
            )
        }
        com.moviebox.tv.data.live.LiveStatus.note("✗ No source has this title")
        throw ApiException(
            lastError?.message ?: "This title isn't available right now.",
        )
    }

    /** The subjectId [p] needs for [title]. Returns the given id untouched when
     *  it already belongs to that provider; otherwise looks the title up on
     *  that provider (TMDB id for VixSrc, catalogue search for the others). */
    private suspend fun idForProvider(
        p: Provider, subjectId: String, title: String, year: Int?, isSeries: Boolean,
    ): String? {
        if (Provider.of(subjectId) == p) return subjectId
        if (title.isBlank()) return null
        return when (p) {
            Provider.VIXSRC -> tmdb.matchId(title, year, isSeries)?.let { (id, isTv) ->
                "${com.moviebox.tv.net.VixSrc.PREFIX}${if (isTv) "tv" else "movie"}:$id"
            }
            Provider.VIDNEST -> tmdb.matchId(title, year, isSeries)?.let { (id, isTv) ->
                "${com.moviebox.tv.net.VidNest.PREFIX}${if (isTv) "tv" else "movie"}:$id"
            }
            Provider.ICEFY -> tmdb.matchId(title, year, isSeries)?.let { (id, isTv) ->
                "${com.moviebox.tv.net.Icefy.PREFIX}${if (isTv) "tv" else "movie"}:$id"
            }
            Provider.FOURKHDHUB -> com.moviebox.tv.net.FourKHdHub.search(title)
                .firstOrNull { titleMatches(it.title, title) }?.subjectId
            Provider.AONEROOM -> resolveByTitle(title, year, isSeries)?.subjectId
        }
    }

    /** (tmdbId, isTv) for any TMDB-keyed subjectId — the browse form
     *  `tmdb:tv:123` and the provider forms `vn:tv:123` / `ice:movie:9`
     *  / `vix:tv:5`. Null for catalogue ids, which keep the aoneroom path. */
    private fun tmdbKeyOf(subjectId: String): Pair<Int, Boolean>? {
        val prefixes = listOf(
            "tmdb:",
            com.moviebox.tv.net.VidNest.PREFIX,
            com.moviebox.tv.net.Icefy.PREFIX,
            com.moviebox.tv.net.VixSrc.PREFIX,
        )
        val p = prefixes.firstOrNull { subjectId.startsWith(it) } ?: return null
        val rest = subjectId.removePrefix(p)
        val id = rest.substringAfterLast(':').toIntOrNull() ?: return null
        return id to rest.startsWith("tv")
    }

    /** Loose title equality for cross-provider matching — provider titles carry
     *  their own decorations ("S1-S5", "[English]", release tags). */
    private fun titleMatches(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")
        val (x, y) = norm(a) to norm(b)
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    suspend fun resolvePlay(
        subjectId: String,
        resolution: String = "best",
        season: Int? = null,
        episode: Int? = null,
        dub: String = "Original",
        titleHint: String? = null,
        // Internal guard: allow ONE title-based re-resolution when the
        // given subjectId is dead (404 / 0 streams). Prevents infinite
        // recursion — the retry passes false.
        allowReresolve: Boolean = true,
    ): PlayInfo {
        // VixSrc provider: TMDB-keyed ADAPTIVE HLS. Tried first for tmdb-keyed
        // picks because the manifest carries a 480p/720p/1080p H.264 ladder,
        // so a thin pipe drops a rendition instead of buffer-starving the way
        // a single fixed-bitrate file does. Falls through to the aoneroom
        // bridge (by title) when this provider doesn't carry the title.
        if (subjectId.startsWith(com.moviebox.tv.net.VixSrc.PREFIX)) {
            val rest = subjectId.removePrefix(com.moviebox.tv.net.VixSrc.PREFIX)
            val isSeries = rest.startsWith("tv:")
            val tmdbId = rest.substringAfterLast(':').toIntOrNull()
            if (tmdbId != null) {
                val viaVix = runCatching {
                    com.moviebox.tv.net.VixSrc.resolvePlay(
                        tmdbId = tmdbId,
                        season = if (isSeries) (season ?: 1) else 0,
                        episode = if (isSeries) (episode ?: 1) else 0,
                        title = titleHint.orEmpty(),
                        resolution = resolution,
                    )
                }.getOrNull()
                if (viaVix != null) return viaVix
            }
            // Not on VixSrc — bridge to aoneroom by title, then continue below
            // with that subjectId (same path a tmdb: pick used to take).
            val bridged = titleHint?.let {
                runCatching { resolveByTitle(it, null, isSeries) }.getOrNull()
            } ?: throw ApiException("This title isn't available right now.")
            return resolvePlay(
                subjectId = bridged.subjectId, resolution = resolution,
                season = season, episode = episode, dub = dub,
                titleHint = titleHint, allowReresolve = allowReresolve,
            )
        }
        // VidNest: TMDB-keyed adaptive HLS. Its CDN requires the Referer that
        // came back with the stream, which travels in PlayInfo.headers.
        if (subjectId.startsWith(com.moviebox.tv.net.VidNest.PREFIX)) {
            val rest = subjectId.removePrefix(com.moviebox.tv.net.VidNest.PREFIX)
            val isTv = rest.startsWith("tv:")
            val tmdbId = rest.substringAfterLast(':').toIntOrNull()
                ?: throw ApiException("This title isn't available right now.")
            return com.moviebox.tv.net.VidNest.resolvePlay(
                tmdbId = tmdbId,
                season = if (isTv) (season ?: 1) else 0,
                episode = if (isTv) (episode ?: 1) else 0,
                title = titleHint.orEmpty(),
            ) ?: throw ApiException("This title isn't available right now.")
        }
        // Icefy: TMDB-keyed adaptive HLS. Referer AND Origin are mandatory —
        // without both the edge serves a Cloudflare challenge — so they come
        // back in PlayInfo.headers and ride every segment request.
        if (subjectId.startsWith(com.moviebox.tv.net.Icefy.PREFIX)) {
            val rest = subjectId.removePrefix(com.moviebox.tv.net.Icefy.PREFIX)
            val isTv = rest.startsWith("tv:")
            val tmdbId = rest.substringAfterLast(':').toIntOrNull()
                ?: throw ApiException("This title isn't available right now.")
            return com.moviebox.tv.net.Icefy.resolvePlay(
                tmdbId = tmdbId,
                season = if (isTv) (season ?: 1) else 0,
                episode = if (isTv) (episode ?: 1) else 0,
                title = titleHint.orEmpty(),
            ) ?: throw ApiException("This title isn't available right now.")
        }
        // 4KHDHub provider: resolve the mirror chain to a direct file instead
        // of the aoneroom play pipeline. Movies use season/episode 0.
        if (subjectId.startsWith(com.moviebox.tv.net.FourKHdHub.PREFIX)) {
            return com.moviebox.tv.net.FourKHdHub.resolvePlay(
                subjectId, season ?: 0, episode ?: 0, resolution,
            )
        }
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
            // Stale-subjectId self-heal. aoneroom rotates subjectIds over
            // time, so a Continue-Watching / history entry (or a deep link)
            // can point at a subjectId that now 404s — the detail lookup
            // returns null, both the episode-specific AND subject-level
            // play calls return 0 streams, and the app just loops the
            // WebView resolver forever ("stuck loading"). Verified on
            // Family Guy: history held 256827366285135664 (dead) while the
            // live catalog serves the same show as 2066092575169151304.
            //
            // Recover by re-searching the catalog for the title we have on
            // hand and retrying ONCE with the fresh subjectId. Generic —
            // fixes every stale-history title, not just Family Guy. Guarded
            // by allowReresolve so the retry can't recurse, and by a title
            // + fresh-id check so we never silently swap to a different
            // show.
            val hint = titleHint?.takeIf { it.isNotBlank() }
            if (allowReresolve && hint != null) {
                val looksSeries = (season ?: 0) > 0 || (episode ?: 0) > 0
                val fresh = runCatching {
                    resolveByTitle(hint, year = null, isSeries = looksSeries)
                }.getOrNull()
                if (fresh != null && fresh.subjectId != subjectId) {
                    android.util.Log.w(
                        "H5",
                        "resolvePlay stale subjectId=$subjectId 404'd — " +
                            "re-resolved '$hint' to ${fresh.subjectId}; retrying",
                    )
                    return resolvePlay(
                        subjectId = fresh.subjectId,
                        resolution = resolution,
                        season = season,
                        episode = episode,
                        dub = dub,
                        titleHint = hint,
                        allowReresolve = false,
                    )
                }
            }
            throw ApiException("This title isn't available right now.")
        }
        // Pick the best stream the user asked for. If [resolution] is
        // unspecified, cap default at 1080p — 2160p H.264 masters on the
        // aoneroom pipeline routinely exceed decoder profiles on mid-tier
        // Android TVs (Realtek G10 says NoSupport on 3840×1640@24), AND
        // when the API only returns a single 4K variant it's usually a
        // decoy master with broken audio (channelCount=0 in the MP4 esds).
        // 1080p is the sweet spot: universally decodable, plenty of quality,
        // and when the catalog has proper 1080p files their audio track is
        // well-formed. If the user explicitly picks 2160p from the quality
        // menu we honour it; otherwise 1080p (or the highest available
        // under 1080p) wins.
        val targetRes = resolution.filter { it.isDigit() }.toIntOrNull()
        val sorted = play.streams.sortedByDescending { it.resolution }
        val selectedStream = targetRes?.let { t ->
            sorted.firstOrNull { it.resolution == t }
        }
            ?: sorted.firstOrNull { it.resolution in 1..DEFAULT_MAX_RESOLUTION }
            ?: sorted.first()  // last resort — only super-high-res streams available
        val qualities = sorted.map { s ->
            Quality(
                label = if (s.resolution > 0) "${s.resolution}P" else s.format,
                mediaUrl = s.url,
            )
        }
        val aoneroomCaptions = runCatching {
            api.extCaptions(effectiveId, selectedStream.id).unwrap()
                .extCaptions.map { CaptionTrack(it.lan, it.lanName, it.url) }
        }.getOrDefault(emptyList())
        // External subtitles from OpenSubtitles (keyless Stremio addons),
        // in EVERY language the addon has — the fix for foreign-dubbed
        // single-audio files aoneroom serves with no captions (Family Guy
        // S8E1 = Spanish audio; K-dramas with only Korean audio; etc.).
        // ONE cheap list call returns ~45 languages; each entry's URL is
        // the addon's direct SRT link, so only the language the user
        // actually selects from the CC menu gets downloaded, not all of
        // them. Bounded by a 6 s timeout so a slow lookup never blocks
        // playback. Languages aoneroom already provides are not
        // duplicated. See [OpenSubtitlesClient].
        val subTitle = titleHint?.takeIf { it.isNotBlank() } ?: h5Detail?.title
        val externalCaptions = if (subTitle != null) {
            val existing = aoneroomCaptions.map { it.code.take(2).lowercase() }.toSet()
            kotlinx.coroutines.withTimeoutOrNull(6_000) {
                com.moviebox.tv.net.OpenSubtitlesClient.list(
                    title = subTitle, season = season ?: 0, episode = episode ?: 0,
                )
            }.orEmpty()
                .filter { it.code.lowercase() !in existing }
                .map { CaptionTrack(it.code, it.name, it.url) }
        } else emptyList()
        val captions = aoneroomCaptions + externalCaptions

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
        /** Default cap on picked stream resolution when the caller doesn't
         *  ask for a specific quality. Excludes 4K (2160p) because most
         *  Android TV chipsets in the target market can't hardware-decode
         *  4K H.264, AND because when the catalog serves a 4K-only variant
         *  it's usually a decoy master with a broken audio track (verified
         *  on Scary Movie subject 3994122036112146904). Users can still
         *  pick 2160p explicitly via the quality menu. */
        private const val DEFAULT_MAX_RESOLUTION = 1080

        private const val PER_PAGE = 20

        // --- Provider failover budget (resolvePlayAnyProvider) ---
        /** Total time the whole provider chain may spend before giving up.
         *  Each extra provider adds its own failure latency (4KHDHub can
         *  spend ~90 s timing out dead mirrors), so without a budget a title
         *  no source carries leaves the viewer on a spinner for minutes. */
        private const val CHAIN_BUDGET_MS = 75_000L
        /** Cap on ONE provider's resolve, so a slow source can't eat the
         *  budget the others need. Wide enough for the aoneroom WebView
         *  resolver, which legitimately takes 10-20 s on a cold start. */
        private const val PER_PROVIDER_TIMEOUT_MS = 30_000L
        /** Cap on the cheap "does this provider have the title" lookup. */
        private const val LOOKUP_TIMEOUT_MS = 12_000L

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

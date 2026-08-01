package com.moviebox.tv.data.tmdb

import com.moviebox.tv.BuildConfig
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.SubjectType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Catalog + enrichment from TMDB. Browsing the app's rows uses this repository
 * — playback then bridges the picked title back to aoneroom via title+year.
 */
class TmdbRepository(token: String = BuildConfig.TMDB_TOKEN) {

    /** Common TMDB network ids — for the "Browse by streaming service" rows. */
    object Networks {
        const val NETFLIX = 213
        const val HBO = 49
        const val DISNEY_PLUS = 2739
        const val PRIME = 1024
        const val APPLE_TV = 2552
        const val HULU = 453
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req: Request = chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val api: TmdbApi = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApi::class.java)

    suspend fun trending(): List<Item> = api.trending().results.mapNotNull { it.toItem() }
    suspend fun popularMovies(): List<Item> =
        api.popularMovies().results.mapNotNull { it.toItem(forceMovie = true) }
    suspend fun popularTv(): List<Item> =
        api.popularTv().results.mapNotNull { it.toItem(forceTv = true) }

    suspend fun byNetwork(networkId: Int, tv: Boolean = true): List<Item> =
        api.discover(if (tv) "tv" else "movie", withNetworks = networkId.toString())
            .results.mapNotNull { it.toItem(forceTv = tv, forceMovie = !tv) }

    // Raw-DTO variants so callers can filter on original_language before mapping.
    suspend fun trendingRaw(): List<TmdbItemDto>      = api.trending().results
    suspend fun popularMoviesRaw(): List<TmdbItemDto> = api.popularMovies().results
    suspend fun popularTvRaw(): List<TmdbItemDto>     = api.popularTv().results
    suspend fun byNetworkRaw(networkId: Int, tv: Boolean = true): List<TmdbItemDto> =
        api.discover(if (tv) "tv" else "movie", withNetworks = networkId.toString())
            .results

    suspend fun byGenre(genreIds: List<Int>, tv: Boolean = false): List<Item> {
        if (genreIds.isEmpty()) return emptyList()
        return api.discover(
            kind = if (tv) "tv" else "movie",
            withGenres = genreIds.joinToString(","),
        ).results.mapNotNull { it.toItem(forceTv = tv, forceMovie = !tv) }
    }

    suspend fun details(id: Int, isSeries: Boolean): TmdbDetailDto =
        if (isSeries) api.tvDetail(id) else api.movieDetail(id)

    suspend fun movieGenres(): List<TmdbGenre> = api.movieGenres().genres
    suspend fun tvGenres(): List<TmdbGenre> = api.tvGenres().genres

    /** TMDB-accurate metadata for a source title (aoneroom/4KHDHub), matched
     *  by title+year+type. Returns null when there's no confident match, so
     *  callers keep the source's own metadata rather than showing a wrong one. */
    data class TmdbMeta(
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val rating: Double?,
        val trailerKey: String?,
        val cast: List<com.moviebox.tv.data.CastMember>,
    )

    suspend fun enrich(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        val match = matchItem(title, year, isSeries) ?: return null
        // Fetch the detail with the MATCHED item's real media type — NOT the
        // source's isSeries. aoneroom can mislabel a series as a movie (The
        // Office → 0 seasons → isSeries=false); the match still finds the TV
        // id, and calling movieDetail() with a TV id returns a different work.
        val matchIsTv = mediaTypeOf(match) == "tv"
        val d = runCatching {
            if (matchIsTv) api.tvDetail(match.id) else api.movieDetail(match.id)
        }.getOrNull() ?: return null
        val trailer = d.videos?.results
            ?.filter { it.site.equals("YouTube", true) && it.type.equals("Trailer", true) }
            ?.maxByOrNull { if (it.official) 1 else 0 }?.key
            ?: d.videos?.results?.firstOrNull { it.site.equals("YouTube", true) }?.key
        val cast = d.credits?.cast.orEmpty()
            .sortedBy { it.order }
            .take(18)
            .map {
                com.moviebox.tv.data.CastMember(
                    name = it.name,
                    character = it.character?.takeIf { c -> c.isNotBlank() },
                    profileUrl = profile(it.profilePath),
                )
            }
        return TmdbMeta(
            posterUrl = poster(d.posterPath, "w500"),
            backdropUrl = backdrop(d.backdropPath),
            overview = d.overview?.takeIf { it.isNotBlank() },
            rating = d.voteAverage?.takeIf { it > 0.0 },
            trailerKey = trailer,
            cast = cast,
        )
    }

    /** TMDB id + whether it's a series, for a source title. Lets the play
     *  failover chain address TMDB-keyed providers (VixSrc) for a title that
     *  only came from aoneroom/4KHDHub. Null when there's no confident match. */
    suspend fun matchId(title: String, year: Int?, isSeries: Boolean): Pair<Int, Boolean>? {
        val m = matchItem(title, year, isSeries) ?: return null
        return m.id to (mediaTypeOf(m) == "tv")
    }

    /** Strict title→TMDB-id match: exact normalized title, right media type,
     *  closest year. No loose/first-result fallback — a wrong match would show
     *  the wrong poster/trailer/cast (the "Affinity → Avengers" failure class). */
    private suspend fun matchItem(title: String, year: Int?, isSeries: Boolean): TmdbItemDto? {
        val clean = cleanTitle(title)
        val want = normalize(clean)
        if (want.isEmpty()) return null
        val results = runCatching { api.searchMulti(clean).results }.getOrNull().orEmpty()
        val wantType = if (isSeries) "tv" else "movie"
        // Consider BOTH media types with an exact normalized title. The
        // requested type is only a tiebreak, NOT a filter — aoneroom's detail
        // for a series can come back with 0 seasons (looks like a movie), and
        // filtering to movies then matches an obscure "The Office" film
        // instead of the famous series. Ranking by votes lets the canonical
        // entry win regardless of that mislabel.
        val pool = results.filter {
            normalize(titleOf(it)) == want &&
                (mediaTypeOf(it) == "movie" || mediaTypeOf(it) == "tv")
        }
        if (pool.isEmpty()) return null
        // Rank by vote_count (the canonical show/film for a name is the
        // most-voted), with a dominant boost for a well-voted close-year
        // match, and a small nudge toward the requested type. This picks
        // Spider-Man (2002) by year, and the US "The Office" (5338 votes) for
        // an ambiguous name whose source year/type is unreliable — instead of
        // an obscure same-named entry (the wrong-cast/poster failure).
        return pool.maxByOrNull { c ->
            val votes = (c.voteCount ?: 0).toLong()
            val yearHit = year != null && yearDistance(c, year) <= 1 && votes >= 20
            val typeHit = mediaTypeOf(c) == wantType
            (if (yearHit) 1_000_000L else 0L) + votes + (if (typeHit) 500L else 0L)
        }
    }

    private fun titleOf(d: TmdbItemDto): String = d.title ?: d.name ?: ""
    private fun mediaTypeOf(d: TmdbItemDto): String =
        d.mediaType ?: if (d.title != null) "movie" else "tv"

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    /** Strip the decorations aoneroom & 4KHDHub bolt onto titles so they match
     *  TMDB's clean title: bracket/paren language+edition tags ("[English]",
     *  "(Version française)"), season ranges ("S5-S12", "Season 3"), and a
     *  trailing year. Without this, "Bleach [English]" normalizes to
     *  "bleachenglish" and never matches TMDB's "bleach". */
    private fun cleanTitle(s: String): String = s
        .replace(Regex("\\[[^\\]]*\\]"), " ")                 // [English], [Version française]
        .replace(Regex("\\([^)]*\\)"), " ")                    // (Version française), (2004)
        .replace(Regex("\\bS\\d{1,2}(\\s*-\\s*S?\\d{1,2})?\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\bSeason\\s*\\d+\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun yearDistance(d: TmdbItemDto, year: Int?): Int {
        if (year == null) return 0
        val dy = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull() ?: return 50
        return kotlin.math.abs(dy - year)
    }

    companion object {
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        fun poster(path: String?, size: String = "w342"): String? =
            path?.let { IMAGE_BASE + size + it }
        fun backdrop(path: String?, size: String = "w1280"): String? =
            path?.let { IMAGE_BASE + size + it }
        fun profile(path: String?, size: String = "w185"): String? =
            path?.let { IMAGE_BASE + size + it }
    }
}

/** Convert a TMDB list item to the app's [Item] model. */
/** True if [date] ("YYYY-MM-DD" or "YYYY...") parses to a day after today.
 *  Unparseable dates return false (don't drop on bad data). */
private fun isFutureRelease(date: String): Boolean = try {
    java.time.LocalDate.parse(date.take(10))
        .isAfter(java.time.LocalDate.now())
} catch (e: Exception) {
    false
}

fun TmdbItemDto.toItem(forceMovie: Boolean = false, forceTv: Boolean = false): Item? {
    val title = title ?: name ?: return null
    val isSeries = when {
        forceTv -> true
        forceMovie -> false
        else -> mediaType == "tv" || (name != null && title == name)
    }
    val date = if (isSeries) firstAirDate else releaseDate
    // Drop titles that aren't out yet. TMDB trending/popular feeds include
    // UPCOMING movies and shows (hype before release) — those have no
    // aoneroom stream, so playing or auto-advancing into one fails with
    // "not available". A release/air date in the future means "not
    // streamable", so never surface it in rows, heroes, or recommendations.
    // Null/unparseable dates are kept (we can't judge them).
    if (date != null && isFutureRelease(date)) return null
    val year = date?.take(4)?.toIntOrNull()
    val type = if (isSeries) SubjectType.TV_SERIES else SubjectType.MOVIE
    return Item(
        // Prefix TMDB ids so the bridge can detect them and resolve via title.
        subjectId = "tmdb:" + (if (isSeries) "tv" else "movie") + ":" + id,
        title = title,
        type = type,
        year = year,
        rating = voteAverage,
        // w185 is plenty for ~150dp poster cards — halves decode + bytes vs w342.
        coverUrl = TmdbRepository.poster(posterPath, size = "w185"),
        seasonCount = 0,
        genres = emptyList(),
        overview = overview,
        backdropUrl = TmdbRepository.backdrop(backdropPath),
    )
}

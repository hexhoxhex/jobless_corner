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

    companion object {
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        fun poster(path: String?, size: String = "w342"): String? =
            path?.let { IMAGE_BASE + size + it }
        fun backdrop(path: String?, size: String = "w1280"): String? =
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

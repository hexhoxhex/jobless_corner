package com.moviebox.tv.data.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    @GET("trending/all/day")
    suspend fun trending(
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US",
    ): TmdbPage<TmdbItemDto>

    @GET("movie/popular")
    suspend fun popularMovies(
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
    ): TmdbPage<TmdbItemDto>

    @GET("tv/popular")
    suspend fun popularTv(
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US",
    ): TmdbPage<TmdbItemDto>

    /** Discover movies/tv with filters such as with_networks, with_genres. */
    @GET("discover/{kind}")
    suspend fun discover(
        @Path("kind") kind: String, // "movie" or "tv"
        @Query("with_networks")  withNetworks: String? = null,
        @Query("with_companies") withCompanies: String? = null,
        @Query("with_genres")    withGenres: String? = null,
        @Query("with_original_language") originalLanguage: String? = "en",
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US",
    ): TmdbPage<TmdbItemDto>

    /** Title search for matching an aoneroom/4KHDHub entry to a TMDB id.
     *  multi returns mixed movie/tv/person — we filter to movie/tv. */
    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("language") language: String = "en-US",
    ): TmdbPage<TmdbItemDto>

    // append_to_response pulls trailers (videos) + cast (credits) in one call.
    @GET("movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "videos,credits",
    ): TmdbDetailDto

    @GET("tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "videos,credits",
    ): TmdbDetailDto

    /** Find a person (actor, director, producer) by name. */
    @GET("search/person")
    suspend fun searchPerson(
        @Query("query") query: String,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("language") language: String = "en-US",
    ): TmdbPage<TmdbPersonDto>

    /** Everything a person acted in OR worked on, movies and TV together. */
    @GET("person/{id}/combined_credits")
    suspend fun personCredits(
        @Path("id") id: Int,
        @Query("language") language: String = "en-US",
    ): TmdbPersonCredits

    @GET("genre/movie/list")
    suspend fun movieGenres(): TmdbGenres

    @GET("genre/tv/list")
    suspend fun tvGenres(): TmdbGenres
}

@JsonClass(generateAdapter = true)
data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TmdbItemDto(
    val id: Int = 0,
    val title: String? = null,            // movie
    val name: String? = null,             // tv
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "poster_path")   val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average")  val voteAverage: Double? = null,
    @Json(name = "vote_count")    val voteCount: Int? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbDetailDto(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val networks: List<TmdbNetwork> = emptyList(),
    // Populated by append_to_response=videos,credits.
    val videos: TmdbVideos? = null,
    val credits: TmdbCredits? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbVideo(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    val name: String = "",
)

@JsonClass(generateAdapter = true)
data class TmdbCredits(val cast: List<TmdbCastMember> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbCastMember(
    val name: String = "",
    val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    val order: Int = 999,
)

@JsonClass(generateAdapter = true)
data class TmdbPersonDto(
    val id: Int = 0,
    val name: String = "",
    @Json(name = "known_for_department") val department: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    val popularity: Double = 0.0,
)

/** combined_credits: `cast` = performed in, `crew` = directed/produced/wrote. */
@JsonClass(generateAdapter = true)
data class TmdbPersonCredits(
    val cast: List<TmdbItemDto> = emptyList(),
    val crew: List<TmdbCrewCredit> = emptyList(),
)

/** A crew credit is an item plus the job done on it, so a "producer" search
 *  can keep only the producing/directing work rather than every gaffer role. */
@JsonClass(generateAdapter = true)
data class TmdbCrewCredit(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val job: String? = null,
    val department: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    val overview: String? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class TmdbNetwork(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class TmdbGenres(val genres: List<TmdbGenre> = emptyList())

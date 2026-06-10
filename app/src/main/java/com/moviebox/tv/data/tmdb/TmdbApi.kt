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

    @GET("movie/{id}")
    suspend fun movieDetail(@Path("id") id: Int): TmdbDetailDto

    @GET("tv/{id}")
    suspend fun tvDetail(@Path("id") id: Int): TmdbDetailDto

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
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class TmdbNetwork(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class TmdbGenres(val genres: List<TmdbGenre> = emptyList())

package com.moviebox.tv.data.dto

import com.squareup.moshi.Json

/** Envelope: { code, message, data }. code==0 && message=="ok" means success. */
data class ApiResponse<T>(
    val code: Int = 1,
    val message: String? = null,
    val data: T? = null,
)

data class ImageDto(val url: String? = null)

data class Pager(
    @Json(name = "hasMore") val hasMore: Boolean = false,
    @Json(name = "nextPage") val nextPage: Int = 0,
    val page: Int = 1,
    @Json(name = "perPage") val perPage: Int = 20,
    @Json(name = "totalCount") val totalCount: Int = 0,
)

// -- search ----------------------------------------------------------------

data class SearchRequest(
    val keyword: String,
    val page: Int,
    val perPage: Int,
    val subjectType: Int,
)

data class Subject(
    val subjectId: String,
    val subjectType: Int = 0,
    val title: String = "",
    val description: String? = null,
    val releaseDate: String? = null,
    // Number or string depending on the endpoint; coerced in the repository.
    val imdbRatingValue: Any? = null,
    val cover: ImageDto? = null,
    // String ("Action, Drama") on some endpoints, list on others.
    val genre: Any? = null,
    val restrictKid: Int = 0,
    @Json(name = "seNum") val seasonCount: Int = 0,
)

data class SearchData(
    val items: List<Subject> = emptyList(),
    val pager: Pager? = null,
)

// -- homepage / discovery --------------------------------------------------

data class BannerItem(
    val image: ImageDto? = null,
    val content: String = "",
    val subjectId: String = "",
    val subject: Subject? = null,
)

data class BannerBlock(val banners: List<BannerItem> = emptyList())

data class HomeBlock(
    val type: String = "",
    val title: String = "",
    val subjects: List<Subject> = emptyList(),
    val banner: BannerBlock? = null,
)

data class HomeData(
    val items: List<HomeBlock> = emptyList(),
    val trendingTitle: String = "",
)

// -- item details ----------------------------------------------------------

data class DubDto(
    val subjectId: String,
    val lanName: String = "Original",
    val lanCode: String = "",
    val original: Boolean = false,
)

data class ItemDetailsData(
    val subjectId: String = "",
    val subjectType: Int = 0,
    val title: String = "",
    val description: String? = null,
    val releaseDate: String? = null,
    val dubs: List<DubDto> = emptyList(),
)

// -- seasons ---------------------------------------------------------------

data class ResolutionItem(
    val resolution: Int = 0,
    @Json(name = "epNum") val epNum: Int = 0,
)

data class SeasonDto(
    val se: Int = 0,
    @Json(name = "maxEp") val maxEp: Int = 0,
    val resolutions: List<ResolutionItem> = emptyList(),
)

data class SeasonsData(
    val subjectId: String = "",
    val subjectType: Int = 0,
    val seasons: List<SeasonDto> = emptyList(),
)

// -- resource (downloadable/streamable files) ------------------------------

data class VideoFile(
    val title: String = "",
    val resourceLink: String = "",
    val resourceId: String = "",
    val se: Int = 0,
    val ep: Int = 0,
    val resolution: Int = 0,
    val size: Long = 0,
    val duration: Int = 0,
    val codecName: String? = null,
)

data class ResourceData(
    val list: List<VideoFile> = emptyList(),
    val subjectId: String = "",
    val subjectType: Int = 0,
    val subjectTitle: String = "",
    val pager: Pager? = null,
)

// -- captions --------------------------------------------------------------

data class Caption(
    val id: String = "",
    val lan: String = "",
    val lanName: String = "",
    val url: String = "",
    val size: Long = 0,
    val delay: Int = 0,
)

data class CaptionsData(
    val extCaptions: List<Caption> = emptyList(),
    val subjectId: String = "",
)

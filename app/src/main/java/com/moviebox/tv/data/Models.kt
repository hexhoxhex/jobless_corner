package com.moviebox.tv.data

enum class SubjectType(val code: Int) {
    ALL(0), MOVIE(1), TV_SERIES(2), EDUCATION(5),
    MUSIC(6), ANIME(7), OTHER(8), UNKNOWN(9);

    val isSeries: Boolean get() = this == TV_SERIES

    companion object {
        fun fromCode(code: Int): SubjectType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class Item(
    val subjectId: String,
    val title: String,
    val type: SubjectType,
    val year: Int?,
    val rating: Double?,
    val coverUrl: String?,
    val seasonCount: Int,
    val genres: List<String> = emptyList(),
    val restrictKid: Int = 0,
    val overview: String? = null,
    val backdropUrl: String? = null,
) {
    val isSeries: Boolean get() = type.isSeries

    /** True when the API flags the item as not suitable for kids. */
    val isAdult: Boolean get() = restrictKid > 0

    val kidFriendly: Boolean
        get() = !isAdult && genres.any {
            it.equals("Animation", true) || it.equals("Kids", true) ||
                it.equals("Family", true)
        }
}

data class HomeRow(val title: String, val items: List<Item>)

data class Hero(val item: Item, val backdropUrl: String?, val tagline: String)

data class HomeContent(val heroes: List<Hero>, val rows: List<HomeRow>)

data class SeasonInfo(
    val season: Int,
    val episodes: Int,
    val resolutions: List<Int>,
)

data class Dub(val name: String, val code: String, val original: Boolean)

data class Details(
    val subjectId: String,
    val title: String,
    val type: SubjectType,
    val description: String,
    val year: Int?,
    val isSeries: Boolean,
    val seasons: List<SeasonInfo>,
    val dubs: List<Dub>,
)

/** A selectable quality. [mediaUrl] is null when it must be re-resolved. */
data class Quality(val label: String, val mediaUrl: String?)

data class CaptionTrack(val code: String, val name: String, val url: String)

data class PlayInfo(
    val title: String,
    val mediaUrl: String,
    val selected: String,
    val qualities: List<Quality>,
    val captions: List<CaptionTrack>,
    val dubs: List<Dub>,
    val selectedDub: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val durationSec: Int,
    /** Live HLS stream — disables resume/quality/dub/autoplay/progress UI. */
    val isLive: Boolean = false,
    /** Optional subtitle row under the title for live streams (e.g. group). */
    val subtitle: String = "",
)

class ApiException(message: String) : Exception(message)

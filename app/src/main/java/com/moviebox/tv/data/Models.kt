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
    /** aoneroom's claimed episode count (seasonInfo.maxEp). Unreliable —
     *  often 1-2 higher than the real number of playable files. Used as
     *  the immediate fallback before [realEpisodes] is enumerated. */
    val episodes: Int,
    val resolutions: List<Int>,
    /** The AUTHORITATIVE episode numbers present in aoneroom's actual
     *  file listing for this season, populated by a background walk of
     *  the resource pages (see Repository.enumerateEpisodes). Null until
     *  that walk completes; when non-null it replaces the 1..[episodes]
     *  range in the UI, so phantom trailing episodes never show. */
    val realEpisodes: List<Int>? = null,
)

data class Dub(val name: String, val code: String, val original: Boolean)

/** A cast member (from TMDB credits). [profileUrl] is a ready image URL. */
data class CastMember(
    val name: String,
    val character: String?,
    val profileUrl: String?,
)

data class Details(
    val subjectId: String,
    val title: String,
    val type: SubjectType,
    val description: String,
    val year: Int?,
    val isSeries: Boolean,
    val seasons: List<SeasonInfo>,
    val dubs: List<Dub>,
    /** YouTube video id for the title's trailer, or null. Prefer TMDB's
     *  official trailer (matched to the exact title); drives the "Trailer"
     *  button on the APK detail screen + web remote. */
    val trailerYouTubeId: String? = null,
    /** TMDB-accurate metadata, filled by [com.moviebox.tv.data.tmdb.TmdbRepository.enrich]
     *  when a confident title match exists — fixes wrong posters/overviews and
     *  adds the cast the aoneroom/4KHDHub sources don't carry. Null/empty when
     *  no match, so the base source metadata still shows. */
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val cast: List<CastMember> = emptyList(),
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
    /** Which source served this stream ("MovieBox"/"VixSrc"/"4KHDHub") and the
     *  subjectId it was served under. Surfaced so the UI and remote can show
     *  the origin and let the user switch sources for the same title. */
    val provider: String = "",
    val providerSubjectId: String = "",
    /** Live HLS stream — disables resume/quality/dub/autoplay/progress UI. */
    val isLive: Boolean = false,
    /** Optional subtitle row under the title for live streams (e.g. group). */
    val subtitle: String = "",
)

class ApiException(message: String) : Exception(message)

package com.moviebox.tv.data

/**
 * Show titles as the app should keep and compare them.
 *
 * The player labels an episode "S13E5 · Family Guy". That label reached the
 * phone remote as the playing title, and its "All episodes" list sent it back
 * as the show's name (2026-10-15). The TMDB lookup couldn't match it, so
 * 7Movies (TMDB-keyed) was never asked and MovieBox's Spanish copy played;
 * picking 7Movies from the remote failed the same way; and the label was
 * saved in Continue watching as a third "Family Guy" card.
 */
object TitleClean {

    private val EPISODE_LABEL = Regex(
        "^\\s*S\\d{1,2}\\s*E\\d{1,4}\\s*[·•|:-]\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** "S13E5 · Family Guy" -> "Family Guy". Anything else unchanged. */
    fun stripEpisodeLabel(title: String): String =
        title.replace(EPISODE_LABEL, "").trim()

    /** The show itself, for grouping: no episode label, no "[English]" or
     *  "(2004)" tags, no "S1-S23" season range. */
    fun showTitle(title: String): String = stripEpisodeLabel(title)
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\bS\\d{1,2}(\\s*-\\s*S?\\d{1,2})?\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /** Key two titles of the same show share. */
    fun showKey(title: String): String =
        showTitle(title).lowercase().replace(Regex("[^a-z0-9]+"), "")
}

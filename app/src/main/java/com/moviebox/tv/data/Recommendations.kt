package com.moviebox.tv.data

import com.moviebox.tv.data.local.WatchHistoryEntity
import kotlin.math.abs

/** What we've learned about the user's taste from their watch history. */
data class TasteProfile(
    val topGenres: List<String>,
    val likesOld: Boolean,
    val prefersSeries: Boolean,
    val medianYear: Int?,
) {
    val isEmpty: Boolean get() = topGenres.isEmpty() && medianYear == null
}

object Recommendations {

    private const val OLD_THRESHOLD = 2010

    fun profileFrom(history: List<WatchHistoryEntity>): TasteProfile {
        if (history.isEmpty())
            return TasteProfile(emptyList(), false, false, null)

        val genreCounts = HashMap<String, Int>()
        var seriesCount = 0
        var movieCount = 0
        val years = ArrayList<Int>()

        for (h in history) {
            h.genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { genreCounts[it] = (genreCounts[it] ?: 0) + 1 }
            if (h.season > 0) seriesCount++ else movieCount++
            h.year?.let { years.add(it) }
        }

        val topGenres = genreCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
        val likesOld = years.isNotEmpty() &&
            years.count { it < OLD_THRESHOLD }.toFloat() / years.size >= 0.34f
        val medianYear = years.sorted().getOrNull(years.size / 2)

        return TasteProfile(
            topGenres = topGenres,
            likesOld = likesOld,
            prefersSeries = seriesCount > movieCount,
            medianYear = medianYear,
        )
    }

    /** Score [pool] against the profile and return the best unseen picks. */
    fun recommend(
        pool: List<Item>,
        history: List<WatchHistoryEntity>,
        limit: Int = 18,
    ): List<Item> {
        val profile = profileFrom(history)
        if (profile.isEmpty) return emptyList()

        val watched = history.map { it.subjectId }.toSet()
        val seen = HashSet<String>()
        return pool.asSequence()
            .filter { it.subjectId !in watched && seen.add(it.subjectId) }
            .map { it to score(it, profile) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(item: Item, p: TasteProfile): Double {
        var s = 0.0
        // Genre overlap, weighted by the genre's rank in the profile.
        item.genres.forEach { g ->
            val idx = p.topGenres.indexOfFirst { it.equals(g, true) }
            if (idx >= 0) s += (p.topGenres.size - idx).toDouble()
        }
        // Era affinity — rewards old films when the user leans old, and
        // proximity to their median year otherwise.
        if (item.year != null) {
            if (p.likesOld && item.year < OLD_THRESHOLD) s += 3.0
            if (p.medianYear != null) {
                val dist = abs(item.year - p.medianYear)
                s += (1.0 - dist / 30.0).coerceAtLeast(0.0)
            }
        }
        // Type affinity.
        if (p.prefersSeries == item.isSeries) s += 1.0
        // Small quality nudge.
        item.rating?.let { s += it / 20.0 }
        return s
    }
}

package com.moviebox.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One row per (subject, season, episode) the user has played. Updated as
 * playback progresses so the user can resume — e.g. after a power cut.
 */
@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val key: String, // "subjectId|se|ep"
    val subjectId: String,
    val title: String,
    val coverUrl: String?,
    val type: Int,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val dub: String,
    val quality: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val genres: String = "",   // comma-separated, for the taste profile
    val year: Int? = null,
) {
    val progress: Float
        get() = if (durationMs > 0)
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Considered finished when within 20s of the end. */
    val finished: Boolean
        get() = durationMs > 0 && positionMs >= durationMs - 20_000

    companion object {
        fun keyOf(subjectId: String, season: Int?, episode: Int?): String =
            "$subjectId|${season ?: 0}|${episode ?: 0}"
    }
}

@Dao
interface WatchHistoryDao {
    /**
     * Raw continue-watching feed — every unfinished episode the user
     * touched, newest first. Use [com.moviebox.tv.ui.MainViewModel.continueWatching]
     * which groups by subjectId so the user sees ONE card per series
     * (the most-recently-watched episode), not every episode they
     * touched. Window functions would have been cleaner but Room's SQL
     * parser doesn't accept them yet.
     */
    @Query(
        "SELECT * FROM watch_history " +
            "WHERE durationMs = 0 OR positionMs < durationMs - 20000 " +
            "ORDER BY updatedAt DESC LIMIT 90"
    )
    fun continueWatching(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun all(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT positionMs FROM watch_history WHERE key = :key")
    suspend fun positionOf(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE key = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}

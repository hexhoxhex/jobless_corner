package com.moviebox.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Something the user wants to be told about when it's next on: a club, a
 * competition, or a show title.
 *
 * Distinct from [LiveFavouriteEntity] (a pinned *channel*) and
 * [FavouriteEntity] (a saved *movie/series*). A follow isn't a thing you
 * can play — it's a standing query against the live schedule. "Manchester
 * United" has no channel and no subjectId; it's a side that shows up on
 * whichever feed happens to carry the match that day.
 */
@Entity(tableName = "follows")
data class FollowEntity(
    /** [FixtureParser.canonical] form — the comparison identity. "Man Utd"
     *  and "Manchester United" collapse to the same row instead of
     *  quietly creating two follows that both fire. */
    @PrimaryKey val key: String,
    /** What the user actually typed / tapped, for display. */
    val label: String,
    /** [FollowKind] name. */
    val kind: String,
    /** Lead time in minutes. Defaults to the 20 the user asked for, but
     *  kept per-row so a slow-to-settle-in viewer can push it to 45. */
    val remindMinutes: Int = DEFAULT_LEAD_MINUTES,
    /** Reminders can be muted without losing the follow (and the
     *  "next fixtures" list it drives). */
    val remind: Boolean = true,
    val addedAt: Long,
) {
    companion object {
        const val DEFAULT_LEAD_MINUTES = 20
    }
}

enum class FollowKind {
    /**
     * Work it out at match time.
     *
     * The default for anything the user simply types. Classifying at *add*
     * time looked tidier but was wrong: whether "Arsenal" reads as a team
     * depends on whether Arsenal happen to be playing the day you add
     * them, so adding on a quiet Tuesday froze them as a SHOW forever.
     * Deciding per-event instead means the same follow behaves correctly
     * whichever day it was created. See [FollowMatcher].
     */
    AUTO,

    /** Matches a parsed *side* of a fixture — the precise case. */
    TEAM,

    /** Matches the competition segment ("England - Premier League"). */
    COMPETITION,

    /** Matches the whole title — for non-fixture programmes. */
    SHOW,
    ;

    companion object {
        fun from(s: String?): FollowKind =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: AUTO
    }
}

@Dao
interface FollowDao {
    @Query("SELECT * FROM follows ORDER BY addedAt DESC")
    fun all(): Flow<List<FollowEntity>>

    @Query("SELECT * FROM follows ORDER BY addedAt DESC")
    suspend fun allNow(): List<FollowEntity>

    @Query("SELECT key FROM follows")
    fun allKeys(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: FollowEntity)

    @Query("DELETE FROM follows WHERE key = :key")
    suspend fun remove(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM follows WHERE key = :key)")
    suspend fun isFollowed(key: String): Boolean

    @Query("UPDATE follows SET remind = :remind WHERE key = :key")
    suspend fun setRemind(key: String, remind: Boolean)

    @Query("UPDATE follows SET remindMinutes = :minutes WHERE key = :key")
    suspend fun setLead(key: String, minutes: Int)
}

/**
 * One row per reminder we've already raised, so rescheduling (which
 * happens on every schedule refresh, every follow change, and every boot)
 * can't fire the same match at the user twice.
 *
 * Keyed by a stable event identity rather than a row id: the catalog
 * republishes the same fixture across refreshes without any id of its own.
 */
@Entity(tableName = "reminders_fired")
data class ReminderFiredEntity(
    /** "<followKey>|<startUnix>" — one alert per follow per kickoff. */
    @PrimaryKey val eventKey: String,
    val firedAt: Long,
)

@Dao
interface ReminderFiredDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun mark(row: ReminderFiredEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM reminders_fired WHERE eventKey = :key)")
    suspend fun wasFired(key: String): Boolean

    /** Housekeeping — yesterday's kickoffs can never fire again. */
    @Query("DELETE FROM reminders_fired WHERE firedAt < :cutoff")
    suspend fun prune(cutoff: Long)
}

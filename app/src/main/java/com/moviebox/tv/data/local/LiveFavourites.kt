package com.moviebox.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * User's pinned live TV channels. Distinct from [FavouriteEntity] (which
 * stores movies/series) — channels have a separate identity domain
 * (string `id` from the catalog rather than the `subjectId` used for
 * VOD) and a different field set (logo URL, group, no rating/year).
 *
 * Persists across app upgrades thanks to the v5→v6 migration in
 * AppDatabase that simply adds this table without touching anything else.
 */
@Entity(tableName = "live_favourites")
data class LiveFavouriteEntity(
    /** Catalog channel id, e.g. "303" for AMC USA. Primary key. */
    @PrimaryKey val channelId: String,
    val name: String,
    val logoUrl: String?,
    val group: String?,
    /** Wall-clock ms when the user starred this channel. Used to sort the
     *  favourites strip newest-first so a freshly-pinned channel is
     *  visually close to the action that pinned it. */
    val addedAt: Long,
)

@Dao
interface LiveFavouriteDao {
    @Query("SELECT * FROM live_favourites ORDER BY addedAt DESC")
    fun all(): Flow<List<LiveFavouriteEntity>>

    /** Emits only the id set — cheaper than the full row when the UI just
     *  needs to know "is this channel starred?" for icon state. */
    @Query("SELECT channelId FROM live_favourites")
    fun allIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: LiveFavouriteEntity)

    @Query("DELETE FROM live_favourites WHERE channelId = :id")
    suspend fun remove(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM live_favourites WHERE channelId = :id)")
    suspend fun isFavourite(id: String): Boolean
}

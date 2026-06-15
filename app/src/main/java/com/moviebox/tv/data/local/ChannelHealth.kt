package com.moviebox.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Per-channel playback-health stats, used to flag the "channels that never
 * work" (Paramount, Cinemax, ch 303/374, …) on the grid and short-circuit
 * the 110-s native→WebView cascade for them.
 *
 *  - `bounceCount`  — consecutive cascade-bounces. A "bounce" is reaching
 *    [com.moviebox.tv.ui.LiveWebPlayer]'s `onAllPathsFailed` (native HLS
 *    exhausted, WebView iframe also dead) for this channel. Reset on any
 *    successful playback of >2 min.
 *  - `webOnlyHint`  — true when bounceCount ≥ 3. UI keys off this to skip
 *    the native attempt on next tap; resetting bounceCount also clears it.
 */
@Entity(tableName = "channel_health")
data class ChannelHealthEntity(
    @PrimaryKey val channelId: String,
    val bounceCount: Int,
    val lastBounceAt: Long,
    val lastSuccessAt: Long,
) {
    val webOnlyHint: Boolean get() = bounceCount >= WEB_ONLY_THRESHOLD
    val unstableHint: Boolean get() = bounceCount >= UNSTABLE_THRESHOLD

    companion object {
        /** Surface a small badge to the user. */
        const val UNSTABLE_THRESHOLD = 2

        /** Skip the native cascade entirely on next tap. */
        const val WEB_ONLY_THRESHOLD = 3
    }
}

@Dao
interface ChannelHealthDao {
    @Query("SELECT * FROM channel_health")
    fun all(): Flow<List<ChannelHealthEntity>>

    @Query("SELECT * FROM channel_health WHERE channelId = :id LIMIT 1")
    suspend fun get(id: String): ChannelHealthEntity?

    @Query(
        """
        INSERT INTO channel_health(channelId, bounceCount, lastBounceAt, lastSuccessAt)
        VALUES (:id, 1, :now, 0)
        ON CONFLICT(channelId) DO UPDATE SET
            bounceCount = bounceCount + 1,
            lastBounceAt = :now
        """
    )
    suspend fun recordBounce(id: String, now: Long)

    /** Success wipes the bounce count — channel is healthy again. */
    @Query(
        """
        INSERT INTO channel_health(channelId, bounceCount, lastBounceAt, lastSuccessAt)
        VALUES (:id, 0, 0, :now)
        ON CONFLICT(channelId) DO UPDATE SET
            bounceCount = 0,
            lastBounceAt = 0,
            lastSuccessAt = :now
        """
    )
    suspend fun recordSuccess(id: String, now: Long)
}

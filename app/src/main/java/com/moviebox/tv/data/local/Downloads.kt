package com.moviebox.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val key: String, // subjectId|se|ep
    val subjectId: String,
    val title: String,
    val episodeTitle: String,
    val coverUrl: String?,
    val type: Int,
    val season: Int,
    val episode: Int,
    val quality: String,
    val dub: String,
    val mediaUrl: String,
    val subtitleJson: String,  // [{"code","name","path"}]
    val filePath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val createdAt: Long,
    val genres: String = "",
    val year: Int? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0)
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isComplete: Boolean get() = status == DownloadStatus.COMPLETED.name
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT key FROM downloads")
    fun allKeys(): Flow<List<String>>

    @Query("SELECT * FROM downloads WHERE key = :key")
    suspend fun byKey(key: String): DownloadEntity?

    @Query(
        "SELECT * FROM downloads WHERE status = 'QUEUED' " +
            "ORDER BY createdAt ASC LIMIT 1"
    )
    suspend fun nextQueued(): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :done, totalBytes = :total WHERE key = :key")
    suspend fun updateProgress(key: String, done: Long, total: Long)

    @Query("UPDATE downloads SET status = :status WHERE key = :key")
    suspend fun updateStatus(key: String, status: String)

    @Query("DELETE FROM downloads WHERE key = :key")
    suspend fun remove(key: String)
}

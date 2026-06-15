package com.moviebox.tv.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.moviebox.tv.data.Item
import com.moviebox.tv.data.SubjectType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val subjectId: String,
    val title: String,
    val type: Int,
    val year: Int?,
    val rating: Double?,
    val coverUrl: String?,
    val addedAt: Long,
) {
    fun toItem(): Item = Item(
        subjectId = subjectId,
        title = title,
        type = SubjectType.fromCode(type),
        year = year,
        rating = rating,
        coverUrl = coverUrl,
        seasonCount = 0,
    )

    companion object {
        fun from(item: Item, addedAt: Long): FavouriteEntity = FavouriteEntity(
            subjectId = item.subjectId,
            title = item.title,
            type = item.type.code,
            year = item.year,
            rating = item.rating,
            coverUrl = item.coverUrl,
            addedAt = addedAt,
        )
    }
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun all(): Flow<List<FavouriteEntity>>

    @Query("SELECT subjectId FROM favourites")
    fun allIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entity: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE subjectId = :subjectId")
    suspend fun remove(subjectId: String)
}

@Database(
    entities = [
        FavouriteEntity::class,
        WatchHistoryEntity::class,
        DownloadEntity::class,
        ChannelHealthEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favourites(): FavouriteDao
    abstract fun watchHistory(): WatchHistoryDao
    abstract fun downloads(): DownloadDao
    abstract fun channelHealth(): ChannelHealthDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moviebox.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}

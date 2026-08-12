package com.voiceping.offlinetranscription.history

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
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "transcript_history")
data class TranscriptHistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAtMillis: Long,
    val durationMillis: Long,
    val source: String,
    val modelId: String,
    val backend: String,
    val language: String,
    val transcript: String,
)

@Dao
interface TranscriptHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TranscriptHistoryEntry)

    @Query("SELECT * FROM transcript_history ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<TranscriptHistoryEntry>>

    @Query("DELETE FROM transcript_history WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [TranscriptHistoryEntry::class], version = 1, exportSchema = false)
abstract class TranscriptHistoryDatabase : RoomDatabase() {
    abstract fun entries(): TranscriptHistoryDao

    companion object {
        fun create(context: Context): TranscriptHistoryDatabase = Room.databaseBuilder(
            context.applicationContext,
            TranscriptHistoryDatabase::class.java,
            "offline_transcribe_history.db"
        ).build()
    }
}

class TranscriptHistoryRepository(private val dao: TranscriptHistoryDao) {
    val entries: Flow<List<TranscriptHistoryEntry>> = dao.observeAll()
    suspend fun save(entry: TranscriptHistoryEntry) = dao.insert(entry)
    suspend fun delete(id: String) = dao.delete(id)
}

package com.example.screennotes

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val aiNotes: String = ""
)

@Entity
data class Shot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val path: String,
    val ocrText: String,
    val timeMs: Long
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM Note ORDER BY createdAt DESC") fun notes(): Flow<List<Note>>
    @Query("SELECT * FROM Note WHERE id = :id") fun note(id: Long): Flow<Note?>
    @Query("SELECT * FROM Note WHERE id = :id") suspend fun noteOnce(id: Long): Note?
    @Query("SELECT * FROM Shot WHERE noteId = :id ORDER BY timeMs") fun shots(id: Long): Flow<List<Shot>>
    @Query("SELECT * FROM Shot WHERE noteId = :id ORDER BY timeMs") suspend fun shotsOnce(id: Long): List<Shot>

    @Insert suspend fun insertNote(n: Note): Long
    @Insert suspend fun insertShot(s: Shot): Long
    @Update suspend fun updateNote(n: Note)
    @Update suspend fun updateShot(s: Shot)
    @Delete suspend fun deleteShot(s: Shot)
    @Query("DELETE FROM Note WHERE id = :id") suspend fun deleteNote(id: Long)
    @Query("DELETE FROM Shot WHERE noteId = :id") suspend fun deleteShots(id: Long)
}

@Database(entities = [Note::class, Shot::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NoteDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(c: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "notes.db")
                .build().also { instance = it }
        }
    }
}

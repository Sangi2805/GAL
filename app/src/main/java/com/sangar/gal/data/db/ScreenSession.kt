package com.sangar.gal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One unlocked stretch of phone use. The start time is the primary key, which lets the service
 * checkpoint an open session with a plain REPLACE (API 29 ships SQLite 3.22, which has no UPSERT).
 */
@Entity(tableName = "screen_sessions")
data class ScreenSession(
    @PrimaryKey @ColumnInfo(name = "start_epoch_millis") val startEpochMillis: Long,
    @ColumnInfo(name = "end_epoch_millis") val endEpochMillis: Long,
    /** Unlocked screen-on time only. The span from start to end also includes short screen-off gaps. */
    @ColumnInfo(name = "screen_on_millis") val screenOnMillis: Long,
    @ColumnInfo(name = "unlock_count") val unlockCount: Int,
)

@Dao
interface ScreenSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ScreenSession)

    /** Every session overlapping [fromEpochMillis, toEpochMillis). */
    @Query(
        "SELECT * FROM screen_sessions WHERE end_epoch_millis >= :fromEpochMillis AND start_epoch_millis < :toEpochMillis " +
            "ORDER BY start_epoch_millis",
    )
    suspend fun overlapping(fromEpochMillis: Long, toEpochMillis: Long): List<ScreenSession>

    @Query(
        "SELECT * FROM screen_sessions WHERE end_epoch_millis >= :fromEpochMillis AND start_epoch_millis < :toEpochMillis " +
            "ORDER BY start_epoch_millis",
    )
    fun observeOverlapping(fromEpochMillis: Long, toEpochMillis: Long): Flow<List<ScreenSession>>

    /** The most recently ended session, for picking a session back up after the service was killed. */
    @Query("SELECT * FROM screen_sessions ORDER BY end_epoch_millis DESC LIMIT 1")
    suspend fun latest(): ScreenSession?

    @Query("SELECT MIN(start_epoch_millis) FROM screen_sessions")
    suspend fun earliestStart(): Long?

    @Query("DELETE FROM screen_sessions WHERE end_epoch_millis < :beforeEpochMillis")
    suspend fun deleteEndingBefore(beforeEpochMillis: Long): Int
}

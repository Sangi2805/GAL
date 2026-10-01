package com.sangar.gal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A stretch of time the tracker service was alive and measuring, from its start to the last moment it was
 * known to be running. Days are only "tracked" when these cover most of them, so a day the service was
 * killed, the phone was off, or the app was not yet installed is never mistaken for a day of zero use.
 */
@Entity(tableName = "tracking_intervals")
data class TrackingInterval(
    @PrimaryKey @ColumnInfo(name = "start_epoch_millis") val startEpochMillis: Long,
    @ColumnInfo(name = "end_epoch_millis") val endEpochMillis: Long,
)

@Dao
interface TrackingIntervalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(interval: TrackingInterval)

    @Query(
        "SELECT * FROM tracking_intervals WHERE end_epoch_millis > :fromEpochMillis AND start_epoch_millis < :toEpochMillis " +
            "ORDER BY start_epoch_millis",
    )
    suspend fun overlapping(fromEpochMillis: Long, toEpochMillis: Long): List<TrackingInterval>

    @Query("DELETE FROM tracking_intervals WHERE end_epoch_millis < :beforeEpochMillis")
    suspend fun deleteEndingBefore(beforeEpochMillis: Long): Int
}

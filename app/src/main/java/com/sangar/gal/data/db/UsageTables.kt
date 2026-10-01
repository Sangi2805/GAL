package com.sangar.gal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "nag_events", indices = [Index("timestamp_epoch_millis")])
data class NagEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_epoch_millis") val timestampEpochMillis: Long,
    @ColumnInfo(name = "phrase_id") val phraseId: Int,
    val tier: Int,
    @ColumnInfo(name = "session_minutes") val sessionMinutes: Long,
    @ColumnInfo(name = "foreground_package") val foregroundPackage: String?,
)

/** One row per local calendar day, written by the daily worker from our own screen_sessions. */
@Entity(tableName = "daily_usage")
data class DailyUsage(
    @PrimaryKey val date: LocalDate,
    @ColumnInfo(name = "total_screen_minutes") val totalScreenMinutes: Int,
    @ColumnInfo(name = "unlock_count") val unlockCount: Int,
    @ColumnInfo(name = "nag_count") val nagCount: Int,
    /**
     * Whether the service was measuring for most of this day. An untracked day's minutes are a partial
     * figure at best, so it is left out of averages and trends and drawn as a gap, never as a low day.
     */
    @ColumnInfo(name = "tracked", defaultValue = "1") val tracked: Boolean = true,
)

@Entity(tableName = "app_daily_usage", primaryKeys = ["date", "package_name"])
data class AppDailyUsage(
    val date: LocalDate,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "foreground_millis") val foregroundMillis: Long,
)

class DateConverters {
    @TypeConverter
    fun fromDate(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun toDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

@Dao
interface NagEventDao {
    @Insert
    suspend fun insert(event: NagEvent)

    @Query("SELECT COUNT(*) FROM nag_events WHERE timestamp_epoch_millis >= :fromEpochMillis AND timestamp_epoch_millis < :toEpochMillis")
    suspend fun countBetween(fromEpochMillis: Long, toEpochMillis: Long): Int

    @Query("SELECT COUNT(*) FROM nag_events WHERE timestamp_epoch_millis >= :fromEpochMillis AND timestamp_epoch_millis < :toEpochMillis")
    fun observeCountBetween(fromEpochMillis: Long, toEpochMillis: Long): Flow<Int>

    @Query("DELETE FROM nag_events WHERE timestamp_epoch_millis < :beforeEpochMillis")
    suspend fun deleteBefore(beforeEpochMillis: Long): Int
}

@Dao
interface DailyUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyUsage)

    @Query("SELECT MAX(date) FROM daily_usage")
    suspend fun latestDate(): LocalDate?

    /** Inclusive range, oldest first. ISO dates sort correctly as text. */
    @Query("SELECT * FROM daily_usage WHERE date >= :from AND date <= :to ORDER BY date")
    suspend fun between(from: LocalDate, to: LocalDate): List<DailyUsage>

    @Query("SELECT * FROM daily_usage WHERE date >= :from AND date <= :to ORDER BY date")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DailyUsage>>

    @Query("SELECT MAX(total_screen_minutes) FROM daily_usage WHERE date < :before")
    suspend fun maxMinutesBefore(before: LocalDate): Int?

    @Query("SELECT COUNT(*) FROM daily_usage WHERE date < :before")
    suspend fun countBefore(before: LocalDate): Int

    @Query("DELETE FROM daily_usage WHERE date < :before")
    suspend fun deleteBefore(before: LocalDate): Int
}

@Dao
interface AppDailyUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AppDailyUsage>)

    @Query("SELECT * FROM app_daily_usage WHERE date = :date ORDER BY foreground_millis DESC LIMIT :limit")
    suspend fun topForDate(date: LocalDate, limit: Int): List<AppDailyUsage>

    @Query("DELETE FROM app_daily_usage WHERE date < :before")
    suspend fun deleteBefore(before: LocalDate): Int
}

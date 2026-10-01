package com.sangar.gal.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScreenSession::class, NagEvent::class, DailyUsage::class, AppDailyUsage::class, TrackingInterval::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(DateConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun screenSessions(): ScreenSessionDao
    abstract fun nagEvents(): NagEventDao
    abstract fun dailyUsage(): DailyUsageDao
    abstract fun appDailyUsage(): AppDailyUsageDao
    abstract fun trackingIntervals(): TrackingIntervalDao

    companion object {
        /**
         * Adds daily_usage.tracked and the tracking_intervals table. Nothing recorded before this version says
         * whether the service was running, so an old row with no screen time and no unlocks is taken to be a
         * day that was not measured (tracking off, phone off, service killed) rather than a real day of zero.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_usage` ADD COLUMN `tracked` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE `daily_usage` SET `tracked` = 0 WHERE `total_screen_minutes` = 0 AND `unlock_count` = 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracking_intervals` (`start_epoch_millis` INTEGER NOT NULL, " +
                        "`end_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`start_epoch_millis`))",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_2_3)
                .build()

        const val NAME = "getalife.db"
    }
}

package com.sangar.gal.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Opening a version 2 database from an older install: the new columns must arrive without losing history,
 * and the days that were only ever zero because nothing was measured must stop counting as real zero days.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTo3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private lateinit var db: AppDatabase

    @Before
    fun createVersion2Database() {
        context.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL(
            "CREATE TABLE IF NOT EXISTS `screen_sessions` (`start_epoch_millis` INTEGER NOT NULL, " +
                "`end_epoch_millis` INTEGER NOT NULL, `screen_on_millis` INTEGER NOT NULL, " +
                "`unlock_count` INTEGER NOT NULL, PRIMARY KEY(`start_epoch_millis`))",
        )
        legacy.execSQL(
            "CREATE TABLE IF NOT EXISTS `nag_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp_epoch_millis` INTEGER NOT NULL, `phrase_id` INTEGER NOT NULL, `tier` INTEGER NOT NULL, " +
                "`session_minutes` INTEGER NOT NULL, `foreground_package` TEXT)",
        )
        legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_nag_events_timestamp_epoch_millis` ON `nag_events` (`timestamp_epoch_millis`)")
        legacy.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_usage` (`date` TEXT NOT NULL, `total_screen_minutes` INTEGER NOT NULL, " +
                "`unlock_count` INTEGER NOT NULL, `nag_count` INTEGER NOT NULL, PRIMARY KEY(`date`))",
        )
        legacy.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_daily_usage` (`date` TEXT NOT NULL, `package_name` TEXT NOT NULL, " +
                "`foreground_millis` INTEGER NOT NULL, PRIMARY KEY(`date`, `package_name`))",
        )
        legacy.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        legacy.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '41b23806d3d1e9242321fd06baeccc17')")
        legacy.execSQL("INSERT INTO daily_usage VALUES ('2026-09-10', 220, 40, 7)")
        legacy.execSQL("INSERT INTO daily_usage VALUES ('2026-09-11', 0, 0, 0)")
        legacy.execSQL("INSERT INTO daily_usage VALUES ('2026-09-12', 0, 3, 0)")
        legacy.execSQL("INSERT INTO screen_sessions VALUES (1000, 2000, 900, 2)")
        legacy.version = 2
        legacy.close()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.getDatabasePath(name).delete()
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()
        .also { db = it }

    @Test
    fun migratingKeepsHistoryAndMarksTheEmptyDaysUntracked() = runBlocking {
        val rows = open().dailyUsage().between(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        assertEquals(3, rows.size)

        val real = rows.single { it.date == LocalDate.of(2026, 9, 10) }
        assertEquals(220, real.totalScreenMinutes)
        assertTrue("a day with real usage stays tracked", real.tracked)

        // Nothing measured and nothing unlocked: it was never a real zero day, so it must not drag averages down.
        assertFalse(rows.single { it.date == LocalDate.of(2026, 9, 11) }.tracked)
        // Unlocks but no minutes can only come from a day that was measured.
        assertTrue(rows.single { it.date == LocalDate.of(2026, 9, 12) }.tracked)

        assertEquals(1, db.screenSessions().overlapping(0, Long.MAX_VALUE).size)
        assertTrue("the new table is there and empty", db.trackingIntervals().overlapping(0, Long.MAX_VALUE).isEmpty())
    }
}

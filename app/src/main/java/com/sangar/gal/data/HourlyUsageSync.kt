package com.sangar.gal.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions
import com.sangar.gal.data.db.AppDatabase
import com.sangar.gal.data.db.AppHourUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Copies the phone's own usage events into app_hour_usage, a day at a time. Android only keeps those events for
 * a few days; saved here, they add up to the weeks of history the home screen's insights need.
 *
 * Nothing leaves the phone, and nothing is read that Android's own "Digital Wellbeing" does not already show.
 */
class HourlyUsageSync(
    private val context: Context,
    private val db: AppDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val mutex = Mutex()

    /**
     * Brings the table up to date, from the newest stored day (which may have been saved part-way through)
     * to now. The first run reaches back as far as Android still remembers. Returns false without usage access.
     */
    suspend fun sync(nowMillis: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!Permissions.hasUsageAccess(context)) return@withLock false
            val today = dateOf(nowMillis)
            val oldest = today.minusDays(MAX_BACK_DAYS)
            val latest = db.appHourUsage().latestDate()
            var day = if (latest == null || latest < oldest) oldest else latest
            val exclude = excludedPackages()
            while (day <= today) {
                val from = startOf(day)
                val to = if (day == today) nowMillis else startOf(day.plusDays(1))
                val events = events(from - LOOKBACK_MILLIS, to) ?: return@withLock false
                val rows = HourlyUsage.rows(events, from, to, zone(), openRunsToEnd = day == today, exclude = exclude)
                // An old day whose events Android has already forgotten comes back empty: keep what is stored.
                if (rows.isNotEmpty()) {
                    db.appHourUsage().deleteDate(day)
                    db.appHourUsage().upsertAll(rows.map { AppHourUsage(it.date, it.hour, it.packageName, it.millis) })
                }
                day = day.plusDays(1)
            }
            db.appHourUsage().deleteBefore(today.minusDays(KEEP_DAYS))
            true
        }
    }

    /** Time the [packages] spent in front today, straight from the events. Null without usage access. */
    suspend fun usedToday(packages: Set<String>, nowMillis: Long = System.currentTimeMillis()): Long? = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext 0L
        if (!Permissions.hasUsageAccess(context)) return@withContext null
        val from = startOf(dateOf(nowMillis))
        val events = events(from - LOOKBACK_MILLIS, nowMillis) ?: return@withContext null
        HourlyUsage.spans(events, from, nowMillis, openRunsToEnd = true)
            .filter { it.packageName in packages }
            .sumOf { it.end - it.start }
    }

    private fun events(from: Long, to: Long): List<AppEvent>? {
        val usage = context.getSystemService<UsageStatsManager>() ?: return null
        val raw = try {
            usage.queryEvents(from, to)
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalStateException) {
            // Thrown while the user is still locked after boot.
            return null
        } ?: return null
        val out = ArrayList<AppEvent>()
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> out += AppEvent(event.timeStamp, event.packageName, AppEvent.Kind.RESUMED)
                UsageEvents.Event.ACTIVITY_PAUSED -> out += AppEvent(event.timeStamp, event.packageName, AppEvent.Kind.PAUSED)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    out += AppEvent(event.timeStamp, null, AppEvent.Kind.SCREEN_OFF)
            }
        }
        return out
    }

    /** Not "apps" to the person holding the phone: GAL itself, the home screen and the system's own surfaces. */
    private fun excludedPackages(): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    }.onFailure { Log.w(TAG, "could not resolve the home app", it) }
        .getOrDefault(emptySet()) + context.packageName + "com.android.systemui"

    private fun startOf(day: LocalDate) = day.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    private companion object {
        const val TAG = "GAL.Hourly"

        /** How far back the first run looks. Android keeps roughly this many days of events. */
        const val MAX_BACK_DAYS = 9L

        /** Insights read four weeks; a little more is kept so a week-on-week comparison always has both sides. */
        const val KEEP_DAYS = 60L

        /** Start a little early so an app already in front at the window start is not missed. */
        const val LOOKBACK_MILLIS = 60 * 60_000L
    }
}

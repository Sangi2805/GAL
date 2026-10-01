package com.sangar.gal.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions

/**
 * Per-app split from raw ACTIVITY_RESUMED / ACTIVITY_PAUSED events. Android only keeps a few days of
 * events, so older days simply come back empty and are skipped.
 */
class UsageStatsAppUsage(private val context: Context) : AppUsageSource {

    override fun foregroundMillisByPackage(fromEpochMillis: Long, toEpochMillis: Long): Map<String, Long>? {
        if (!Permissions.hasUsageAccess(context)) return null
        val usage = context.getSystemService<UsageStatsManager>() ?: return null
        // Start a little early so an app already in front at the window start is not missed.
        val events = try {
            usage.queryEvents(fromEpochMillis - LOOKBACK_MILLIS, toEpochMillis)
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } ?: return null

        val resumedAt = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        val event = UsageEvents.Event()
        fun add(pkg: String, start: Long, end: Long) {
            val clampedStart = maxOf(start, fromEpochMillis)
            val clampedEnd = minOf(end, toEpochMillis)
            if (clampedEnd > clampedStart) totals[pkg] = (totals[pkg] ?: 0L) + (clampedEnd - clampedStart)
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt.putIfAbsent(pkg, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> resumedAt.remove(pkg)?.let { add(pkg, it, event.timeStamp) }
            }
        }
        resumedAt.forEach { (pkg, start) -> add(pkg, start, toEpochMillis) }
        return totals
    }

    private companion object {
        const val LOOKBACK_MILLIS = 60 * 60_000L
    }
}

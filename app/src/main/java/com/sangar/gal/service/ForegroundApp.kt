package com.sangar.gal.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions

/**
 * Which app is in front, from UsageStatsManager events. Only used for exclusions and per-app splits,
 * never for the session timer. Returns null when usage access is missing or nothing is known.
 *
 * Someone who stays in one app for hours has no recent resume event, so the last answer is remembered
 * and later calls only scan events since then instead of rescanning a whole day every tick.
 */
object ForegroundApp {

    private const val FIRST_WINDOW_MILLIS = 24 * 60 * 60_000L
    private const val OVERLAP_MILLIS = 2_000L

    private var lastPackage: String? = null
    private var lastQueryEpochMillis = 0L

    @Synchronized
    fun current(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): String? {
        if (!Permissions.hasUsageAccess(context)) {
            reset()
            return null
        }
        val usage = context.getSystemService<UsageStatsManager>() ?: return null
        val cacheValid = lastPackage != null && nowEpochMillis - lastQueryEpochMillis in 0..FIRST_WINDOW_MILLIS
        val from = if (cacheValid) lastQueryEpochMillis - OVERLAP_MILLIS else nowEpochMillis - FIRST_WINDOW_MILLIS

        val events = try {
            usage.queryEvents(from, nowEpochMillis)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            // Thrown while the user is still locked after boot.
            null
        }
        if (events == null) {
            reset()
            return null
        }

        var latest: String? = null
        var sawAnyEvent = false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            sawAnyEvent = true
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        // Revoked usage access returns an empty stream rather than throwing, so never trust a stale cache
        // on a fresh full-day scan that saw nothing at all.
        if (!cacheValid && !sawAnyEvent) {
            reset()
            return null
        }
        lastPackage = latest ?: lastPackage
        lastQueryEpochMillis = nowEpochMillis
        return lastPackage
    }

    @Synchronized
    fun reset() {
        lastPackage = null
        lastQueryEpochMillis = 0L
    }
}

package com.sangar.gal.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sangar.gal.container
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Writes daily_usage for every missing day, prunes old rows, then books its own next run for just
 * after the coming midnight. A one-off chain is used instead of a 24 h periodic job because periodic
 * work drifts away from midnight; the backfill makes a late or skipped run harmless either way.
 */
class DailyUsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        return try {
            val today = LocalDate.now()
            val aggregator = DailyUsageAggregator(container.database, appUsage = UsageStatsAppUsage(applicationContext))
            aggregator.backfill(today)
            aggregator.prune(today)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "daily aggregation failed", e)
            Result.retry()
        } finally {
            if (tags.contains(TAG_NIGHTLY)) DailyUsageWork.scheduleNightly(applicationContext)
        }
    }

    companion object {
        const val TAG = "GAL.DailyWork"
        const val TAG_NIGHTLY = "daily-usage-nightly"
    }
}

object DailyUsageWork {
    private const val RUN_AFTER_MIDNIGHT_MINUTES = 5L

    /**
     * Books the run for the next midnight. Each night gets its own unique name, so calling this from app
     * start, from boot and from the worker itself never creates duplicates and never cancels a running job.
     */
    fun scheduleNightly(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        val now = ZonedDateTime.now(zone)
        val target = now.toLocalDate().plusDays(1).atStartOfDay(zone).plusMinutes(RUN_AFTER_MIDNIGHT_MINUTES)
        val delay = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<DailyUsageWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(DailyUsageWorker.TAG_NIGHTLY)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("daily-usage-${target.toLocalDate()}", ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.e(DailyUsageWorker.TAG, "could not schedule nightly run", it) }
    }

    /** Catch-up run now, e.g. when the app opens after the phone was off for a while. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DailyUsageWorker>().build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork("daily-usage-now", ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.e(DailyUsageWorker.TAG, "could not schedule catch-up run", it) }
    }
}

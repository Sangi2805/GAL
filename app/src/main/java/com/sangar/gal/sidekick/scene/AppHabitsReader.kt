package com.sangar.gal.sidekick.scene

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions
import com.sangar.gal.data.UsageStatsAppUsage
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Android half of [AppHabits]: is an app social, how much has it been used, and what has been roasted.
 * Usage reads go through UsageStatsManager, so they need the Usage access that Screen Time Roasts already
 * has; without it every open is a plain one. Call [usage] off the main thread.
 */
class AppHabitsReader(private val context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Social by the app's own declared category, or by a short list of well-known social apps that do not
     * declare one. Messaging apps are never counted: being roasted for answering your mum is not the joke.
     */
    fun isSocial(packageName: String): Boolean {
        if (packageName in MESSAGING) return false
        if (packageName in KNOWN_SOCIAL) return true
        val info: ApplicationInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return false
        return info.category == ApplicationInfo.CATEGORY_SOCIAL
    }

    /** Today's opens and minutes and the seven day average, or null without usage access. */
    fun usage(packageName: String, nowWall: Long = System.currentTimeMillis()): AppUsage? {
        if (!Permissions.hasUsageAccess(context)) return null
        val manager = context.getSystemService<UsageStatsManager>() ?: return null
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return runCatching {
            val opens = AppHabits.countOpens(foregroundChanges(manager, dayStart, nowWall), packageName)
            val todayMillis = UsageStatsAppUsage(context).foregroundMillisByPackage(dayStart, nowWall)?.get(packageName) ?: 0L
            val weekStart = nowWall - WEEK_MILLIS
            val weekMillis = manager.queryAndAggregateUsageStats(weekStart, nowWall)[packageName]?.totalTimeInForeground ?: 0L
            AppUsage(
                opensToday = opens,
                minutesToday = todayMillis / 60_000L,
                averageMinutes7d = weekMillis / 7L / 60_000L,
            )
        }.getOrNull()
    }

    private fun foregroundChanges(manager: UsageStatsManager, from: Long, to: Long): List<String> {
        val out = ArrayList<String>(256)
        val events = manager.queryEvents(from, to) ?: return out
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> event.packageName?.let { out.add(it) }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> out.add(AppHabits.SCREEN_OFF)
            }
        }
        return out
    }

    fun history(): RoastHistory {
        val last = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_LAST) && value is Long) key.removePrefix(KEY_LAST) to value else null
        }.toMap()
        return RoastHistory(last, prefs.getInt(KEY_COUNT, 0), prefs.getLong(KEY_DAY, 0L))
    }

    fun recordRoast(packageName: String, nowWall: Long = System.currentTimeMillis()) {
        val today = today()
        val next = history().after(packageName, nowWall, today)
        prefs.edit()
            .putLong(KEY_LAST + packageName, nowWall)
            .putInt(KEY_COUNT, next.roastsOnDay)
            .putLong(KEY_DAY, next.day)
            .apply()
    }

    fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

    /** Forgets which apps were roasted and when, for "Wipe all data". */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "app_roasts"
        private const val KEY_LAST = "last_roast:"
        private const val KEY_COUNT = "roasts_on_day"
        private const val KEY_DAY = "roast_day"
        private const val WEEK_MILLIS = 7L * 24 * 60 * 60_000L

        /** Social and short-video apps that do not always declare the social category. */
        val KNOWN_SOCIAL = setOf(
            "com.instagram.android",
            "com.instagram.barcelona", // Threads
            "com.facebook.katana",
            "com.facebook.lite",
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok in some regions
            "com.snapchat.android",
            "com.twitter.android", // X
            "com.reddit.frontpage",
            "com.pinterest",
            "com.linkedin.android",
            "com.tumblr",
            "org.joinmastodon.android",
            "xyz.blueskyweb.app",
            "com.bereal.ft",
            "com.google.android.youtube", // counted for Shorts
            "com.vkontakte.android",
            "com.sina.weibo",
        )

        /** Never roasted, whatever category they declare. */
        val MESSAGING = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms", // Signal
            "com.facebook.orca", // Messenger
            "com.discord",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.Slack",
            "com.microsoft.teams",
            "com.skype.raider",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.tencent.mm", // WeChat
        )
    }
}

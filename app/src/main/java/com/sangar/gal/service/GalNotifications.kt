package com.sangar.gal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions
import com.sangar.gal.R
import com.sangar.gal.ui.MainActivity

/**
 * Two notifications:
 * - the quiet ongoing one that keeps [GalService] in the foreground, whatever mix of roasts and the elephant it runs;
 * - "The elephant took a break", posted when she should be on but is not, so the user can bring her back.
 */
object GalNotifications {
    const val CHANNEL_ID = "gal_running"
    private const val BRING_BACK_CHANNEL_ID = "sidekick_back"
    const val NOTIFICATION_ID = 1001
    private const val BRING_BACK_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GAL running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The quiet ongoing notification that keeps the screen timer and the elephant alive."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(BRING_BACK_CHANNEL_ID, "Bring the elephant back", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown when Android stopped the elephant. Tap it to bring her back."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    /**
     * [roastStatus] is the screen timer's line, or null when roasts are not running. [sidekickOn] adds the
     * elephant's line and a "Hide for 1 hour" button. [hiddenUntil] (wall clock, 0 for not hidden) says when a
     * hidden elephant comes back and adds a "Show her now" button.
     */
    fun build(context: Context, roastStatus: String?, sidekickOn: Boolean, hiddenUntil: Long = 0L): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hidden = !sidekickOn && hiddenUntil > System.currentTimeMillis()
        val hiddenLine = if (hidden) {
            "The elephant is hiding until ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(hiddenUntil))}."
        } else {
            null
        }
        val title = when {
            roastStatus != null && sidekickOn -> "GAL is watching, and so is the elephant"
            roastStatus != null -> "GAL is watching"
            hidden -> "The elephant is hiding"
            else -> "The elephant is on duty"
        }
        val text = listOfNotNull(roastStatus, if (sidekickOn) "Drag her onto the X to hide her." else null, hiddenLine)
            .joinToString(" ")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sidekick)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (sidekickOn) {
            val hideHour = PendingIntent.getService(
                context,
                1,
                Intent(context, GalService::class.java).setAction(GalService.ACTION_HIDE_HOUR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Hide for 1 hour", hideHour)
        } else if (hidden) {
            val show = PendingIntent.getService(
                context,
                3,
                Intent(context, GalService::class.java).setAction(GalService.ACTION_SHOW_ELEPHANT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Show her now", show)
        }
        return builder.build()
    }

    /** Silently does nothing when notifications are blocked; the service keeps running either way. */
    fun update(context: Context, roastStatus: String?, sidekickOn: Boolean, hiddenUntil: Long = 0L) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NOTIFICATION_ID, build(context, roastStatus, sidekickOn, hiddenUntil))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    /**
     * "Tap to bring Sidekick back". The tap starts [GalService] directly as a foreground service.
     */
    fun postBringBack(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        // A tap could not bring Sidekick back without these; the home screen explains what is missing instead.
        if (!Permissions.canDrawOverlays(context)) return
        val bringBack = PendingIntent.getForegroundService(
            context,
            2,
            Intent(context, GalService::class.java).setAction(GalService.ACTION_START_VOICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, BRING_BACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sidekick)
            .setContentTitle("The elephant took a break")
            .setContentText("Tap to bring her back.")
            .setContentIntent(bringBack)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(BRING_BACK_ID, notification)
            NagLog.i("Notify", "posted 'tap to bring Sidekick back'")
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    fun cancelBringBack(context: Context) = NotificationManagerCompat.from(context).cancel(BRING_BACK_ID)
}

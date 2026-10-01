package com.sangar.gal.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telecom.TelecomManager
import com.sangar.gal.service.NagLog
import androidx.core.content.getSystemService
import androidx.core.net.toUri

/**
 * Apps where a nag would get in the way: dialer, camera, maps, clock. Package names differ by OEM,
 * so they are resolved from intents at runtime. Anything that fails to resolve is simply left out.
 */
object DefaultExclusions {

    private const val TAG = "Exclusions"

    fun resolve(context: Context): Set<String> {
        val pm = context.packageManager
        val result = linkedSetOf<String>()
        runCatching { context.getSystemService<TelecomManager>()?.defaultDialerPackage }
            .getOrNull()?.let(result::add)
        result += handlers(pm, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        result += handlers(pm, Intent(Intent.ACTION_VIEW, "geo:0,0".toUri()))
        result += handlers(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))
        NagLog.d(TAG, "default exclusions resolved: $result")
        NagLog.i(TAG, "default exclusions resolved: ${result.size} app(s)")
        return result
    }

    /**
     * The default handler if there is one. When the user never picked a default, Android resolves to its
     * chooser ("android"), and then every candidate app is excluded instead.
     */
    private fun handlers(pm: PackageManager, intent: Intent): Set<String> = runCatching {
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        when {
            resolved == null -> emptySet()
            resolved != "android" -> setOf(resolved)
            else -> pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != "android" }
                .toSet()
        }
    }.getOrElse {
        NagLog.w(TAG, "resolve failed for an exclusion intent", it)
        emptySet()
    }
}

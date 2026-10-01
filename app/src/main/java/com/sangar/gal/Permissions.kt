package com.sangar.gal

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/** The two things GAL can do. Each asks only for its own permissions, and only when switched on. */
enum class Feature { ROASTS, VOICE }

/** One permission, as the setup screen shows it. */
enum class Need(val label: String, val explanation: String, val grantedInSettings: Boolean, val optional: Boolean = false) {
    USAGE_ACCESS(
        "Usage access",
        "Lets Sidekick see which app is on screen, so it stays quiet in the ones you exclude.",
        grantedInSettings = true,
    ),
    OVERLAY(
        "Display over other apps",
        "Lets Sidekick appear on top of whatever you are using.",
        grantedInSettings = true,
    ),
    NOTIFICATIONS(
        "Notifications",
        "Android needs a small ongoing notification to keep GAL running in the background.",
        grantedInSettings = false,
    ),
    MICROPHONE(
        "Microphone",
        "Used only after you tap Sidekick, to hear which app you asked for.",
        grantedInSettings = false,
    ),
    BATTERY(
        "Battery (recommended)",
        "Some phones kill background apps aggressively. Exempting GAL keeps the screen timer honest.",
        grantedInSettings = true,
        optional = true,
    ),
    ;

    companion object {
        fun forFeature(feature: Feature): List<Need> = when (feature) {
            Feature.ROASTS -> listOf(USAGE_ACCESS, OVERLAY, NOTIFICATIONS, BATTERY)
            Feature.VOICE -> listOf(OVERLAY, MICROPHONE, NOTIFICATIONS)
        }
    }
}

data class PermissionStatus(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val microphone: Boolean = false,
) {
    /** Everything Screen Time Roasts needs. Battery exemption is requested once but never required. */
    val allRequired: Boolean get() = missingFor(Feature.ROASTS).isEmpty()

    /** What Screen Time Roasts is missing, as short names. */
    val missing: List<String> get() = missingFor(Feature.ROASTS).map { it.label.lowercase() }

    fun granted(need: Need): Boolean = when (need) {
        Need.USAGE_ACCESS -> usageAccess
        Need.OVERLAY -> overlay
        Need.NOTIFICATIONS -> notifications
        Need.MICROPHONE -> microphone
        Need.BATTERY -> batteryUnrestricted
    }

    /** Required permissions for [feature] that are not granted, in the order the setup screen asks for them. */
    fun missingFor(feature: Feature): List<Need> = Need.forFeature(feature).filter { !it.optional && !granted(it) }

    fun ready(feature: Feature): Boolean = missingFor(feature).isEmpty()
}

/**
 * Every check here is cheap and side-effect free, so it is safe to call from onResume,
 * from the service tick, or anywhere a permission might have been revoked behind our back.
 */
object Permissions {

    fun check(context: Context) = PermissionStatus(
        usageAccess = hasUsageAccess(context),
        overlay = canDrawOverlays(context),
        notifications = notificationsEnabled(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context),
        microphone = hasMicrophone(context),
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService<AppOpsManager>() ?: return false
        val mode = runCatching {
            // Deprecated only in the API 37 stubs we compile against; it is the correct call for API 29 to 36.
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            // MODE_DEFAULT means the op defers to the permission itself.
            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    fun canDrawOverlays(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun needsRuntimeNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    fun openUsageAccessSettings(context: Context) = context.startFirst(
        // Some builds jump straight to our row when given the package, others only accept the bare action.
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(context)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    fun openOverlaySettings(context: Context) = context.startFirst(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    fun openNotificationSettings(context: Context) = context.startFirst(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    /** Where a runtime permission denied twice can still be switched on. */
    fun openAppDetails(context: Context) = context.startFirst(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    /**
     * Xiaomi, Redmi and POCO phones have their own switch, "Display pop-up windows while running in the
     * background", and without it Android's usual overlay exemption is not enough to open an app from Sidekick.
     * It cannot be read reliably, so setup just points at it on these phones.
     */
    val isXiaomiFamily: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any { it.orEmpty().lowercase() in XIAOMI_FAMILY }

    private val XIAOMI_FAMILY = setOf("xiaomi", "redmi", "poco")

    /** Xiaomi's own permission page for GAL ("Other permissions"), or the app's info page where it is missing. */
    fun openXiaomiOtherPermissions(context: Context) = context.startFirst(
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    /**
     * Needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. Falls back to the full list if the direct dialog is missing.
     * Lint flags this as against Play policy; the app is sideloaded, asks once, and a screen timer that OEM
     * battery managers kill is the exact failure this prevents.
     */
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) = context.startFirst(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    private fun Context.startFirst(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next one
            } catch (_: SecurityException) {
                // try the next one
            }
        }
        return false
    }
}

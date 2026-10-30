package com.sangar.gal

import com.sangar.gal.data.Settings

/** GAL's one-word state, shown at the top of home. */
enum class Health { ACTIVE, PAUSED, NEEDS_PERMISSION, RESTRICTED }

/** One line of the "Check GAL" list in Settings. */
data class CheckItem(val label: String, val ok: Boolean, val fix: String? = null)

/**
 * Turns the scattered pieces (permissions, the service, the elephant's window, the switches) into one state and
 * one checklist. Pure, so it is unit tested.
 */
object HealthCheck {

    fun health(
        settings: Settings,
        permissions: PermissionStatus,
        serviceRunning: Boolean,
        elephantOnScreen: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): Health = when {
        !permissions.allRequired -> Health.NEEDS_PERMISSION
        settings.roastsActive && !serviceRunning -> Health.RESTRICTED
        settings.voiceActive && !settings.elephantHidden(nowMillis) && !elephantOnScreen -> Health.RESTRICTED
        !settings.enabled || settings.elephantHidden(nowMillis) -> Health.PAUSED
        else -> Health.ACTIVE
    }

    fun headline(health: Health): String = when (health) {
        Health.ACTIVE -> "All good. She is keeping an eye on things."
        Health.PAUSED -> "Paused. Check-ins or the elephant are switched off for now."
        Health.NEEDS_PERMISSION -> "She needs a permission back before she can help."
        Health.RESTRICTED -> "Your phone stopped GAL in the background."
    }

    fun items(
        settings: Settings,
        permissions: PermissionStatus,
        serviceRunning: Boolean,
        elephantOnScreen: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<CheckItem> = listOf(
        CheckItem("Can see how long you have been on your phone", permissions.usageAccess, "Usage access"),
        CheckItem("Can appear over other apps", permissions.overlay, "Display over other apps"),
        CheckItem("Can keep running in the background", permissions.notifications, "Notifications"),
        CheckItem("Not limited by battery saving", permissions.batteryUnrestricted, "Battery"),
        CheckItem("Timer running", !settings.roastsActive || serviceRunning, "Restart"),
        CheckItem(
            "Elephant on screen",
            !settings.voiceActive || settings.elephantHidden(nowMillis) || elephantOnScreen,
            "Restart",
        ),
    )
}

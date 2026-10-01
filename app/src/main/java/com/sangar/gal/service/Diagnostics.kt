package com.sangar.gal.service

import android.util.Log
import com.sangar.gal.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One logcat tag for the whole tracker and overlay path: `adb logcat -s NAG`.
 * Messages are prefixed with the component so the stream stays readable.
 *
 * Logcat is readable over adb by anyone with the phone, so a release build never writes what you were
 * looking at or what a card said: the debug stream is switched off entirely, and the few lines that survive
 * put app names and phrases through [app] and [phrase]. Debug builds log everything, as before.
 */
object NagLog {
    const val TAG = "NAG"

    /** Debug builds only. Release keeps errors and the coarse lifecycle lines. */
    val verbose: Boolean = BuildConfig.DEBUG

    /** An app's package name, or a placeholder in a release build. */
    fun app(packageName: String?, verbose: Boolean = this.verbose): String = when {
        packageName == null -> "none"
        verbose -> packageName
        else -> "hidden"
    }

    /** A phrase id, which is enough to look the line up in the asset, so it is hidden in a release build. */
    fun phrase(id: Int, verbose: Boolean = this.verbose): String = if (verbose) id.toString() else "hidden"

    fun d(component: String, message: String) {
        if (verbose) Log.d(TAG, "[$component] $message")
    }
    fun i(component: String, message: String) = Log.i(TAG, "[$component] $message")
    fun w(component: String, message: String, error: Throwable? = null) = Log.w(TAG, "[$component] $message", error)
    fun e(component: String, message: String, error: Throwable? = null) = Log.e(TAG, "[$component] $message", error)
}

/** What the card actually looked like once laid out, to rule out off-screen or invisible windows. */
data class CardGeometry(
    val wallTime: Long,
    val screenX: Int,
    val screenY: Int,
    val width: Int,
    val height: Int,
    val alpha: Float,
    val shown: Boolean,
    val windowVisible: Boolean,
    val attached: Boolean,
    val displayWidth: Int,
    val displayHeight: Int,
    val textLength: Int,
) {
    val onScreen: Boolean
        get() = width > 0 && height > 0 && screenX < displayWidth && screenY < displayHeight &&
            screenX + width > 0 && screenY + height > 0

    override fun toString() =
        "at ($screenX,$screenY) size ${width}x$height on ${displayWidth}x$displayHeight alpha=$alpha " +
            "shown=$shown windowVisible=$windowVisible attached=$attached onScreen=$onScreen textLength=$textLength"
}

/**
 * Live state straight from the running service, not from DataStore, so the diagnostics screen shows what
 * the service is really using. Everything is cheap to keep up to date.
 */
data class ServiceDiagnostics(
    val serviceAlive: Boolean = false,
    val pid: Int = 0,
    val serviceCreatedWall: Long = 0L,
    val enabledInService: Boolean = false,
    val settingsLoaded: Boolean = false,
    val thresholdMinutesInUse: Int = 0,
    val sessionOpen: Boolean = false,
    val counting: Boolean = false,
    val activeMillis: Long = 0L,
    val measuredAtElapsed: Long = 0L,
    /** Session active time at which the next card becomes due, or null if no session. */
    val nextDueActiveMillis: Long? = null,
    /** Cards this session may show, latched when it started. */
    val sessionCardCap: Int = 0,
    val sessionCardsShown: Int = 0,
    /** Where that cap comes from: the threshold, or a manual override. */
    val sessionCardCapSetting: String = "not computed yet",
    val snoozeUntilElapsed: Long = 0L,
    val lastScreenOnWall: Long = 0L,
    val lastScreenOffWall: Long = 0L,
    val lastUserPresentWall: Long = 0L,
    val lastTickWall: Long = 0L,
    val lastDecision: String = "none yet",
    val lastDecisionWall: Long = 0L,
    val lastForegroundPackage: String? = null,
    val lastForegroundExcluded: Boolean = false,
    val nagProblem: String? = null,
    val lastCard: CardGeometry? = null,
)

object DiagnosticsState {
    private val _state = MutableStateFlow(ServiceDiagnostics())
    val state: StateFlow<ServiceDiagnostics> = _state.asStateFlow()

    fun update(transform: (ServiceDiagnostics) -> ServiceDiagnostics) = _state.update(transform)

    fun decision(text: String) = update { it.copy(lastDecision = text, lastDecisionWall = System.currentTimeMillis()) }
}

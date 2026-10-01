package com.sangar.gal.service

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the running service knows right now. The UI lives in the same process and reads it directly. */
data class LiveSession(
    val serviceRunning: Boolean = false,
    val sessionOpen: Boolean = false,
    val counting: Boolean = false,
    val sessionStartEpochMillis: Long = 0L,
    val activeMillis: Long = 0L,
    val measuredAtElapsed: Long = 0L,
    val unlockCount: Int = 0,
    val thresholdMinutes: Int = 0,
    /** Human readable reason nagging is paused, or null if it is not. */
    val nagProblem: String? = null,
) {
    /** Active session time extrapolated to now, so the UI does not have to wait for the next tick. */
    fun activeMillisNow(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        if (counting) activeMillis + (nowElapsed - measuredAtElapsed).coerceAtLeast(0) else activeMillis
}

/** The coverage interval the service in this process has open, so daily aggregation can count it up to now. */
object TrackingCoverage {
    @Volatile
    var openStartEpochMillis: Long? = null
}

object LiveTracker {
    private val _state = MutableStateFlow(LiveSession())
    val state: StateFlow<LiveSession> = _state.asStateFlow()

    fun publish(session: LiveSession) {
        _state.value = session
    }
}

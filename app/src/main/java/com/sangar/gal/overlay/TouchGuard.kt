package com.sangar.gal.overlay

/**
 * Swallows every touch gesture that starts within [guardMillis] of the card appearing, so a tap already
 * on its way to the app underneath cannot land on Fine or Snooze. A gesture that starts inside the guard
 * is ignored to the end, even if it lifts after the guard has run out. Pure, times passed in, so it can
 * be unit tested; [TouchGuardLayout] wires it to real MotionEvents.
 */
class TouchGuard(private val guardMillis: Long = GUARD_MILLIS) {

    private var armedAtUptime = Long.MAX_VALUE
    private var swallowing = false

    /** The card just became visible to the user. */
    fun arm(nowUptime: Long) {
        armedAtUptime = nowUptime
        swallowing = false
    }

    /**
     * Returns true when the event must be dropped. [downTimeUptime] is when the gesture's first finger went
     * down, which is what decides: the card may still be sliding in while a finger is already moving.
     */
    fun shouldSwallow(isDown: Boolean, isEnd: Boolean, downTimeUptime: Long): Boolean {
        if (isDown) swallowing = downTimeUptime - armedAtUptime < guardMillis
        val swallow = swallowing
        if (isEnd) swallowing = false
        return swallow
    }

    companion object {
        const val GUARD_MILLIS = 600L
    }
}

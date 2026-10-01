package com.sangar.gal.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout

/** The overlay window's root: the card inside, with [TouchGuard] in front of every touch. */
@SuppressLint("ViewConstructor")
class TouchGuardLayout(context: Context, private val guard: TouchGuard = TouchGuard()) : FrameLayout(context) {

    var onSwallowed: (MotionEvent) -> Unit = {}

    fun arm() = guard.arm(SystemClock.uptimeMillis())

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val swallow = guard.shouldSwallow(
            isDown = action == MotionEvent.ACTION_DOWN,
            isEnd = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL,
            downTimeUptime = event.downTime,
        )
        if (swallow) {
            if (action == MotionEvent.ACTION_DOWN) onSwallowed(event)
            // Consumed, so the rest of this gesture keeps coming here and is dropped too.
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}

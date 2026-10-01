package com.sangar.gal.sidekick.stage

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions
import com.sangar.gal.service.NagLog

/**
 * The stage window: a full-screen, see-through overlay that exists only while a scene plays. It sits on top of
 * whatever app is open, so the blob can run across it.
 *
 * Why only while a scene plays: from Android 12 a full-screen window from another app swallows every touch
 * meant for the app underneath. So the window is added for a scene of a few seconds, takes taps itself (a tap
 * skips the scene), and is removed the moment the scene ends. Main thread only.
 */
class StageOverlay(private val context: Context) {

    private val windowManager: WindowManager = context.getSystemService()!!
    private var view: StageView? = null

    val isShowing: Boolean get() = view != null

    /** Returns false if the window could not be added, usually because the overlay permission is gone. */
    fun show(stage: StageView): Boolean {
        if (view != null) dismiss()
        if (!Permissions.canDrawOverlays(context)) {
            NagLog.w(C, "no stage: canDrawOverlays is false")
            return false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the keyboard and the back button stay with the app underneath. Layout in screen and
            // no limits: the stage covers the whole display, under the status and navigation bars, and the view
            // reads the bar sizes as insets to keep the ground above the navigation bar.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "GAL Stage"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            windowManager.addView(stage, params)
            view = stage
            true
        } catch (e: RuntimeException) {
            NagLog.e(C, "could not add the stage window", e)
            false
        }
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        v.stopLoop()
        runCatching { windowManager.removeViewImmediate(v) }
            .onFailure { NagLog.w(C, "removing the stage window failed", it) }
    }

    private companion object {
        const val C = "Stage"
    }
}

package com.sangar.gal.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions
import com.sangar.gal.R
import com.sangar.gal.databinding.OverlayNagCardBinding
import com.sangar.gal.phrases.PhraseEngine
import com.sangar.gal.service.CardGeometry
import com.sangar.gal.service.DiagnosticsState
import com.sangar.gal.service.NagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

data class NagCard(
    val text: String,
    @param:DrawableRes val mascot: Int,
    val mascotDescription: String = "",
    /** Small line above the phrase, so the card is never mistaken for part of the app underneath. */
    val label: String = "Sidekick",
    /** The face, so the live Sidekick can pull it too while the card is up. */
    val face: Mascot? = null,
)

enum class ShowResult { SHOWN, NO_PERMISSION, FAILED }

/**
 * Which roast face is on screen right now, if any. The floating Sidekick watches this and wears the same face
 * for as long as the card stays up, so the roast visibly comes from Sidekick. Main thread writes only.
 */
object CardEvents {
    private val _face = MutableStateFlow<Mascot?>(null)
    val face: StateFlow<Mascot?> = _face.asStateFlow()

    /** Whose card set the face. Debug test cards use a second controller, and its timeout must not clear a real card's face. */
    private var owner: Any? = null

    fun shown(owner: Any, face: Mascot?) {
        this.owner = owner
        _face.value = face
    }

    fun gone(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        _face.value = null
    }
}

/**
 * Draws the nag card with WindowManager. Main thread only.
 *
 * The window is exactly the size of the card and uses FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL, so it
 * never takes keyboard focus and every touch outside the card goes to the app underneath.
 *
 * Touches on the card are ignored for its first [TouchGuard.GUARD_MILLIS], so a tap aimed at the app
 * underneath that lands just as the card appears cannot hit Fine or Snooze.
 *
 * The card is dark on purpose: the first real-world miss was a cream card drawn over the app's own cream
 * cards, where it read as part of the page. It slides in, and a bar shows how long it will stay.
 */
class OverlayController(context: Context) {

    private val appContext = context.applicationContext
    private val themed = ContextThemeWrapper(appContext, R.style.Theme_GAL)
    private val windowManager = appContext.getSystemService<WindowManager>()
    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var leaving: View? = null

    private val autoDismiss = Runnable { dismiss("timeout", animate = true) }

    val isShowing: Boolean get() = current != null

    fun show(card: NagCard, onFine: () -> Unit = {}, onSnooze: () -> Unit = {}): ShowResult {
        dismiss("replaced")
        val wm = windowManager ?: return ShowResult.FAILED.also { NagLog.e(C, "no WindowManager available") }
        if (!Permissions.canDrawOverlays(appContext)) {
            NagLog.w(C, "not drawing: canDrawOverlays is false")
            return ShowResult.NO_PERMISSION
        }

        val binding = OverlayNagCardBinding.inflate(LayoutInflater.from(themed))
        val text = card.text.takeIf { it.isNotBlank() } ?: PhraseEngine.DEFAULT_TEXT.also {
            NagLog.e(C, "refusing to draw a blank card; using the default line")
        }
        binding.label.text = card.label
        binding.phrase.text = text
        binding.mascot.setImageResource(card.mascot)
        binding.mascot.tag = card.mascotDescription
        binding.root.contentDescription = "${card.label}. $text"
        binding.fine.setOnClickListener {
            dismiss("fine", animate = true)
            onFine()
        }
        binding.snooze.setOnClickListener {
            dismiss("snooze", animate = true)
            onSnooze()
        }

        val params = WindowManager.LayoutParams(
            cardWidthPx(wm),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The window is laid out inside the status and navigation bars, so the offset is only the gap.
                // Adding the status bar height here as well pushed the card about 55 dp too low.
                fitInsetsTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                y = dp(TOP_GAP_DP)
            } else {
                // Android 10 does not fit overlay windows to insets, so clear a typical status bar by hand.
                y = dp(LEGACY_STATUS_BAR_DP + TOP_GAP_DP)
            }
            // The view animates itself in; a window animation on top of that would double the fade.
            windowAnimations = 0
            title = "GAL roast card"
        }

        NagLog.d(
            C,
            "addView type=APPLICATION_OVERLAY w=${params.width} h=WRAP gravity=TOP|CENTER_HORIZONTAL x=${params.x} y=${params.y} " +
                "flags=0x${Integer.toHexString(params.flags)} textLength=${text.length} mascot=${card.mascotDescription} " +
                "stays=${AUTO_DISMISS_MILLIS / 1000}s",
        )
        val guarded = TouchGuardLayout(themed)
        guarded.addView(binding.root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return try {
            val root = binding.root
            root.alpha = 0f
            root.translationY = -dp(28).toFloat()
            wm.addView(guarded, params)
            val shownAt = SystemClock.uptimeMillis()
            guarded.arm()
            guarded.onSwallowed = { NagLog.i(C, "ignored a touch ${it.downTime - shownAt}ms after the card appeared") }
            current = guarded
            animateIn(binding, wm)
            handler.postDelayed(autoDismiss, AUTO_DISMISS_MILLIS)
            CardEvents.shown(this, card.face)
            ShowResult.SHOWN
        } catch (e: WindowManager.BadTokenException) {
            failed(e)
        } catch (e: WindowManager.InvalidDisplayException) {
            failed(e)
        } catch (e: SecurityException) {
            // The overlay permission can disappear between the check and the call.
            failed(e)
        } catch (e: IllegalStateException) {
            failed(e)
        }
    }

    /**
     * [animate] is for dismissals the user can see coming (Fine, Snooze, timeout). Screen off, replacement
     * and service shutdown remove the window at once.
     */
    fun dismiss(reason: String, animate: Boolean = false) {
        handler.removeCallbacks(autoDismiss)
        leaving?.let { removeNow(it) }
        leaving = null
        val view = current ?: return
        current = null
        CardEvents.gone(this)
        view.animate().cancel()
        if (animate && view.isAttachedToWindow) {
            leaving = view
            view.animate()
                .alpha(0f)
                .translationY(-dp(12).toFloat())
                .setDuration(EXIT_MILLIS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (leaving === view) leaving = null
                    removeNow(view)
                }
                .start()
        } else {
            removeNow(view)
        }
        NagLog.i(C, "card dismissed: $reason")
    }

    private fun removeNow(view: View) {
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // Already detached, for example when the permission was revoked and the system removed it.
        }
    }

    private fun animateIn(binding: OverlayNagCardBinding, wm: WindowManager) {
        val root = binding.root
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_MILLIS)
            .setInterpolator(OvershootInterpolator(1.1f))
            // Measure once the entrance has finished, so the logged alpha and position are the final ones.
            .withEndAction { reportGeometry(binding, wm) }
            .start()

        binding.timerBar.pivotX = 0f
        binding.timerBar.animate()
            .scaleX(0f)
            .setDuration(AUTO_DISMISS_MILLIS)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    /** Measures where the card really ended up, to rule out an off-screen, zero-size or transparent window. */
    private fun reportGeometry(binding: OverlayNagCardBinding, wm: WindowManager) {
        val root = binding.root
        if (current !== root.parent) return
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val display = displaySize(wm)
        val geometry = CardGeometry(
            wallTime = System.currentTimeMillis(),
            screenX = location[0],
            screenY = location[1],
            width = root.width,
            height = root.height,
            alpha = root.alpha,
            shown = root.isShown,
            windowVisible = root.windowVisibility == View.VISIBLE,
            attached = root.isAttachedToWindow,
            displayWidth = display.first,
            displayHeight = display.second,
            textLength = binding.phrase.text?.length ?: 0,
        )
        NagLog.i(C, "card visible $geometry phraseViewSize=${binding.phrase.width}x${binding.phrase.height}")
        if (!geometry.onScreen || geometry.alpha < 0.05f || !geometry.shown || geometry.textLength == 0) {
            NagLog.w(C, "card is NOT visible to the user: $geometry")
        }
        DiagnosticsState.update { it.copy(lastCard = geometry) }
    }

    private fun displaySize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    private fun failed(e: Exception): ShowResult {
        NagLog.e(C, "could not add overlay window", e)
        current = null
        return ShowResult.FAILED
    }

    private fun dp(value: Int) = (value * appContext.resources.displayMetrics.density).toInt()

    private fun cardWidthPx(wm: WindowManager): Int = min(displaySize(wm).first - dp(24), dp(460))

    companion object {
        private const val C = "Overlay"

        /** Long enough to notice and read; the bar on the card shows it running out. */
        const val AUTO_DISMISS_MILLIS = 15_000L
        private const val ENTER_MILLIS = 320L
        private const val EXIT_MILLIS = 180L
        private const val TOP_GAP_DP = 12
        private const val LEGACY_STATUS_BAR_DP = 24
    }
}

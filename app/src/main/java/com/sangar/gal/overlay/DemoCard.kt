package com.sangar.gal.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.sangar.gal.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real check-in card on demand, in the user's chosen tone, through the same phrase engine and overlay as the
 * real ones. Used right after setup (so people see what GAL does without waiting) and by "Check GAL" in
 * Settings. It does not count towards the session's cards.
 */
object DemoCard {

    private var overlay: OverlayController? = null
    private var screenOff: BroadcastReceiver? = null

    private fun overlay(context: Context): OverlayController =
        overlay ?: OverlayController(context.applicationContext).also { overlay = it }

    /** A card must never outlive the screen, even when the tracking service is off. */
    private fun armScreenOffDismissal(context: Context) {
        if (screenOff != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                overlay?.dismiss("screen off")
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOff = receiver
    }

    suspend fun show(context: Context, label: String = "GAL · just showing you"): ShowResult {
        val settings = runCatching { context.container.settings.current() }.getOrNull()
        val tone = settings?.tone ?: com.sangar.gal.phrases.Tone.DEFAULT
        val phrase = context.container.phrases.sample(tone.maxTier.coerceAtMost(2), nagsThisSession = 0, pack = tone.pack)
        val mascot = Mascot.forNag(phrase.tier, 0, phrase.tags)
        return withContext(Dispatchers.Main) {
            armScreenOffDismissal(context)
            overlay(context).show(NagCard(phrase.text, mascot.drawable, mascot.description, label = label, face = mascot))
        }
    }
}

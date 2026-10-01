package com.sangar.gal.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.sangar.gal.container
import com.sangar.gal.phrases.PhrasePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows a real card on demand, through the same phrase engine, mascot mapping and overlay code.
 * Debug builds only: the Settings card, the diagnostics screen and the adb receiver all live here too,
 * so a release build carries none of it.
 */
object TestCard {

    /** Separate from the service's own overlay so a test card works whether or not tracking is on. */
    private var overlay: OverlayController? = null

    private var screenOff: BroadcastReceiver? = null

    private fun overlay(context: Context): OverlayController =
        overlay ?: OverlayController(context.applicationContext).also { overlay = it }

    /**
     * A card must never outlive the screen. The tracker service does that for real cards; a test card can
     * be shown with the service off, so it listens for itself. Registered once, for the process's life.
     */
    private fun armScreenOffDismissal(context: Context) {
        if (screenOff != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                overlay?.dismiss("screen off")
            }
        }
        context.applicationContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOff = receiver
    }

    suspend fun show(context: Context, tier: Int, nagsThisSession: Int = 0): ShowResult {
        val pack = runCatching { context.container.settings.current().phrasePack }.getOrDefault(PhrasePack.SPICY)
        val phrase = context.container.phrases.sample(tier.coerceIn(1, 3), nagsThisSession, pack)
        val mascot = Mascot.forNag(phrase.tier, nagsThisSession, phrase.tags)
        return withContext(Dispatchers.Main) {
            armScreenOffDismissal(context)
            overlay(context).show(NagCard(phrase.text, mascot.drawable, mascot.description, label = "Sidekick · test card", face = mascot))
        }
    }
}

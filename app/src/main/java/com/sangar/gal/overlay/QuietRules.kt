package com.sangar.gal.overlay

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import com.sangar.gal.service.ForegroundApp
import com.sangar.gal.service.NagLog

/** Situations where a card would be rude or dangerous. */
object QuietRules {

    /** Everything the rules looked at, so a suppression can always be explained. */
    data class Observed(
        val keyguardLocked: Boolean,
        val audioMode: Int,
        val foregroundPackage: String?,
        val foregroundExcluded: Boolean,
    ) {
        override fun toString() =
            "keyguardLocked=$keyguardLocked audioMode=${audioModeName(audioMode)} fg=${NagLog.app(foregroundPackage)} " +
                "excluded=$foregroundExcluded"
    }

    sealed interface Verdict {
        val observed: Observed

        data class Speak(override val observed: Observed) : Verdict
        data class Quiet(val reason: String, override val observed: Observed) : Verdict
    }

    fun evaluate(context: Context, exclusions: Set<String>): Verdict {
        val locked = context.getSystemService<KeyguardManager>()?.isKeyguardLocked == true
        val mode = context.getSystemService<AudioManager>()?.mode ?: AudioManager.MODE_NORMAL
        val foreground = ForegroundApp.current(context)
        val observed = Observed(locked, mode, foreground, foreground != null && foreground in exclusions)
        return when {
            locked -> Verdict.Quiet("phone is locked", observed)
            isCallMode(mode) -> Verdict.Quiet("in a call (audio mode ${audioModeName(mode)})", observed)
            observed.foregroundExcluded -> Verdict.Quiet("excluded app ${NagLog.app(foreground)}", observed)
            else -> Verdict.Speak(observed)
        }
    }

    /** Call detection through the audio mode, so READ_PHONE_STATE is never needed. */
    fun isInCall(context: Context): Boolean = isCallMode(context.getSystemService<AudioManager>()?.mode ?: AudioManager.MODE_NORMAL)

    private fun isCallMode(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT))

    fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        4 -> "CALL_SCREENING"
        5 -> "CALL_REDIRECT"
        6 -> "COMMUNICATION_REDIRECT"
        else -> mode.toString()
    }
}

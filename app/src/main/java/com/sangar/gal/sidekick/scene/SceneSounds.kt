package com.sangar.gal.sidekick.scene

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.core.content.getSystemService
import com.sangar.gal.R
import com.sangar.gal.service.NagLog
import com.sangar.gal.sidekick.stage.StageEvent

/**
 * The stage's sound effects: short original sounds made by tools/sounds/make_sounds.py, played through a
 * SoundPool. Only created while "Sound effects" is on in Settings (off by default). Silent and vibrate mode
 * keep the scenes quiet. Main thread only; call [release] when done.
 */
class SceneSounds(context: Context) : SceneDirector.Sounds {

    private val audio: AudioManager? = context.getSystemService()

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val jump = load(context, R.raw.scene_jump)
    private val land = load(context, R.raw.scene_land)
    private val thud = load(context, R.raw.scene_thud)
    private val bump = load(context, R.raw.scene_bump)
    private val hit = load(context, R.raw.scene_hit)
    private val crack = load(context, R.raw.scene_break)
    private val pop = load(context, R.raw.scene_pop)

    private var released = false

    override fun play(event: StageEvent) {
        if (released || !ringerAllowsSound()) return
        when (event) {
            StageEvent.Jumped -> play(jump, 0.5f)
            is StageEvent.Landed -> if (event.impact > 0.25f) play(land, (0.25f + event.impact * 0.5f).coerceAtMost(0.8f))
            StageEvent.CrateThud -> play(thud, 0.8f)
            StageEvent.HeadBump -> play(bump, 0.8f)
            // Each blow a little higher, so the third one sounds like the one that does it.
            is StageEvent.HammerHit -> play(hit, 0.9f, rate = 1f + 0.09f * (event.hit - 1).coerceIn(0, 3))
            StageEvent.CrateBroken -> play(crack, 0.9f)
            StageEvent.LaunchApp -> play(pop, 0.7f)
            StageEvent.SceneOver -> Unit
        }
    }

    fun release() {
        released = true
        runCatching { pool.release() }
    }

    private fun play(sound: Int, volume: Float, rate: Float = 1f) {
        if (sound == 0) return
        // Returns 0 while the sound is still loading; a scene that starts at once just plays without it.
        pool.play(sound, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    private fun ringerAllowsSound(): Boolean = (audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL) == AudioManager.RINGER_MODE_NORMAL

    private fun load(context: Context, res: Int): Int = runCatching { pool.load(context, res, 1) }
        .onFailure { NagLog.w(C, "could not load a scene sound", it) }
        .getOrDefault(0)

    private companion object {
        const val C = "Stage"
        const val MAX_STREAMS = 4
    }
}

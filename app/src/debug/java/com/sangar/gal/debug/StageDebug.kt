package com.sangar.gal.debug

import android.content.Context
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import com.sangar.gal.sidekick.Mood
import com.sangar.gal.sidekick.scene.NormalOpenScene
import com.sangar.gal.sidekick.scene.NotFoundScene
import com.sangar.gal.sidekick.scene.PlaygroundScene
import com.sangar.gal.sidekick.scene.RoastOpenScene
import com.sangar.gal.sidekick.scene.SceneDirector
import kotlin.math.roundToInt

/**
 * Debug builds only: the stage without a voice command, for tuning the movement and checking the scenes on a
 * real phone. The demo scenes use GAL's own icon and open nothing. Main thread only.
 */
object StageDebug {

    fun playground(context: Context): Boolean = SceneDirector.play(
        context,
        icon = ownIcon(context),
        playground = true,
        makeScene = { PlaygroundScene(it) },
        callbacks = SceneDirector.Callbacks(),
    ).also { if (!it) toast(context, "No stage: is \"Display over other apps\" on?") }

    fun demo(context: Context, kind: String): Boolean = SceneDirector.play(
        context,
        icon = ownIcon(context),
        makeScene = { entry ->
            when (kind) {
                "roast" -> RoastOpenScene(entry, "Are you married to this app?", Mood.SMUG)
                "notfound" -> NotFoundScene(entry, "Never heard of it.")
                else -> NormalOpenScene(entry)
            }
        },
        callbacks = SceneDirector.Callbacks(
            onLaunch = { toast(context, "The app would open now") },
        ),
    ).also { if (!it) toast(context, "No stage: is \"Display over other apps\" on?") }

    private fun ownIcon(context: Context) = runCatching {
        val px = (96 * context.resources.displayMetrics.density).roundToInt()
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(px, px)
    }.getOrNull()

    private fun toast(context: Context, text: String) = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
}

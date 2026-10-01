package com.sangar.gal.sidekick.scene

/** The scenes a voice command can play. */
enum class SceneKind { NORMAL_OPEN, ROAST_OPEN, NOT_FOUND }

/**
 * Which scene a voice command plays, if any. Plain Kotlin, unit tested.
 *
 * No scene (the old way: say it, then open) when Quick open is on or the phone's "Remove animations" setting is
 * on. The stage can still fail to show at run time (no overlay permission); the caller falls back the same way.
 * A roast does not need a scene: with scenes off the roast line is still spoken before the app opens.
 */
object SceneChoice {

    fun choose(found: Boolean, roast: Boolean, scenesEnabled: Boolean, animationsOff: Boolean): SceneKind? = when {
        !scenesEnabled || animationsOff -> null
        !found -> SceneKind.NOT_FOUND
        roast -> SceneKind.ROAST_OPEN
        else -> SceneKind.NORMAL_OPEN
    }
}

/**
 * Runs [action] once, after both the scene and the speech are over, whichever ends last. A scene and its line
 * run side by side, and Sidekick is only free for the next command when both are done. Main thread only.
 */
class AfterSceneAndSpeech(private val action: () -> Unit) {
    private var sceneOver = false
    private var speechOver = false
    private var fired = false

    fun sceneDone() {
        sceneOver = true
        fireIfBoth()
    }

    fun speechDone() {
        speechOver = true
        fireIfBoth()
    }

    private fun fireIfBoth() {
        if (sceneOver && speechOver && !fired) {
            fired = true
            action()
        }
    }
}

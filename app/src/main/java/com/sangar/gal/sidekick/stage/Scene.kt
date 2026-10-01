package com.sangar.gal.sidekick.stage

/**
 * A short scripted moment on the stage. Plain Kotlin: a scene drives the [World] each step like a player with
 * a controller, and the world reports what happened through [StageEvent]s.
 *
 * Every scene ends on its own within [maxSeconds], can be skipped with a tap at any moment, and opens its app
 * at most once ([StageEvent.LaunchApp]). The stage window only exists while a scene plays.
 */
abstract class Scene {

    /** For logs. */
    abstract val name: String

    /** Hard limit. Past it the scene opens its app (if it has one) and ends, whatever state it is in. */
    open val maxSeconds: Float = 3.5f

    /** False for scenes that only react, like "never heard of it". */
    open val opensApp: Boolean = true

    var time = 0f
        private set

    var launchFired = false
        private set

    /** The scene has finished its part; the world fades out and then reports [StageEvent.SceneOver]. */
    var over = false
        private set

    /** Was the scene cut short by a tap? */
    var skipped = false
        private set

    /** Place the actors. Called once, after the world knows its size. */
    abstract fun start(world: World)

    /** One step of the script, before the actors move. */
    protected abstract fun onUpdate(dt: Float, world: World)

    fun update(dt: Float, world: World) {
        time += dt
        if (over) return
        onUpdate(dt, world)
        if (!over && time >= maxSeconds) onTimeout(world)
    }

    /** Tap: open the app straight away (if this scene opens one) and leave. */
    open fun skip(world: World) {
        if (over) return
        skipped = true
        if (opensApp) launch(world)
        end(world, fadeSeconds = FAST_FADE)
    }

    protected open fun onTimeout(world: World) {
        if (opensApp) launch(world)
        end(world)
    }

    protected fun launch(world: World) {
        if (launchFired) return
        launchFired = true
        world.emit(StageEvent.LaunchApp)
    }

    protected fun end(world: World, fadeSeconds: Float = NORMAL_FADE) {
        if (over) return
        over = true
        world.fadeTo(0f, fadeSeconds)
    }

    companion object {
        const val NORMAL_FADE = 0.18f
        const val FAST_FADE = 0.08f
    }
}

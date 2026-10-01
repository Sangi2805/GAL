package com.sangar.gal.sidekick.stage

import kotlin.math.PI
import kotlin.math.sin

/**
 * The wooden crate with the app's icon on it. The app comes to the blob, because Android does not tell other
 * apps where the launcher draws its icons. Plain Kotlin; the renderer draws the wood and the real icon.
 *
 * Two ways in: [dropToGround] (it falls and lands with a thud, for the trunk smacks) and [floatDown] (it comes down
 * on a little parachute and hangs in the air, for a head-bump from below). Either way it ends [broken], with
 * the icon popping out of it.
 */
class AppCrate(
    /** Width and height of the box, pixels. */
    val width: Float,
    val height: Float,
) {
    enum class State { WAITING, FALLING, FLOATING, HANGING, RESTING, BROKEN }

    var state = State.WAITING
        private set

    /** Middle of the bottom edge. */
    var x = 0f
    var y = 0f
    private var vy = 0f
    private var hangY = 0f
    private var time = 0f

    /** Visual offset when hit from below, pixels, negative is up. */
    var bumpOffset = 0f
        private set
    private var bumpVelocity = 0f

    /** 0..3: how cracked the wood is. */
    var cracks = 0
        private set

    /** 1 right after a hit, decaying to 0: the renderer shakes the crate with it. */
    var shake = 0f
        private set

    /** 0..1: how far the parachute has opened. */
    var chuteOpen = 0f
        private set

    /** The parachute is drawn while true. */
    val parachute: Boolean get() = chuteOpen > 0f && (state == State.FLOATING || state == State.HANGING)

    /** Can be hit with the head. */
    val solid: Boolean get() = state == State.FLOATING || state == State.HANGING || state == State.RESTING

    val broken: Boolean get() = state == State.BROKEN

    /** Seconds since it broke, for the icon pop. */
    var brokenFor = 0f
        private set

    /** The parachute drifting off after a bump: offset and age. */
    var chuteAway = -1f
        private set

    fun dropToGround(x: Float, fromY: Float) {
        this.x = x
        y = fromY
        vy = 0f
        state = State.FALLING
    }

    fun floatDown(x: Float, fromY: Float, hangY: Float) {
        this.x = x
        y = fromY
        vy = 0f
        this.hangY = hangY
        state = State.FLOATING
    }

    fun step(dt: Float, world: World) {
        time += dt
        when (state) {
            State.FALLING -> {
                vy += world.tuning.gravity * world.tuning.fallGravityMultiplier * dt
                y += vy * dt
                if (y >= world.groundY) {
                    y = world.groundY
                    vy = 0f
                    state = State.RESTING
                    shake = 0.6f
                    world.spawnDust(x, y, 6)
                    world.emit(StageEvent.CrateThud)
                }
            }
            State.FLOATING -> {
                // Falls fast, then the parachute opens close to its spot and brakes it to a drift.
                val s = world.tuning.bodySize
                if (y < hangY - height * CHUTE_OPEN_HEIGHTS) {
                    vy = (vy + world.tuning.gravity * world.tuning.fallGravityMultiplier * dt).coerceAtMost(s * MAX_FALL_SIZES)
                } else {
                    chuteOpen = BlobActor.approach(chuteOpen, 1f, dt * 7f)
                    vy = BlobActor.approach(vy, s * DRIFT_SIZES, s * BRAKE_SIZES * dt)
                }
                y += vy * dt
                if (y >= hangY) {
                    y = hangY
                    vy = 0f
                    chuteOpen = 1f
                    state = State.HANGING
                    bumpVelocity = s * 1.5f
                }
            }
            State.HANGING -> Unit
            State.RESTING -> Unit
            State.BROKEN -> brokenFor += dt
            State.WAITING -> Unit
        }
        // Bump spring.
        val accel = -BUMP_STIFFNESS * bumpOffset - BUMP_DAMPING * bumpVelocity
        bumpVelocity += accel * dt
        bumpOffset += bumpVelocity * dt
        shake = (shake - dt * 3f).coerceAtLeast(0f)
        if (chuteAway >= 0f) chuteAway += dt
    }

    /** A gentle sway while hanging from the parachute, in pixels. */
    val sway: Float get() = if (state == State.HANGING || state == State.FLOATING) sin(time * 2f * PI.toFloat() * 0.7f) * width * 0.04f else 0f

    /** Hit from below: one bump is enough to burst it. */
    fun hitFromBelow(world: World) {
        if (!solid) return
        bumpVelocity = -world.tuning.bodySize * 6f
        cracks = MAX_CRACKS
        if (parachute) chuteAway = 0f
        burst(world)
    }

    /** A trunk smack. The third one bursts it. Returns the hit number, from 1. */
    fun hitWithTrunk(world: World): Int {
        if (broken) return cracks
        cracks = (cracks + 1).coerceAtMost(MAX_CRACKS)
        shake = 1f
        world.spawnChips(x, y - height / 2f, 3 + cracks)
        if (cracks >= MAX_CRACKS) burst(world)
        return cracks
    }

    private fun burst(world: World) {
        state = State.BROKEN
        brokenFor = 0f
        world.spawnChips(x, y - height / 2f, 10)
        world.spawnStars(x, y - height / 2f, 6)
        world.emit(StageEvent.CrateBroken)
    }

    companion object {
        const val MAX_CRACKS = 3

        /** The parachute opens this many crate heights above its spot. */
        const val CHUTE_OPEN_HEIGHTS = 1.4f

        /** Top falling speed before the parachute opens, body sizes per second. */
        const val MAX_FALL_SIZES = 16f

        /** Drifting speed under the open parachute, body sizes per second. */
        const val DRIFT_SIZES = 1.2f

        /** How hard the parachute brakes, body sizes per second squared. */
        const val BRAKE_SIZES = 110f

        const val BUMP_STIFFNESS = 900f
        const val BUMP_DAMPING = 30f
    }
}

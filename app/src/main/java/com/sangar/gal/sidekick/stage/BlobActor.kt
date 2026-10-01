package com.sangar.gal.sidekick.stage

import com.sangar.gal.sidekick.BlobPose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

/**
 * The blob on the stage. Plain Kotlin with no Android in it, so its movement can be unit tested.
 *
 * Position is the middle of the feet: [x] horizontally and [y] the bottom of the body. Y grows downwards, like
 * the screen. Scenes drive it like a game controller: [moveDir] is the stick, [pressJump] / [releaseJump]
 * the button. Everything else (speed, gravity, squash) comes from [MovementTuning].
 */
class BlobActor(var tuning: MovementTuning) {

    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var onGround = false
        private set

    /** 1 facing right, -1 facing left. */
    var facing = 1

    /** The stick: -1 left, 0 none, 1 right. */
    var moveDir = 0

    private var jumpHeld = false
    private var jumpQueued = false

    /** The face drawn on the body. Scenes set the mood and eyes; the renderer reads it. */
    val pose = BlobPose()

    /** Shape spring: positive is squashed (wide and short), negative is stretched. */
    var squash = 0f
        private set
    private var squashVelocity = 0f

    /** Running hop cycle, in hops. */
    private var runPhase = 0f

    /** 0..1, for fading in and out. */
    var alpha = 1f

    /** Hammer state, see [swingHammer]. */
    var hammerOut = false
        private set
    var hammerAngle = HAMMER_REST
        private set
    private var swingTime = -1f
    private var swingStruck = false
    private var pendingStrike: (() -> Unit)? = null

    fun place(x: Float, y: Float, airborne: Boolean) {
        this.x = x
        this.y = y
        vx = 0f
        vy = 0f
        onGround = !airborne
    }

    /** Jump on the next step if standing. Keep holding for full height; [releaseJump] cuts it short. */
    fun pressJump() {
        jumpQueued = true
        jumpHeld = true
    }

    fun releaseJump() {
        jumpHeld = false
    }

    /** A small hop, like a jump with the button let go at once. */
    fun hop() {
        jumpQueued = true
        jumpHeld = false
    }

    /** A sudden squash, for being poked or for effort. Positive squashes, negative stretches. */
    fun kickSquash(amount: Float) {
        squashVelocity += amount * 12f
    }

    fun drawHammer() {
        hammerOut = true
        hammerAngle = HAMMER_RAISED
    }

    fun putHammerAway() {
        hammerOut = false
        hammerAngle = HAMMER_REST
        swingTime = -1f
    }

    /**
     * Swings the hammer down. [onStrike] runs once, at the moment the head meets its target, which is when
     * the scene breaks a bit more of the crate.
     */
    fun swingHammer(onStrike: () -> Unit) {
        if (!hammerOut) drawHammer()
        swingTime = 0f
        swingStruck = false
        pendingStrike = onStrike
    }

    val isSwinging: Boolean get() = swingTime >= 0f

    /** Visual scale of the body this frame, for the renderer. */
    val scaleX: Float get() = 1f + 0.65f * squash - 0.45f * airStretch() - 0.04f * runContact()
    val scaleY: Float get() = 1f - squash + airStretch() - 0.06f * runContact()

    /** How far the body is lifted off [y] by the running hop. */
    val hopLift: Float get() = if (onGround && abs(vx) > RUN_THRESHOLD) abs(sin(runPhase * PI.toFloat())) * tuning.runHopHeight else 0f

    fun step(dt: Float, world: World) {
        val t = tuning
        // Horizontal: accelerate towards the stick, brake without it. Turning round brakes and accelerates at once.
        val control = if (onGround) 1f else t.airControl
        if (moveDir != 0) {
            val target = moveDir * t.maxRunSpeed
            val turning = vx != 0f && sign(vx).toInt() != moveDir
            val rate = (if (turning) t.runAccel + t.runDecel else t.runAccel) * control
            vx = approach(vx, target, rate * dt)
            facing = moveDir
        } else {
            vx = approach(vx, 0f, t.runDecel * control * dt)
        }

        if (jumpQueued && onGround) {
            vy = -t.jumpVelocity
            onGround = false
            squashVelocity -= 5f
            world.emit(StageEvent.Jumped)
        }
        jumpQueued = false

        // Vertical: light on the way up while the button is held, heavy on the way down.
        val g = when {
            vy > 0f -> t.gravity * t.fallGravityMultiplier
            !jumpHeld -> t.gravity * t.jumpCutMultiplier
            else -> t.gravity
        }
        val previousTop = y - t.bodySize
        if (!onGround) {
            // Average of the old and new speed: exact for constant gravity, so the apex lands on jumpHeight
            // whatever the frame rate.
            val before = vy
            vy = (vy + g * dt).coerceAtMost(t.maxFallSpeed)
            y += (before + vy) * 0.5f * dt
        }
        x += vx * dt

        // Walls: the screen edges.
        val half = t.bodySize / 2f
        if (x < world.left + half) {
            x = world.left + half
            if (vx < 0f) vx = 0f
        } else if (x > world.right - half) {
            x = world.right - half
            if (vx > 0f) vx = 0f
        }

        // Ceilings: a crate hanging above can be hit with the head.
        if (vy < 0f) {
            val top = y - t.bodySize
            for (crate in world.crates) {
                if (!crate.solid) continue
                val bottom = crate.y + crate.bumpOffset
                val overlap = abs(x - crate.x) < (half + crate.width / 2f) * 0.8f
                if (overlap && previousTop >= bottom - 1f && top < bottom) {
                    y = bottom + t.bodySize
                    vy = abs(vy) * 0.12f
                    squashVelocity += 4f
                    crate.hitFromBelow(world)
                    world.emit(StageEvent.HeadBump)
                    break
                }
            }
        }

        // Ground.
        if (!onGround && y >= world.groundY) {
            val impact = (vy / t.jumpVelocity).coerceAtLeast(0f)
            y = world.groundY
            vy = 0f
            onGround = true
            squashVelocity += t.landSquash * impact * 14f
            world.emit(StageEvent.Landed(impact))
        }

        // Shape spring.
        val accel = -t.squashStiffness * squash - t.squashDamping * squashVelocity
        squashVelocity += accel * dt
        squash = (squash + squashVelocity * dt).coerceIn(-0.35f, 0.45f)

        // Running hops.
        if (onGround && abs(vx) > RUN_THRESHOLD) {
            runPhase += abs(vx) * dt / t.runHopLength
        } else {
            runPhase = 0f
        }

        stepHammer(dt)
    }

    private fun stepHammer(dt: Float) {
        if (swingTime < 0f) return
        swingTime += dt
        val down = SWING_DOWN_SECONDS
        hammerAngle = when {
            swingTime < down -> {
                val p = swingTime / down
                HAMMER_RAISED + (HAMMER_STRIKE - HAMMER_RAISED) * p * p
            }
            swingTime < down + SWING_HOLD_SECONDS -> HAMMER_STRIKE
            swingTime < down + SWING_HOLD_SECONDS + SWING_UP_SECONDS -> {
                val p = (swingTime - down - SWING_HOLD_SECONDS) / SWING_UP_SECONDS
                HAMMER_STRIKE + (HAMMER_RAISED - HAMMER_STRIKE) * p
            }
            else -> {
                swingTime = -1f
                HAMMER_RAISED
            }
        }
        if (!swingStruck && swingTime >= down) {
            swingStruck = true
            kickSquash(0.25f)
            pendingStrike?.invoke()
            pendingStrike = null
        }
    }

    /** Stretched while moving fast vertically in the air. */
    private fun airStretch(): Float =
        if (onGround) 0f else (abs(vy) / tuning.jumpVelocity).coerceIn(0f, 1f) * 0.10f

    /** 1 at the bottom of a running hop, where the body touches down. */
    private fun runContact(): Float =
        if (onGround && abs(vx) > RUN_THRESHOLD) 1f - abs(sin(runPhase * PI.toFloat())) else 0f

    companion object {
        const val RUN_THRESHOLD = 30f

        /** Hammer angles in degrees, 0 pointing straight up, positive tipping forward. */
        const val HAMMER_REST = 0f
        const val HAMMER_RAISED = -40f
        const val HAMMER_STRIKE = 95f
        const val SWING_DOWN_SECONDS = 0.11f
        const val SWING_HOLD_SECONDS = 0.06f
        const val SWING_UP_SECONDS = 0.14f

        fun approach(value: Float, target: Float, maxDelta: Float): Float = when {
            value < target -> minOf(value + maxDelta, target)
            value > target -> maxOf(value - maxDelta, target)
            else -> value
        }
    }
}

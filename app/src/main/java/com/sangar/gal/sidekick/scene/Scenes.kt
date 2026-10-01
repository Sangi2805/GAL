package com.sangar.gal.sidekick.scene

import com.sangar.gal.sidekick.Mood
import com.sangar.gal.sidekick.stage.AppCrate
import com.sangar.gal.sidekick.stage.Scene
import com.sangar.gal.sidekick.stage.StageEvent
import com.sangar.gal.sidekick.stage.World
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Where the stage blob starts. When the floating Sidekick is on screen the stage blob takes its place and
 * hops down from there, so it reads as the same character; otherwise it comes in from the right edge.
 */
data class Entry(
    /** Centre of the floating blob on the stage, pixels. */
    val x: Float,
    val y: Float,
    val fromFloatingBlob: Boolean,
) {
    companion object {
        val OFFSCREEN_RIGHT = Entry(Float.NaN, Float.NaN, fromFloatingBlob = false)
    }
}

/** Shared moves for the scenes. */
internal object Moves {

    fun enter(world: World, entry: Entry) {
        val a = world.actor
        val t = world.tuning
        a.pose.reset()
        a.putHammerAway()
        if (entry.fromFloatingBlob && !entry.x.isNaN() && !entry.y.isNaN()) {
            val feet = (entry.y + t.bodySize / 2f).coerceAtMost(world.groundY)
            a.place(entry.x.coerceIn(world.left + t.bodySize / 2f, world.right - t.bodySize / 2f), feet, airborne = feet < world.groundY)
            if (feet < world.groundY) a.vy = -t.jumpVelocity * 0.35f
            a.facing = if (entry.x > world.width / 2f) -1 else 1
        } else {
            a.place(world.right - t.bodySize / 2f, world.groundY, airborne = false)
            a.facing = -1
        }
        world.fadeTo(1f, 0.15f)
    }

    /** A crate spot away from the blob, so there is a little run first. */
    fun crateX(world: World): Float {
        val ax = world.actor.x
        return if (ax > world.width / 2f) world.width * 0.36f else world.width * 0.64f
    }

    /**
     * Runs towards [targetX] and stops on it. Returns true once standing there. Brakes early enough to stop on
     * the spot instead of sliding past it.
     */
    fun runTo(world: World, targetX: Float, tolerance: Float = world.tuning.bodySize * 0.08f): Boolean {
        val a = world.actor
        val dx = targetX - a.x
        if (abs(dx) <= tolerance && abs(a.vx) < world.tuning.maxRunSpeed * 0.2f) {
            a.moveDir = 0
            return a.onGround
        }
        val stopping = a.vx * a.vx / (2f * world.tuning.runDecel)
        a.moveDir = when {
            abs(dx) <= tolerance -> 0
            sign(dx) == sign(a.vx) && abs(dx) <= stopping -> 0
            else -> sign(dx).toInt()
        }
        return false
    }

    /**
     * Horizontal distance covered while rising from the ground until the head reaches [rise] above its
     * starting height, at the current speed. Used to time a jump so the head meets the crate.
     */
    fun leadDistance(world: World, rise: Float): Float {
        val t = world.tuning
        val v = t.jumpVelocity
        val disc = v * v - 2f * t.gravity * rise
        val time = if (disc <= 0f) v / t.gravity else (v - sqrt(disc)) / t.gravity
        return abs(world.actor.vx) * time
    }
}

/**
 * Any app: a crate with the app's icon comes down on a parachute, the blob runs under it, jumps and bumps it
 * with its head, the icon pops out and the app opens. About two seconds.
 */
class NormalOpenScene(private val entry: Entry) : Scene() {
    override val name = "NormalOpen"

    private enum class Step { ENTER, RUN, JUMP, POP }

    private var step = Step.ENTER
    private lateinit var crate: AppCrate
    private var crateX = 0f
    private var jumps = 0
    private var poppedAt = -1f

    override fun start(world: World) {
        Moves.enter(world, entry)
        crateX = Moves.crateX(world)
        val t = world.tuning
        crate = world.newCrate()
        val hangY = world.groundY - t.bodySize - t.jumpHeight * HEAD_REACH
        crate.floatDown(crateX, fromY = world.top - crate.height * 1.5f, hangY = hangY)
    }

    override fun onUpdate(dt: Float, world: World) {
        val a = world.actor
        val t = world.tuning
        when (step) {
            Step.ENTER -> if (a.onGround && time > 0.15f) step = Step.RUN
            Step.RUN -> {
                val dx = crateX - a.x
                val hanging = crate.state == AppCrate.State.HANGING
                if (!hanging) {
                    // Get underneath and look up while it comes down.
                    Moves.runTo(world, crateX)
                    a.pose.gazeY = -0.8f
                    return
                }
                a.pose.gazeY = -0.5f
                a.moveDir = if (abs(dx) > t.bodySize * 0.05f) sign(dx).toInt() else 0
                val rise = (world.groundY - t.bodySize) - (crate.y + crate.bumpOffset)
                val lead = Moves.leadDistance(world, rise)
                if (a.onGround && abs(dx) <= lead + t.bodySize * 0.12f) {
                    a.pressJump()
                    jumps++
                    step = Step.JUMP
                }
            }
            Step.JUMP -> {
                val dx = crateX - a.x
                a.moveDir = if (abs(dx) > t.bodySize * 0.06f) sign(dx).toInt() else 0
                if (crate.broken) {
                    poppedAt = time
                    a.releaseJump()
                    a.pose.wideEyes = 1f
                    a.pose.gazeY = -1f
                    step = Step.POP
                } else if (a.onGround && a.vy == 0f && time > 0.3f) {
                    // Missed (it can happen if the crate moved). Try again, then force it.
                    if (jumps >= 2) {
                        crate.hitFromBelow(world)
                    } else {
                        step = Step.RUN
                    }
                }
            }
            Step.POP -> {
                a.moveDir = 0
                if (time - poppedAt >= POP_SECONDS) launch(world)
                if (time - poppedAt >= POP_SECONDS + LINGER_SECONDS) end(world)
            }
        }
    }

    companion object {
        /** Crate's bottom edge sits this share of a full jump above the head. */
        const val HEAD_REACH = 0.55f
        const val POP_SECONDS = 0.35f
        const val LINGER_SECONDS = 0.2f
    }
}

/**
 * A heavily used social app: the crate thuds onto the ground, the blob walks up, turns to us with a roast in a
 * speech bubble, pulls out a hammer and smashes the crate open in three blows. Then the app opens.
 */
class RoastOpenScene(
    private val entry: Entry,
    private val line: String,
    private val mood: Mood,
) : Scene() {
    override val name = "RoastOpen"

    private enum class Step { ENTER, WALK, TALK, HAMMER, POP }

    private var step = Step.ENTER
    private lateinit var crate: AppCrate
    private var crateX = 0f
    private var spotX = 0f
    private var talkStarted = -1f

    /** Longer lines stay up a little longer, within the scene's time limit. */
    private val bubbleSeconds = bubbleSecondsFor(line)
    private var nextSwing = 0f
    private var swings = 0
    private var poppedAt = -1f

    override fun start(world: World) {
        Moves.enter(world, entry)
        crateX = Moves.crateX(world)
        crate = world.newCrate()
        crate.dropToGround(crateX, fromY = world.top - crate.height * 1.2f)
        // Stand on our own side of the crate, close enough to swing at it.
        val side = if (world.actor.x >= crateX) 1f else -1f
        spotX = crateX + side * (crate.width / 2f + world.tuning.bodySize * 0.62f)
    }

    override fun onUpdate(dt: Float, world: World) {
        val a = world.actor
        when (step) {
            Step.ENTER -> if (a.onGround && time > 0.1f) {
                step = Step.WALK
                a.pose.mood = mood
                world.bubble.say(line, bubbleSeconds)
                talkStarted = time
            }
            Step.WALK -> {
                if (Moves.runTo(world, spotX)) {
                    a.facing = if (crateX > a.x) 1 else -1
                    step = Step.TALK
                }
            }
            Step.TALK -> {
                // Look at us while the line is up.
                a.pose.gazeX = 0f
                a.pose.gazeY = 0f
                if (time - talkStarted >= bubbleSeconds - 0.25f && crate.state == AppCrate.State.RESTING) {
                    a.facing = if (crateX > a.x) 1 else -1
                    a.drawHammer()
                    a.pose.gazeX = a.facing * 0.9f
                    nextSwing = time + 0.12f
                    step = Step.HAMMER
                }
            }
            Step.HAMMER -> {
                if (crate.broken) {
                    poppedAt = time
                    a.putHammerAway()
                    a.pose.mood = Mood.SMUG
                    step = Step.POP
                } else if (!a.isSwinging && time >= nextSwing) {
                    a.swingHammer {
                        val hit = crate.hitWithHammer(world)
                        world.emit(StageEvent.HammerHit(hit))
                        world.popWord(WORDS[(hit - 1).coerceIn(0, WORDS.lastIndex)], crate.x, crate.y - crate.height * 1.1f)
                    }
                    swings++
                    nextSwing = time + SWING_GAP_SECONDS
                }
            }
            Step.POP -> {
                if (time - poppedAt >= POP_SECONDS) launch(world)
                if (time - poppedAt >= POP_SECONDS + LINGER_SECONDS) end(world)
            }
        }
    }

    companion object {
        const val BUBBLE_SECONDS = 1.35f
        const val LONG_BUBBLE_SECONDS = 1.75f
        const val SWING_GAP_SECONDS = 0.33f

        /** 1.35 s for a short line, growing to 1.75 s for the longest (about 90 characters). */
        fun bubbleSecondsFor(line: String): Float = (0.95f + line.length * 0.01f).coerceIn(BUBBLE_SECONDS, LONG_BUBBLE_SECONDS)
        const val POP_SECONDS = 0.32f
        const val LINGER_SECONDS = 0.2f
        val WORDS = listOf("BONK", "WHACK", "CRACK!")
    }
}

/** The app was not found: the blob drops in, shrugs, says so, and leaves. Nothing opens. */
class NotFoundScene(private val entry: Entry, private val line: String) : Scene() {
    override val name = "NotFound"
    override val opensApp = false
    override val maxSeconds = 2.2f

    private var said = -1f
    private var shrugs = 0

    override fun start(world: World) {
        Moves.enter(world, entry)
    }

    override fun onUpdate(dt: Float, world: World) {
        val a = world.actor
        if (said < 0f) {
            if (a.onGround && time > 0.1f) {
                said = time
                a.pose.mood = Mood.DISAPPOINTED
                world.bubble.say(line, 1.25f)
            }
            return
        }
        val since = time - said
        // Look left, look right: nothing here by that name.
        a.pose.gazeX = if ((since * 3f).toInt() % 2 == 0) -0.9f else 0.9f
        if (shrugs < 2 && since > 0.25f + shrugs * 0.35f) {
            a.hop()
            shrugs++
        }
        if (since >= 1.35f) end(world)
    }
}

/**
 * Debug only: the blob runs and jumps under your fingers, for tuning [com.sangar.gal.sidekick.stage.MovementTuning]
 * on a real phone. Hold the left or right third to run, tap the middle to jump (hold for a higher one), and tap
 * the top strip to close.
 */
class PlaygroundScene(private val entry: Entry) : Scene() {
    override val name = "Playground"
    override val opensApp = false
    override val maxSeconds = 120f

    private var wasJumping = false

    override fun start(world: World) {
        Moves.enter(world, entry)
        val crate = world.newCrate()
        crate.floatDown(world.width * 0.5f, world.top - crate.height, world.groundY - world.tuning.bodySize - world.tuning.jumpHeight * NormalOpenScene.HEAD_REACH)
    }

    override fun onUpdate(dt: Float, world: World) {
        val a = world.actor
        val input = world.input
        if (input.close) {
            end(world)
            return
        }
        a.moveDir = when {
            input.left && !input.right -> -1
            input.right && !input.left -> 1
            else -> 0
        }
        if (input.jump && !wasJumping) a.pressJump()
        if (!input.jump && wasJumping) a.releaseJump()
        wasJumping = input.jump
        // A fresh crate to bump once the last one is gone.
        val crate = world.crates.lastOrNull()
        if (crate != null && crate.broken && crate.brokenFor > 1.2f) {
            world.crates.clear()
            val next = world.newCrate()
            next.floatDown(
                world.left + (world.right - world.left) * (0.2f + 0.6f * world.randomFloat()),
                world.top - next.height,
                world.groundY - world.tuning.bodySize - world.tuning.jumpHeight * NormalOpenScene.HEAD_REACH,
            )
        }
    }

    override fun skip(world: World) {
        // Taps are controls here, not skips. The close strip ends it.
    }
}

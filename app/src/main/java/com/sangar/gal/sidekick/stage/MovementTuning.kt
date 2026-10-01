package com.sangar.gal.sidekick.stage

import kotlin.math.sqrt

/**
 * Every number that decides how the stage blob moves, in one place so the feel can be tuned on a phone with
 * the debug playground. Distances are in dp and times in seconds; [scaled] turns them into pixels.
 *
 * The feel is a classic platformer's, nothing more: quick to get going, a little slide when stopping, a jump
 * that rises softly and falls fast, and a squash on landing. The character is our own blob.
 */
data class MovementTuning(
    /** Resting body width and height. Matches the floating Sidekick's body (116 dp window x 0.72). */
    val bodySize: Float = 84f,
    /** Top running speed. */
    val maxRunSpeed: Float = 560f,
    /** How fast it gets up to speed on the ground. */
    val runAccel: Float = 2800f,
    /** How fast it stops on the ground when there is no input. */
    val runDecel: Float = 3600f,
    /** Share of the ground acceleration it keeps in the air. */
    val airControl: Float = 0.65f,
    /** Gravity while rising with the jump still held. */
    val gravity: Float = 3400f,
    /** Falling is heavier than rising, which is what makes a jump feel snappy instead of floaty. */
    val fallGravityMultiplier: Float = 1.7f,
    /** Letting go of jump early cuts the rise short. */
    val jumpCutMultiplier: Float = 2.4f,
    /** Apex of a full jump above the ground, measured at the feet. */
    val jumpHeight: Float = 170f,
    /** Terminal falling speed. */
    val maxFallSpeed: Float = 2400f,
    /** Squash and stretch spring: stiffness and damping of the body's shape. */
    val squashStiffness: Float = 420f,
    val squashDamping: Float = 18f,
    /** How much a hard landing squashes the body, per unit of landing speed / jump speed. */
    val landSquash: Float = 0.30f,
    /** Height of the little hops the blob takes while running (it has no legs). */
    val runHopHeight: Float = 9f,
    /** Distance covered per running hop. */
    val runHopLength: Float = 62f,
) {
    /** Launch speed that reaches [jumpHeight] under [gravity]. */
    val jumpVelocity: Float get() = sqrt(2f * gravity * jumpHeight)

    /** The same tuning in pixels, for a screen with this [density]. */
    fun scaled(density: Float): MovementTuning = copy(
        bodySize = bodySize * density,
        maxRunSpeed = maxRunSpeed * density,
        runAccel = runAccel * density,
        runDecel = runDecel * density,
        gravity = gravity * density,
        jumpHeight = jumpHeight * density,
        maxFallSpeed = maxFallSpeed * density,
        runHopHeight = runHopHeight * density,
        runHopLength = runHopLength * density,
    )
}

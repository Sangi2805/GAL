package com.sangar.gal.sidekick.stage

/**
 * Things that happen on the stage that the Android side reacts to: haptics, sounds, and the moment to open the
 * app. The world collects them during a step; the stage view drains them once per frame.
 */
sealed interface StageEvent {
    data object Jumped : StageEvent

    /** [impact] is the landing speed as a share of a full jump's speed, 0..1+. */
    data class Landed(val impact: Float) : StageEvent

    /** The crate came down to rest on the ground. */
    data object CrateThud : StageEvent

    /** The blob's head hit the crate from below. */
    data object HeadBump : StageEvent

    /** A hammer blow landed; [hit] counts from 1. */
    data class HammerHit(val hit: Int) : StageEvent

    /** The crate burst and the app's icon popped out. */
    data object CrateBroken : StageEvent

    /** Open the app now. Fired at most once per scene. */
    data object LaunchApp : StageEvent

    /** The scene is over and the stage can leave. */
    data object SceneOver : StageEvent
}

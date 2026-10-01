package com.sangar.gal.sidekick.stage

import com.sangar.gal.sidekick.Mood
import com.sangar.gal.sidekick.scene.Entry
import com.sangar.gal.sidekick.scene.NormalOpenScene
import com.sangar.gal.sidekick.scene.NotFoundScene
import com.sangar.gal.sidekick.scene.RoastOpenScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The stage runs headless here: the same fixed step the phone uses, on a typical 1080 x 2400 screen. */
class StageTest {

    private val density = 2.75f
    private val step = 1f / 60f

    private fun world(seed: Int = 1): World = World(MovementTuning().scaled(density), Random(seed)).apply {
        setBounds(1080f, 2400f, 0f, 66f, 0f, 132f)
    }

    /** Runs until the world finishes or [maxSeconds] pass. Returns every event with the time it fired. */
    private fun run(world: World, maxSeconds: Float = 6f, onStep: (Float) -> Unit = {}): List<Pair<Float, StageEvent>> {
        val out = mutableListOf<Pair<Float, StageEvent>>()
        val buffer = mutableListOf<StageEvent>()
        var t = 0f
        while (t < maxSeconds && !world.finished) {
            onStep(t)
            world.step(step)
            t += step
            world.drainEvents(buffer)
            buffer.forEach { out += t to it }
            buffer.clear()
        }
        return out
    }

    @Test
    fun aFullJumpReachesItsTunedHeight() {
        val w = world()
        val a = w.actor
        a.place(500f, w.groundY, airborne = false)
        a.pressJump()
        var highest = w.groundY
        repeat(120) {
            w.step(step)
            highest = minOf(highest, a.y)
        }
        val apex = w.groundY - highest
        val tuned = w.tuning.jumpHeight
        assertTrue("apex $apex vs tuned $tuned", abs(apex - tuned) / tuned < 0.05f)
        assertTrue(a.onGround)
    }

    @Test
    fun aHopIsLowerThanAFullJump() {
        val w = world()
        val a = w.actor
        a.place(500f, w.groundY, airborne = false)
        a.hop()
        var highest = w.groundY
        repeat(120) {
            w.step(step)
            highest = minOf(highest, a.y)
        }
        assertTrue(w.groundY - highest < w.tuning.jumpHeight * 0.6f)
    }

    @Test
    fun fallingIsFasterThanRising() {
        val w = world()
        val a = w.actor
        a.place(500f, w.groundY, airborne = false)
        a.pressJump()
        var up = 0
        var down = 0
        var wasRising = true
        repeat(200) {
            w.step(step)
            if (!a.onGround) {
                if (a.vy < 0f) up++ else down++
            }
            if (a.vy > 0f) wasRising = false
        }
        assertFalse(wasRising)
        assertTrue("rise $up steps, fall $down steps", down < up)
    }

    @Test
    fun theStepIsDeterministic() {
        fun trace(): List<Float> {
            val w = world(seed = 4)
            w.start(RoastOpenScene(Entry(1000f, 900f, fromFloatingBlob = true), "Married to this app?", Mood.SMUG))
            val xs = mutableListOf<Float>()
            run(w) { xs += w.actor.x; xs += w.actor.y }
            return xs
        }
        assertEquals(trace(), trace())
    }

    @Test
    fun runningTopsOutAndStops() {
        val w = world()
        val a = w.actor
        a.place(100f, w.groundY, airborne = false)
        a.moveDir = 1
        repeat(30) { w.step(step) }
        assertEquals(w.tuning.maxRunSpeed, a.vx, 0.5f)
        a.moveDir = 0
        repeat(30) { w.step(step) }
        assertEquals(0f, a.vx, 0.001f)
    }

    @Test
    fun theWallsHoldTheBlobOnScreen() {
        val w = world()
        val a = w.actor
        a.place(200f, w.groundY, airborne = false)
        a.moveDir = -1
        repeat(120) { w.step(step) }
        assertEquals(w.left + w.tuning.bodySize / 2f, a.x, 0.01f)
    }

    @Test
    fun normalOpenBumpsTheCrateAndOpensTheAppOnceInTime() {
        for (entry in listOf(Entry(1000f, 900f, true), Entry(80f, 1500f, true), Entry.OFFSCREEN_RIGHT)) {
            val w = world()
            w.start(NormalOpenScene(entry))
            val events = run(w)
            val names = events.map { it.second }
            assertTrue("$entry: $names", StageEvent.HeadBump in names)
            assertTrue(StageEvent.CrateBroken in names)
            assertEquals(1, names.count { it == StageEvent.LaunchApp })
            val launchAt = events.first { it.second == StageEvent.LaunchApp }.first
            val overAt = events.first { it.second == StageEvent.SceneOver }.first
            assertTrue("$entry launched at $launchAt", launchAt < 3.0f)
            assertTrue("$entry over at $overAt", overAt <= 3.5f + Scene.NORMAL_FADE + 0.05f)
            assertTrue(w.finished)
        }
    }

    @Test
    fun theAppOpensWhileTheStageIsStillUp() {
        val w = world()
        w.start(NormalOpenScene(Entry(1000f, 900f, true)))
        var fadeAtLaunch = -1f
        val buffer = mutableListOf<StageEvent>()
        repeat(400) {
            if (w.finished) return@repeat
            w.step(step)
            w.drainEvents(buffer)
            if (StageEvent.LaunchApp in buffer) fadeAtLaunch = w.fade
            buffer.clear()
        }
        // Android 15 lets an overlay app start an activity from the background only while its overlay is visible.
        assertEquals(1f, fadeAtLaunch, 0.001f)
    }

    @Test
    fun roastOpenHitsThreeTimesThenOpens() {
        val w = world()
        w.start(RoastOpenScene(Entry(1000f, 900f, true), "Are you married to this app?", Mood.DISAPPOINTED))
        val events = run(w)
        val hits = events.mapNotNull { (it.second as? StageEvent.TrunkHit)?.hit }
        assertEquals(listOf(1, 2, 3), hits)
        val names = events.map { it.second }
        assertTrue(StageEvent.CrateThud in names)
        assertTrue(StageEvent.CrateBroken in names)
        assertEquals(1, names.count { it == StageEvent.LaunchApp })
        val launchAt = events.first { it.second == StageEvent.LaunchApp }.first
        assertTrue("launched at $launchAt", launchAt <= 3.5f)
        // The launch comes after the third blow, never before.
        val thirdAt = events.first { (it.second as? StageEvent.TrunkHit)?.hit == 3 }.first
        assertTrue(launchAt > thirdAt)
    }

    @Test
    fun theLongestRoastLineStillFinishesInAboutThreeAndAHalfSeconds() {
        // 90 characters, the longest the phrase builder allows with a ten letter app name.
        val line = "You have been on Tenletters long enough to have a favourite chair there. Truly, honestly."
        assertEquals(RoastOpenScene.LONG_BUBBLE_SECONDS, RoastOpenScene.bubbleSecondsFor(line))
        assertEquals(RoastOpenScene.BUBBLE_SECONDS, RoastOpenScene.bubbleSecondsFor("Married to this app?"))
        for (entry in listOf(Entry(1000f, 900f, true), Entry(80f, 1500f, true), Entry.OFFSCREEN_RIGHT)) {
            val w = world()
            w.start(RoastOpenScene(entry, line, Mood.HORRIFIED))
            val events = run(w)
            assertEquals(listOf(1, 2, 3), events.mapNotNull { (it.second as? StageEvent.TrunkHit)?.hit })
            val launchAt = events.first { it.second == StageEvent.LaunchApp }.first
            val overAt = events.first { it.second == StageEvent.SceneOver }.first
            assertTrue("launched at $launchAt", launchAt <= 3.3f)
            assertTrue("stage gone at $overAt", overAt <= 3.6f)
        }
    }

    @Test
    fun theRoastLineIsUpWhileTheBlobWalksIn() {
        val w = world()
        w.start(RoastOpenScene(Entry(1000f, 900f, true), "Married to this app?", Mood.SMUG))
        var seen = false
        run(w) { if (w.bubble.text == "Married to this app?") seen = true }
        assertTrue(seen)
    }

    @Test
    fun aTapSkipsAndStillOpensTheAppExactlyOnce() {
        for (skipAt in listOf(0.05f, 0.6f, 1.5f, 2.6f)) {
            val w = world()
            w.start(RoastOpenScene(Entry(1000f, 900f, true), "Married to this app?", Mood.SMUG))
            var skipped = false
            val events = run(w) { t ->
                if (!skipped && t >= skipAt) {
                    w.skip()
                    w.skip()
                    skipped = true
                }
            }
            val names = events.map { it.second }
            assertEquals("skip at $skipAt", 1, names.count { it == StageEvent.LaunchApp })
            assertTrue(w.finished)
            val overAt = events.first { it.second == StageEvent.SceneOver }.first
            assertTrue("skip at $skipAt ended at $overAt", overAt < skipAt + 0.2f)
        }
    }

    @Test
    fun notFoundNeverOpensAnything() {
        val w = world()
        w.start(NotFoundScene(Entry(1000f, 900f, true), "Never heard of it."))
        val names = run(w).map { it.second }
        assertFalse(StageEvent.LaunchApp in names)
        assertTrue(StageEvent.SceneOver in names)
        val w2 = world()
        w2.start(NotFoundScene(Entry.OFFSCREEN_RIGHT, "Never heard of it."))
        var skipped = false
        val names2 = run(w2) { if (!skipped) { w2.skip(); skipped = true } }.map { it.second }
        assertFalse(StageEvent.LaunchApp in names2)
    }

    @Test
    fun theParticlePoolNeverGrows() {
        val p = Particles(capacity = 8)
        repeat(50) { p.spawn(ParticleKind.DUST, 0f, 0f, 1f, 1f, life = 10f, size = 1f) }
        assertEquals(8, p.count)
        p.step(20f, 0f)
        assertEquals(0, p.count)
    }

    @Test
    fun theBubblePopsInAndGoesAway() {
        val b = SpeechBubble()
        b.say("Hi", 1f)
        b.step(0.09f)
        assertTrue(b.scale in 0.1f..1.2f)
        b.step(0.5f)
        assertEquals(1f, b.scale, 0.01f)
        b.step(1f)
        assertEquals(null, b.text)
        assertEquals(0f, b.scale, 0f)
    }
}

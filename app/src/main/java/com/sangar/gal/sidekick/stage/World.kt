package com.sangar.gal.sidekick.stage

import kotlin.random.Random

/**
 * Everything on the stage for one scene: the blob, the crate, particles, the speech bubble and a floating
 * word. Plain Kotlin, stepped at a fixed rate by [StageView], so the whole scene can be run in a unit test.
 *
 * Coordinates are pixels with the origin at the top left of the stage window, which covers the whole display.
 * [groundY] is where feet and crates rest: just above the navigation bar.
 */
class World(
    /** Tuning already scaled to pixels. */
    val tuning: MovementTuning,
    private val random: Random = Random.Default,
) {
    var width = 0f
        private set
    var height = 0f
        private set
    var left = 0f
        private set
    var right = 0f
        private set
    var top = 0f
        private set
    var groundY = 0f
        private set

    val ready: Boolean get() = width > 0f && height > 0f

    val actor = BlobActor(tuning)
    val crates = ArrayList<AppCrate>(1)
    val particles = Particles()
    val bubble = SpeechBubble()
    val floatText = FloatText()

    /** Controller input from touches, for the playground. */
    val input = Input()

    var scene: Scene? = null
        private set

    /** Seconds since the scene started. */
    var time = 0f
        private set

    /** 0..1: the stage's own opacity (ground shade, blob), animated by [fadeTo]. */
    var fade = 0f
        private set
    private var fadeTarget = 0f
    private var fadeRate = 0f

    /** True once the scene has ended and faded out; nothing more will happen. */
    var finished = false
        private set

    private val events = ArrayList<StageEvent>(8)

    class Input {
        var left = false
        var right = false
        var jump = false
        var close = false
    }

    /**
     * Sets the stage size and the system bar insets. The ground sits a little above the bottom inset, the
     * walls inside the side insets.
     */
    fun setBounds(width: Float, height: Float, insetLeft: Float, insetTop: Float, insetRight: Float, insetBottom: Float) {
        this.width = width
        this.height = height
        left = insetLeft
        right = width - insetRight
        top = insetTop
        groundY = height - insetBottom - tuning.bodySize * GROUND_MARGIN
    }

    fun start(scene: Scene) {
        this.scene = scene
        time = 0f
        finished = false
        scene.start(this)
    }

    fun step(dt: Float) {
        if (!ready || finished) return
        time += dt
        val s = scene
        s?.update(dt, this)
        actor.step(dt, this)
        for (crate in crates) crate.step(dt, this)
        particles.step(dt, tuning.gravity * tuning.fallGravityMultiplier)
        bubble.step(dt)
        floatText.step(dt)
        if (fade != fadeTarget) {
            fade = BlobActor.approach(fade, fadeTarget, fadeRate * dt)
        }
        if (s != null && s.over && fade <= 0f && !finished) {
            finished = true
            emit(StageEvent.SceneOver)
        }
    }

    /** Tap anywhere: the scene jumps to its end. */
    fun skip() {
        scene?.skip(this)
    }

    /** Fades the stage to [target] (0..1) over [seconds]. */
    fun fadeTo(target: Float, seconds: Float) {
        fadeTarget = target.coerceIn(0f, 1f)
        fadeRate = if (seconds <= 0f) Float.MAX_VALUE else 1f / seconds
        if (seconds <= 0f) fade = fadeTarget
    }

    fun emit(event: StageEvent) {
        events.add(event)
    }

    /** Moves this step's events into [into], oldest first. */
    fun drainEvents(into: MutableList<StageEvent>) {
        into.addAll(events)
        events.clear()
    }

    fun newCrate(): AppCrate {
        val size = tuning.bodySize * CRATE_SIZE
        return AppCrate(size, size).also { crates.add(it) }
    }

    fun spawnDust(x: Float, y: Float, count: Int) {
        val s = tuning.bodySize
        for (n in 0 until count) {
            val dir = if (n % 2 == 0) -1f else 1f
            particles.spawn(
                ParticleKind.DUST,
                x + dir * random.nextFloat() * s * 0.3f,
                y - random.nextFloat() * s * 0.05f,
                dir * s * (1.2f + random.nextFloat() * 1.6f),
                -s * (0.3f + random.nextFloat() * 0.6f),
                life = 0.35f + random.nextFloat() * 0.2f,
                size = s * (0.08f + random.nextFloat() * 0.06f),
            )
        }
    }

    fun spawnChips(x: Float, y: Float, count: Int) {
        val s = tuning.bodySize
        for (n in 0 until count) {
            particles.spawn(
                ParticleKind.CHIP,
                x + (random.nextFloat() - 0.5f) * s * 0.6f,
                y + (random.nextFloat() - 0.5f) * s * 0.4f,
                (random.nextFloat() - 0.5f) * s * 6f,
                -s * (3f + random.nextFloat() * 4f),
                life = 0.6f + random.nextFloat() * 0.3f,
                size = s * (0.07f + random.nextFloat() * 0.07f),
                spin = (random.nextFloat() - 0.5f) * 1400f,
            )
        }
    }

    fun spawnStars(x: Float, y: Float, count: Int) {
        val s = tuning.bodySize
        Bursts.ring(random, count, s * 4.5f) { vx, vy ->
            particles.spawn(ParticleKind.STAR, x, y, vx, vy, life = 0.5f + random.nextFloat() * 0.2f, size = s * 0.12f, spin = 360f)
        }
    }

    /** A word over a hit, e.g. "BONK". */
    fun popWord(word: String, x: Float, y: Float) {
        floatText.show(word, x, y, tilt = (random.nextFloat() - 0.5f) * 24f, rise = tuning.bodySize * 0.9f)
    }

    fun randomFloat(): Float = random.nextFloat()

    companion object {
        /** Ground clearance above the bottom inset, in body sizes. */
        const val GROUND_MARGIN = 0.35f

        /** Crate size in body sizes. */
        const val CRATE_SIZE = 0.92f
    }
}

package com.sangar.gal.sidekick.stage

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Kinds of particle; the renderer gives each its look. */
enum class ParticleKind { DUST, CHIP, STAR }

/**
 * A fixed pool of particles, so a scene never allocates while it plays. When the pool is full the oldest
 * slot is reused.
 */
class Particles(capacity: Int = 64) {
    val kind = arrayOfNulls<ParticleKind>(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val age = FloatArray(capacity)
    val life = FloatArray(capacity)
    val size = FloatArray(capacity)
    val spin = FloatArray(capacity)
    val angle = FloatArray(capacity)
    private var next = 0

    val capacity: Int get() = kind.size

    fun alive(i: Int): Boolean = kind[i] != null

    fun spawn(kind: ParticleKind, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, spin: Float = 0f) {
        val i = next
        next = (next + 1) % capacity
        this.kind[i] = kind
        this.x[i] = x
        this.y[i] = y
        this.vx[i] = vx
        this.vy[i] = vy
        this.age[i] = 0f
        this.life[i] = life
        this.size[i] = size
        this.spin[i] = spin
        this.angle[i] = 0f
    }

    fun step(dt: Float, gravity: Float) {
        for (i in kind.indices) {
            val k = kind[i] ?: continue
            age[i] += dt
            if (age[i] >= life[i]) {
                kind[i] = null
                continue
            }
            when (k) {
                ParticleKind.DUST -> {
                    vx[i] *= 1f - 3f * dt
                    vy[i] *= 1f - 3f * dt
                }
                ParticleKind.CHIP -> vy[i] += gravity * dt
                ParticleKind.STAR -> {
                    vx[i] *= 1f - 2f * dt
                    vy[i] = vy[i] * (1f - 2f * dt) + gravity * 0.15f * dt
                }
            }
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            angle[i] += spin[i] * dt
        }
    }

    /** 0..1 remaining life, for fading. */
    fun fade(i: Int): Float = (1f - age[i] / life[i]).coerceIn(0f, 1f)

    fun clear() {
        kind.fill(null)
    }

    val count: Int get() = kind.count { it != null }
}

/** A speech bubble over the blob. The renderer lays the text out; this only knows what and when. */
class SpeechBubble {
    var text: String? = null
        private set
    var age = 0f
        private set
    private var duration = 0f

    fun say(text: String, seconds: Float) {
        this.text = text
        age = 0f
        duration = seconds
    }

    fun clear() {
        text = null
    }

    fun step(dt: Float) {
        if (text == null) return
        age += dt
        if (age >= duration) text = null
    }

    /** Pops in with a little overshoot and shrinks away at the end. */
    val scale: Float
        get() {
            if (text == null) return 0f
            val inT = (age / 0.18f).coerceIn(0f, 1f)
            val pop = 1f + 0.12f * sin(inT * PI.toFloat())
            val outT = ((duration - age) / 0.12f).coerceIn(0f, 1f)
            return inT * pop * outT
        }
}

/** A word that pops off a hit ("BONK") and floats up. */
class FloatText {
    var text: String? = null
        private set
    var x = 0f
    var y = 0f
    var age = 0f
        private set
    var tilt = 0f
        private set
    private var rise = 0f

    /** [rise] is how fast it floats up, pixels per second. */
    fun show(text: String, x: Float, y: Float, tilt: Float, rise: Float) {
        this.text = text
        this.x = x
        this.y = y
        this.tilt = tilt
        this.rise = rise
        age = 0f
    }

    fun step(dt: Float) {
        if (text == null) return
        age += dt
        y -= rise * dt
        if (age >= LIFE) text = null
    }

    val scale: Float get() = if (text == null) 0f else (age / 0.08f).coerceIn(0f, 1f) * (1f + 0.25f * (1f - (age / 0.2f).coerceIn(0f, 1f)))
    val alpha: Float get() = if (text == null) 0f else (1f - ((age - 0.35f) / 0.2f).coerceIn(0f, 1f))

    companion object {
        const val LIFE = 0.55f
    }
}

/** Helpers for bursts of particles. */
internal object Bursts {
    fun ring(random: Random, count: Int, speed: Float, emit: (vx: Float, vy: Float) -> Unit) {
        for (n in 0 until count) {
            val a = (n.toFloat() / count) * 2f * PI.toFloat() + random.nextFloat() * 0.6f
            val s = speed * (0.6f + 0.6f * random.nextFloat())
            emit(cos(a) * s, sin(a) * s)
        }
    }
}

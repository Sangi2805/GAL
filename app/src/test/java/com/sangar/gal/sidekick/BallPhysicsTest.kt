package com.sangar.gal.sidekick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BallPhysicsTest {

    private fun run(ball: BallPhysics, seconds: Float, left: Float = 0f, right: Float = 1000f, floor: Float = 500f) {
        repeat((seconds * 60).toInt()) { ball.step(1f / 60f, left, right, floor) }
    }

    @Test
    fun aDroppedBallBouncesLowerEachTimeAndComesToRest() {
        val ball = BallPhysics(x = 500f, y = 100f, radius = 20f, gravity = 3000f)
        run(ball, 6f)
        assertTrue(ball.atRest(500f, slow = 30f))
        assertEquals(480f, ball.y, 0.6f)
    }

    @Test
    fun itStaysBetweenTheWallsAndComesBack() {
        val ball = BallPhysics(x = 500f, y = 480f, radius = 20f, gravity = 3000f)
        ball.vx = 2500f
        var hitRight = false
        repeat(240) {
            ball.step(1f / 60f, 0f, 1000f, 500f)
            if (ball.x >= 979f) hitRight = true
            assertTrue(ball.x in 20f..980f)
        }
        assertTrue(hitRight)
        assertFalse(ball.gone(0f, 1000f))
    }

    @Test
    fun withTheWallsOffTheLastKickLeavesTheScreen() {
        val ball = BallPhysics(x = 500f, y = 480f, radius = 20f, gravity = 3000f)
        ball.walls = false
        ball.vx = 3000f
        ball.vy = -900f
        run(ball, 1.5f)
        assertTrue(ball.gone(0f, 1000f))
    }
}

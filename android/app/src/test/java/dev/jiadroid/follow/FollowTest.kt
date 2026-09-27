package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class FollowTest {
    private val hfov = Math.toRadians(70.0)

    @Test
    fun markerOnTheRightTurnsRight() {
        val decision = decide(Scene(true, 0.4f, 1.2f))
        assertEquals("Marker is right", decision.situation)
        assertEquals("TURN RIGHT", decision.command)
        assertTrue(decision.yaw < 0f)
        assertTrue(decision.headYaw > 0f)
    }

    @Test
    fun markerOnTheLeftTurnsLeft() {
        val decision = decide(Scene(true, -0.4f, 1.2f))
        assertEquals("TURN LEFT", decision.command)
        assertTrue(decision.yaw > 0f)
        assertTrue(decision.headYaw < 0f)
    }

    @Test
    fun centeredAndFarWalksForward() {
        val decision = decide(Scene(true, 0f, 2f))
        assertEquals("Marker is too far", decision.situation)
        assertEquals("FORWARD", decision.command)
        assertEquals(0.12f, decision.forward, 0.0001f)
        assertEquals(0f, decision.yaw, 0.0001f)
    }

    @Test
    fun centeredAndNearSlowsDown() {
        val decision = decide(Scene(true, 0f, 0.5f))
        assertEquals("SLOW DOWN", decision.command)
        assertEquals(0.05f, decision.forward, 0.0001f)
    }

    @Test
    fun tooCloseStops() {
        val decision = decide(Scene(true, 0f, 0.2f))
        assertEquals("Marker is too close", decision.situation)
        assertEquals("STOP", decision.command)
        assertEquals(0f, decision.forward, 0.0001f)
        assertEquals(0f, decision.headYaw, 0.0001f)
    }

    @Test
    fun hiddenMarkerStops() {
        val decision = decide(Scene(false, 0.4f, 1f))
        assertEquals("Marker is lost", decision.situation)
        assertEquals("STOP", decision.command)
    }

    @Test
    fun measureReadsDistanceAndRightwardAngle() {
        val scene = measure(800f, 100f, 1000, hfov, 0.06f)
        assertTrue(scene.visible)
        assertTrue(scene.angle > 0.3f)
        assertEquals(0.43f, scene.distance, 0.03f)
    }

    @Test
    fun measureRejectsATinyCode() {
        assertFalse(measure(500f, 4f, 1000, hfov, 0.06f).visible)
    }

    @Test
    fun duckWalksForwardInItsFacingDirection() {
        val pose = Pose(0f, 0f, (PI / 2.0).toFloat())
        stepPose(pose, 0.1f, 0f, 0f, 1f)
        assertEquals(0f, pose.x, 0.0001f)
        assertEquals(0.1f, pose.y, 0.0001f)
    }
}

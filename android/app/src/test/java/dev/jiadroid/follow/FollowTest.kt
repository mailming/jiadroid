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
    fun markerTenFeetAwayIsNotTooClose() {
        // A 120 mm code at about 10 feet covers ~4% of a portrait webcam frame.
        val fov = uprightFieldOfView(90, 960, 1280)
        val scene = measure(480f, 38f, 960, fov, 0.12f)
        assertTrue(scene.visible)
        assertTrue(scene.distance > 2f)
        assertEquals("Marker is too far", decide(scene).situation)
    }

    @Test
    fun adultAcrossTheRoomIsFollowed() {
        // 1.7 m adult about 3 m away, portrait webcam frame 960 wide.
        val fov = uprightFieldOfView(90, 960, 1280)
        val fx = 480.0 / kotlin.math.tan(fov / 2.0)
        val shoulderPx = (1.7 * 0.22 * fx / 3.0).toFloat()
        val torsoPx = (1.7 * 0.29 * fx / 3.0).toFloat()
        val shoulders = Point(480f - shoulderPx / 2, 400f) to Point(480f + shoulderPx / 2, 400f)
        val hips = Point(470f, 400f + torsoPx) to Point(490f, 400f + torsoPx)
        val scene = measurePerson(shoulders, hips, 960, fov, 1.7f)
        assertTrue(scene.visible)
        assertEquals(3f, scene.distance, 0.05f)
        assertEquals("Person is too far", decide(scene, "Person").situation)
    }

    @Test
    fun personTurnedSidewaysIsNotReadAsFar() {
        val fov = uprightFieldOfView(90, 960, 1280)
        val fx = 480.0 / kotlin.math.tan(fov / 2.0)
        val torsoPx = (1.7 * 0.29 * fx / 0.3).toFloat()
        val shoulders = Point(470f, 100f) to Point(490f, 100f)
        val hips = Point(470f, 100f + torsoPx) to Point(490f, 100f + torsoPx)
        val scene = measurePerson(shoulders, hips, 960, fov, 1.7f)
        assertEquals("Person is too close", decide(scene, "Person").situation)
    }

    @Test
    fun greetingIsSpokenBack() {
        val spoken = reply("hello there", "Person is centered")
        assertEquals("Hi! I'm Vicky, the robot with phone eyes. What should we explore?", spoken.say)
        assertEquals(Emotion.HAPPY, spoken.emotion)
    }

    @Test
    fun askingWhatItSeesUsesTheCamera() {
        assertEquals("Hmm, my eyes lost you. Wave so I can find you!", reply("what do you see", "Person is lost").say)
        assertEquals(Emotion.CONFUSED, reply("what do you see", "Person is lost").emotion)
        assertEquals("I see you! Person is left.", reply("what are you looking at", "Person is left").say)
    }

    @Test
    fun wakeNameIsRequiredBeforeAnswering() {
        assertFalse(parseAttention("what do you see", "vicky").addressed)
        val hey = parseAttention("hey Vicky, stop", "vicky")
        assertTrue(hey.addressed)
        assertEquals("stop", hey.utterance)
        val only = parseAttention("Vicky", "vicky")
        assertTrue(only.addressed)
        assertEquals("", only.utterance)
        assertEquals("Yes?", reply("", "Person is centered").say)
        assertEquals(Emotion.CURIOUS, reply("", "Person is centered").emotion)
    }

    @Test
    fun wakeOpensAConversationWindowWithoutRepeatingTheName() {
        val session = AttentionSession(holdMs = 30_000L)
        val wake = session.consider("hey Vicky", "vicky", requireName = true, nowMs = 1_000L)
        assertTrue(wake.addressed)
        assertTrue(wake.newConversation)
        assertEquals("", wake.utterance)
        val followUp = session.consider("what do you see", "vicky", requireName = true, nowMs = 5_000L)
        assertTrue(followUp.addressed)
        assertFalse(followUp.newConversation)
        assertEquals("what do you see", followUp.utterance)
        val late = session.consider("are you there", "vicky", requireName = true, nowMs = 40_000L)
        assertFalse(late.addressed)
        val rewake = session.consider("hey Vicky, what's my name", "vicky", requireName = true, nowMs = 41_000L)
        assertTrue(rewake.addressed)
        assertTrue(rewake.newConversation)
        assertEquals("what's my name", rewake.utterance)
    }

    @Test
    fun duckWalksForwardInItsFacingDirection() {
        val pose = Pose(0f, 0f, (PI / 2.0).toFloat())
        stepPose(pose, 0.1f, 0f, 0f, 1f)
        assertEquals(0f, pose.x, 0.0001f)
        assertEquals(0.1f, pose.y, 0.0001f)
    }

    @Test
    fun lostTargetStartsASearchSpinThenClearsOnReacquire() {
        val search = LostSearch(graceMs = 200L, maxSearchMs = 5_000L, sliceMs = 1_000L, searchYaw = 0.5f)
        val hidden = decide(Scene(false, 0f, 0f))
        search.enrich(Scene(false, 0f, 0f), hidden, nowMs = 0L, subject = "Marker")
        val looking = search.enrich(Scene(false, 0f, 0f), hidden, nowMs = 1_000L, subject = "Marker")
        assertEquals("SEARCH", looking.command)
        assertTrue(search.searching)
        search.enrich(Scene(true, 0f, 1f), decide(Scene(true, 0f, 1f)), nowMs = 2_000L, subject = "Marker")
        assertFalse(search.searching)
    }
}

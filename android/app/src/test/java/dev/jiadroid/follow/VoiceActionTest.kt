package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActionTest {
    @Test
    fun turnRightNowBecomesAClockwiseTurn() {
        val action = parseVoiceAction("turn right now")!!
        assertEquals(VoiceMotion.TURN_RIGHT, action.motion)
        assertTrue(action.decision().yaw < 0f)
    }

    @Test
    fun explicitMovementCommandsAreRecognized() {
        assertEquals(VoiceMotion.TURN_LEFT, parseVoiceAction("rotate to the left")?.motion)
        assertEquals(VoiceMotion.TURN_LEFT, parseVoiceAction("go left")?.motion)
        assertEquals(VoiceMotion.TURN_LEFT, parseVoiceAction("left")?.motion)
        assertEquals(VoiceMotion.TURN_RIGHT, parseVoiceAction("turn to right")?.motion)
        assertEquals(VoiceMotion.FORWARD, parseVoiceAction("please move forward")?.motion)
        assertEquals(VoiceMotion.BACKWARD, parseVoiceAction("drive backwards")?.motion)
        assertEquals(VoiceMotion.STOP, parseVoiceAction("stop right now")?.motion)
        assertEquals(VoiceMotion.FOLLOW, parseVoiceAction("follow me")?.motion)
    }

    @Test
    fun conversationAndNegatedCommandsDoNotMove() {
        assertNull(parseVoiceAction("right now the weather is nice"))
        assertNull(parseVoiceAction("do not turn right"))
        assertNull(parseVoiceAction("what happens if a robot moves forward"))
        assertNull(parseVoiceAction("looking forward to dinner"))
    }

    @Test
    fun movementIsBriefAndStopIsLatched() {
        assertEquals(220L, VoiceAction(VoiceMotion.TURN_RIGHT).durationMs)
        assertEquals(-0.20f, VoiceAction(VoiceMotion.TURN_RIGHT).decision().yaw, 0.0001f)
        assertEquals(1400L, VoiceAction(VoiceMotion.FORWARD).durationMs)
        assertEquals(0L, VoiceAction(VoiceMotion.STOP).durationMs)
        assertEquals("Turning left.", VoiceAction(VoiceMotion.TURN_LEFT).spokenAck)
    }
}

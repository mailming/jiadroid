package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlTest {
    @Test
    fun speedsMatchFollowerAndVoice() {
        assertEquals(0.12f, ManualControl.forward().forward, 0.0001f)
        assertEquals(0.05f, ManualControl.slowForward().forward, 0.0001f)
        assertEquals(-0.12f, ManualControl.backward().forward, 0.0001f)
        assertEquals(-0.05f, ManualControl.slowBackward().forward, 0.0001f)
        assertTrue(ManualControl.left().yaw > 0f)
        assertTrue(ManualControl.left().forward > 0f)
        assertTrue(ManualControl.right().yaw < 0f)
        assertTrue(ManualControl.right().forward > 0f)
        assertEquals("SEARCH", ManualControl.search().command)
        assertTrue(ManualControl.search().yaw > 0f)
        assertEquals("STOP", ManualControl.stop().command)
    }
}

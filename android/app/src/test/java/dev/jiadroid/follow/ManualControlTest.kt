package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlTest {
    @Test
    fun speedsMatchFollowerAndVoice() {
        assertEquals(0.06f, ManualControl.forward().forward, 0.0001f)
        assertEquals(0.04f, ManualControl.slowForward().forward, 0.0001f)
        assertEquals(-0.06f, ManualControl.backward().forward, 0.0001f)
        assertEquals(-0.04f, ManualControl.slowBackward().forward, 0.0001f)
        assertEquals(ManualControl.left().forward, ManualControl.right().forward, 0.0001f)
        assertEquals(ManualControl.left().yaw, -ManualControl.right().yaw, 0.0001f)
        assertTrue(ManualControl.left().yaw > 0f)
        assertEquals("SEARCH", ManualControl.search().command)
        assertTrue(ManualControl.search().yaw > 0f)
        assertEquals("STOP", ManualControl.stop().command)
    }
}

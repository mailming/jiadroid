package dev.jiadroid.follow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTest {
    @Test
    fun bargeInNeedsHeyNameNotNameAlone() {
        assertTrue(isBargeInWake("hey Vicky stop", "Vicky"))
        assertTrue(isBargeInWake("hi vicky, turn left", "vicky"))
        assertFalse(isBargeInWake("I'm Vicky, the robot with phone eyes", "Vicky"))
        assertFalse(isBargeInWake("Vicky", "Vicky"))
        assertFalse(isBargeInWake("going forward", "Vicky"))
    }
}

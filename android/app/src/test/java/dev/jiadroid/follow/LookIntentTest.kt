package dev.jiadroid.follow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookIntentTest {
    @Test
    fun visionPhrasesAskForAPhoto() {
        assertTrue(wantsCameraLook("look at this"))
        assertTrue(wantsCameraLook("hey Vicky solve this math problem"))
        assertTrue(wantsCameraLook("read that"))
        assertTrue(wantsCameraLook("what's on the screen"))
        assertTrue(wantsCameraLook("what is on the paper"))
        assertTrue(wantsCameraLook("help me with this"))
        assertTrue(wantsCameraLook("what does this say"))
        assertTrue(wantsCameraLook("can you see this"))
        assertTrue(wantsCameraLook("what's this code"))
    }

    @Test
    fun followSightQuestionsStayOnTextPath() {
        assertFalse(wantsCameraLook("what are you looking at"))
        assertFalse(wantsCameraLook("what do you see"))
        assertFalse(wantsCameraLook("hello there"))
        assertFalse(wantsCameraLook("turn left"))
    }
}

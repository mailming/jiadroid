package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotionTest {
    @Test
    fun trailingTagIsStrippedBeforeSpeech() {
        val spoken = parseSpokenReply("Hello there. <<happy>>")
        assertEquals("Hello there.", spoken.say)
        assertEquals(Emotion.HAPPY, spoken.emotion)
    }

    @Test
    fun jsonReplyParsesSayAndEmotion() {
        val spoken = parseSpokenReply("""{"say":"I see you.","emotion":"curious"}""")
        assertEquals("I see you.", spoken.say)
        assertEquals(Emotion.CURIOUS, spoken.emotion)
    }

    @Test
    fun unknownTagFallsBackToNeutral() {
        val spoken = parseSpokenReply("Okay <<mystery>>")
        assertEquals("Okay", spoken.say)
        assertEquals(Emotion.NEUTRAL, spoken.emotion)
    }

    @Test
    fun localGreetingIsHappy() {
        val spoken = reply("hello", "Person is centered")
        assertEquals(Emotion.HAPPY, spoken.emotion)
        assertEquals(true, spoken.say.contains("Vicky"))
    }

    @Test
    fun nameOnlyWakeIsCurious() {
        assertEquals(Emotion.CURIOUS, reply("", "Person is centered").emotion)
    }
}

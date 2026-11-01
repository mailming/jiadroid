package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class BrainStreamTest {
    @Test
    fun anthropicStreamStopsWhenEmotionTagArrives() {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi there!"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" <<happy>>"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" trailing noise"}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()
        val raw = readSseUntilComplete(BufferedReader(StringReader(sse)), anthropic = true)
        assertEquals("Hi there! <<happy>>", raw)
        assertEquals(Emotion.HAPPY, parseSpokenReply(raw).emotion)
    }

    @Test
    fun openAiStreamStopsOnFinishReason() {
        val sse = """
            data: {"choices":[{"delta":{"content":"Okay!"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":" <<curious>>"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()
        val raw = readSseUntilComplete(BufferedReader(StringReader(sse)), anthropic = false)
        assertEquals("Okay! <<curious>>", raw)
    }

    @Test
    fun completeReplyDetectsTrailingTag() {
        assertFalse(isCompleteSpokenReply("Almost ready"))
        assertTrue(isCompleteSpokenReply("Almost ready <<excited>>"))
        assertTrue(isCompleteSpokenReply("""{"say":"Hi","emotion":"happy"}"""))
    }
}

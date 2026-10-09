package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkMemoryTest {
    @Test
    fun recallsLastUserLine() {
        val memory = TalkMemory()
        memory.rememberExchange("I like robots", "Cool.")
        memory.rememberExchange("bring me water", "Okay.")
        assertEquals("You said bring me water.", memory.recallReply("what did I just say")?.say)
    }

    @Test
    fun ignoresNonRecallQuestions() {
        assertNull(TalkMemory().recallReply("follow me"))
    }

    @Test
    fun capsMessageHistory() {
        val memory = TalkMemory(maxMessages = 4)
        repeat(5) { i ->
            memory.rememberExchange("user $i", "bot $i")
        }
        val turns = memory.chatTurns()
        assertEquals(4, turns.size)
        assertEquals("user 3", turns.first().text)
        assertEquals("bot 4", turns.last().text)
    }
}

class AudienceKbTest {
    @Test
    fun persistsNameAcrossReload() {
        var stored: String? = null
        val first = AudienceKb(loadJson = { stored }, saveJson = { stored = it })
        first.rememberFrom("My name is Jia")
        assertTrue(stored!!.contains("Jia"))

        val second = AudienceKb(loadJson = { stored }, saveJson = { stored = it })
        assertEquals("You're Jia.", second.recallReply("what's my name")?.say)
        assertTrue(second.promptBlock().contains("Audience knowledge base"))
    }

    @Test
    fun remembersFreeformFacts() {
        var stored: String? = null
        val kb = AudienceKb(loadJson = { stored }, saveJson = { stored = it })
        kb.rememberFrom("Remember that the wifi password is orchid")
        assertTrue(kb.promptBlock().contains("wifi password is orchid"))
        assertTrue(kb.recallReply("what do you remember")!!.say.contains("orchid"))
    }

    @Test
    fun doesNotTreatIAmFollowingAsAName() {
        var stored: String? = null
        val kb = AudienceKb(loadJson = { stored }, saveJson = { stored = it })
        kb.rememberFrom("I am following")
        assertEquals(
            "I don't know your name yet. Tell me, and I'll remember.",
            kb.recallReply("what's my name")?.say,
        )
    }

    @Test
    fun clearForgetCommandWipesKb() {
        var stored: String? = null
        val kb = AudienceKb(loadJson = { stored }, saveJson = { stored = it })
        kb.rememberFrom("My name is Jia")
        assertEquals("Okay, I cleared what I saved about you on this phone.", kb.recallReply("forget everything")?.say)
        assertEquals(0, kb.noteCount())
    }

    @Test
    fun pinnedNameSurvivesFactCapCompaction() {
        var stored: String? = null
        var now = 1_000_000L
        val kb = AudienceKb(
            loadJson = { stored },
            saveJson = { stored = it },
            maxFacts = 4,
            factTtlMs = 30L * 24 * 60 * 60 * 1000,
            clockMs = { now },
        )
        kb.rememberFrom("My name is Jia")
        repeat(8) { i ->
            now += 1_000
            kb.rememberFrom("Remember that detail number $i")
        }
        assertEquals("You're Jia.", kb.recallReply("what's my name")?.say)
        assertTrue(kb.factCount() <= 4)
        assertTrue(kb.promptBlock().contains("summary") || kb.factCount() <= 4)
    }

    @Test
    fun ttlDropsOldFactsButKeepsPinnedName() {
        var stored: String? = null
        var now = 1_000_000L
        val day = 24L * 60 * 60 * 1000
        val kb = AudienceKb(
            loadJson = { stored },
            saveJson = { stored = it },
            maxFacts = 16,
            factTtlMs = 30 * day,
            clockMs = { now },
        )
        kb.rememberFrom("My name is Jia")
        kb.rememberFrom("Remember that the wifi password is orchid")
        now += 31 * day
        kb.maintainNow()
        assertEquals("You're Jia.", kb.recallReply("what's my name")?.say)
        assertFalse(kb.promptBlock().contains("orchid"))
    }

    @Test
    fun compactFoldsOverflowIntoSummary() {
        var stored: String? = null
        var now = 1_000_000L
        val kb = AudienceKb(
            loadJson = { stored },
            saveJson = { stored = it },
            maxFacts = 3,
            factTtlMs = 30L * 24 * 60 * 60 * 1000,
            clockMs = { now },
        )
        repeat(6) { i ->
            now += 1_000
            kb.rememberFrom("Remember that item $i")
        }
        assertTrue(kb.factCount() <= 3)
        assertTrue(kb.promptBlock().contains("summary"))
        assertTrue(kb.promptBlock().contains("item"))
    }
}

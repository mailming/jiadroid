package dev.jiadroid.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallTest {
    @Test
    fun callMyMomIsRecognized() {
        val request = parseCallRequest("eva call my mom")!!
        assertEquals("mom", request.who)
        assertEquals("Mom", request.displayWho)
        assertEquals("Calling Mom.", request.spokenAck)
    }

    @Test
    fun commonCallPhrasesWork() {
        assertEquals("dad", parseCallRequest("call dad")?.who)
        assertEquals("mom", parseCallRequest("please phone my mum")?.who)
        assertEquals("jia", parseCallRequest("dial Jia")?.who)
        assertEquals("grandma", parseCallRequest("can you call my grandma")?.who)
        assertEquals("mom", parseCallRequest("make a call to mom")?.who)
        assertEquals("mom", parseCallRequest("ring my mother")?.who)
    }

    @Test
    fun nameSettingAndNegationAreNotCalls() {
        assertNull(parseCallRequest("call me Jia"))
        assertNull(parseCallRequest("don't call mom"))
        assertNull(parseCallRequest("what do you call this"))
        assertNull(parseCallRequest("follow me"))
    }

    @Test
    fun phoneNumbersNormalize() {
        assertEquals("5551234567", normalizePhoneNumber("555-123-4567"))
        assertEquals("+15551234567", normalizePhoneNumber("+1 (555) 123-4567"))
        assertNull(normalizePhoneNumber("123"))
    }

    @Test
    fun contactNumberFactsParse() {
        assertEquals("mom" to "5551234567", parseContactNumberFact("mom's number is 555-123-4567"))
        assertEquals("dad" to "5559876543", parseContactNumberFact("my dad's phone is 555.987.6543"))
        assertEquals("jia" to "+15551112222", parseContactNumberFact("Jia's phone number is +1 555 111 2222"))
    }

    @Test
    fun relationshipAliasesCollapse() {
        assertEquals("contact-mom", canonicalContactKey("mother"))
        assertEquals("contact-mom", canonicalContactKey("mum"))
        assertEquals("contact-dad", canonicalContactKey("father"))
        assertEquals("Mom", canonicalContactLabel("mum"))
    }

    @Test
    fun audienceKbStoresAndRecallsContactNumbers() {
        var stored = ""
        val kb = AudienceKb(loadJson = { stored }, saveJson = { stored = it })
        assertTrue(kb.rememberFrom("mom's number is 555-123-4567").isNotEmpty())
        assertEquals("5551234567", kb.contactNumber("mum"))
        assertEquals("Mom's number is 5551234567.", kb.recallReply("what's mom's number")?.say)
        assertTrue(kb.promptBlock().contains("contact-mom"))
    }
}

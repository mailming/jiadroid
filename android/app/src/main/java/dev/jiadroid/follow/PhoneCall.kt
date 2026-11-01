package dev.jiadroid.follow

/**
 * Phone-call requests recognized locally from a completed speech transcript.
 *
 * Like [VoiceAction], this stays off the model: only an explicit “call …” phrase
 * can open the dialer, so a chat hallucination cannot ring someone.
 */
data class CallRequest(val who: String) {
    val displayWho: String
        get() = canonicalContactLabel(who)

    val spokenAck: String
        get() = "Calling $displayWho."

    val missingNumberPrompt: String
        get() = "I don't have $displayWho's number yet. " +
            "Say $displayWho's number is …, or allow Contacts so I can look them up."

    val notFoundPrompt: String
        get() = "I couldn't find $displayWho. Tell me $displayWho's number is …"
}

fun parseCallRequest(transcript: String): CallRequest? {
    val text = transcript.lowercase().replace(Regex("""[^\p{L}\p{N}'+]+"""), " ").trim()
    if (text.isEmpty()) return null
    // Negation and name-setting are not authority to dial.
    if (Regex("""\b(don't|dont|do not|never|not)\b""").containsMatchIn(text)) return null
    if (Regex("""\bcall me\b""").containsMatchIn(text)) return null
    if (Regex("""\b(what|who|how|why|when)\b""").containsMatchIn(text) &&
        Regex("""\bcall\b""").containsMatchIn(text)
    ) {
        return null
    }

    val who = CALL_PATTERNS.asSequence()
        .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.trim() }
        .mapNotNull { cleanCallWho(it) }
        .map { canonicalContactId(it) }
        .firstOrNull()
        ?: return null
    return CallRequest(who)
}

/** Collapse relationship nicknames onto one KB key (`contact-mom`, …). */
fun canonicalContactKey(who: String): String = "contact-${canonicalContactId(who)}"

fun canonicalContactLabel(who: String): String =
    canonicalContactId(who).split(Regex("""\s+|-+"""))
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.titlecase() } }

fun canonicalContactId(who: String): String {
    val cleaned = who.trim().lowercase().replace(Regex("""\s+"""), " ")
    if (cleaned.isEmpty()) return cleaned
    return CONTACT_ALIASES[cleaned] ?: cleaned
}

/** Digits (optional leading +) suitable for tel: / ACTION_CALL. */
fun normalizePhoneNumber(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val digits = trimmed.filter { it.isDigit() }
    if (digits.length < 7 || digits.length > 16) return null
    return if (trimmed.startsWith("+")) "+$digits" else digits
}

/** Pull “mom's number is 555…” from a user line. */
fun parseContactNumberFact(transcript: String): Pair<String, String>? {
    val lower = transcript.lowercase().replace(Regex("""\s+"""), " ").trim()
    if (lower.isEmpty()) return null
    val match = CONTACT_NUMBER_FACT.find(lower) ?: return null
    val who = cleanCallWho(match.groupValues.getOrNull(1).orEmpty()) ?: return null
    val number = normalizePhoneNumber(match.groupValues.getOrNull(2).orEmpty()) ?: return null
    return who to number
}

private fun cleanCallWho(who: String): String? {
    var w = who.trim()
        .replace(Regex("""^(the|a|an|to)\s+"""), "")
        .replace(Regex("""\s+(for me|now|please|real quick|quickly)$"""), "")
        .trim()
    if (w.isEmpty() || w in CALL_WHO_STOP) return null
    if (w.split(Regex("""\s+""")).size > 4) return null
    return w
}

private val CALL_PATTERNS = listOf(
    // Prefer “make a call to …” before bare “call …”, or “call” steals “to mom”.
    Regex(
        """\b(?:please\s+)?(?:make|place)\s+(?:a\s+)?(?:phone\s+)?call\s+to\s+(?:my\s+)?(.+?)(?:\s+please)?$""",
    ),
    Regex(
        """\b(?:please\s+)?(?:can you\s+|could you\s+)?(?:call|phone|dial|ring)\s+(?:up\s+)?(?:my\s+)?(.+?)(?:\s+please)?$""",
    ),
)

private val CONTACT_NUMBER_FACT = Regex(
    """(?:my\s+)?([a-z][a-z'’\s-]{0,40}?)(?:'s)?\s+(?:phone\s+)?(?:number|phone)\s+is\s+([+\d][\d\s().-]{6,24})""",
)

private val CALL_WHO_STOP = setOf(
    "me", "you", "it", "that", "this", "them", "him", "her", "us",
    "someone", "somebody", "anyone", "anybody", "everyone", "everybody",
    "back", "home", "out", "later",
)

private val CONTACT_ALIASES = mapOf(
    "mom" to "mom",
    "mum" to "mom",
    "mama" to "mom",
    "mommy" to "mom",
    "mother" to "mom",
    "dad" to "dad",
    "daddy" to "dad",
    "papa" to "dad",
    "father" to "dad",
    "pa" to "dad",
    "pop" to "dad",
    "grandma" to "grandma",
    "grandmother" to "grandma",
    "nana" to "grandma",
    "granny" to "grandma",
    "gran" to "grandma",
    "grandpa" to "grandpa",
    "grandfather" to "grandpa",
    "granddad" to "grandpa",
    "grandad" to "grandpa",
    "gramps" to "grandpa",
    "sis" to "sister",
    "sister" to "sister",
    "bro" to "brother",
    "brother" to "brother",
    "wife" to "wife",
    "husband" to "husband",
    "girlfriend" to "girlfriend",
    "boyfriend" to "boyfriend",
    "partner" to "partner",
    "aunt" to "aunt",
    "uncle" to "uncle",
    "cousin" to "cousin",
)

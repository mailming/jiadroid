package dev.jiadroid.follow

/** Default wake name when the user has not assigned one. */
const val DEFAULT_ROBOT_NAME = "lulu"

/** How long conversation stays open after the wake name (or each reply). */
const val ATTENTION_HOLD_MS = 30_000L

/**
 * Whether a transcript is addressed to the robot, and the words after the wake name.
 *
 * Background chat is ignored until someone says the name (e.g. "hey Lulu, stop").
 * After that, follow-ups without the name still count until [ATTENTION_HOLD_MS] of silence.
 */
data class Attention(val addressed: Boolean, val utterance: String)

fun normalizeRobotName(name: String): String {
    val cleaned = name.trim().lowercase().replace(Regex("""\s+"""), " ")
    return cleaned.ifEmpty { DEFAULT_ROBOT_NAME }
}

fun parseAttention(heard: String, name: String): Attention {
    val said = heard.trim().replace(Regex("""\s+"""), " ")
    if (said.isEmpty()) return Attention(false, "")
    val wake = Regex.escape(normalizeRobotName(name))
    val lower = said.lowercase()
    if (!Regex("""\b$wake\b""").containsMatchIn(lower)) {
        return Attention(false, said)
    }
    // Drop an optional soft opener and the name; keep the rest as the request.
    val stripped = said
        .replace(Regex("""(?i)^(?:hey|hi|hello|ok|okay|yo)\s+$wake\b[,:!?]?\s*"""), "")
        .replace(Regex("""(?i)\b$wake\b[,:!?]?\s*"""), "")
        .trim()
        .replace(Regex("""\s+"""), " ")
    return Attention(true, stripped)
}

/**
 * One wake opens a short conversation window. Call [consider] for each mic line;
 * [nowMs] is elapsed realtime (or any monotonic clock).
 */
class AttentionSession(private val holdMs: Long = ATTENTION_HOLD_MS) {
    @Volatile private var engagedUntilMs = 0L

    fun clear() {
        engagedUntilMs = 0L
    }

    fun consider(heard: String, name: String, requireName: Boolean, nowMs: Long): Attention {
        val said = heard.trim().replace(Regex("""\s+"""), " ")
        if (!requireName) {
            touch(nowMs)
            return Attention(true, said)
        }
        val parsed = parseAttention(said, name)
        if (parsed.addressed) {
            touch(nowMs)
            return parsed
        }
        if (nowMs < engagedUntilMs) {
            touch(nowMs)
            return Attention(true, said)
        }
        return Attention(false, said)
    }

    private fun touch(nowMs: Long) {
        engagedUntilMs = nowMs + holdMs
    }
}

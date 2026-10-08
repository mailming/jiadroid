package dev.jiadroid.follow

/** Default wake name when the user has not assigned one. */
const val DEFAULT_ROBOT_NAME = "lulu"

/**
 * Whether a transcript is addressed to the robot, and the words after the wake name.
 *
 * Background chat is ignored until someone says the name (e.g. "hey Lulu, stop").
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

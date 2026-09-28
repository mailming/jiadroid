package dev.jiadroid.follow

/**
 * Turns heard speech into a spoken reply.
 *
 * Reachy Mini streams the microphone to a realtime language model. This phone
 * answers on the device so it can talk with no API key. The function is the
 * only place that reply comes from.
 */
fun reply(heard: String, seeing: String): String {
    val said = heard.trim().replace(Regex("\\s+"), " ")
    if (said.isEmpty()) return "I didn't catch that."
    val lower = said.lowercase()
    if (Regex("""\b(hi|hello|hey)\b""").containsMatchIn(lower)) {
        return "Hello. I can see you, and I can hear you."
    }
    if (lower.contains("who are you") || lower.contains("your name")) {
        return "I am the phone on the robot. I follow you, and I can talk."
    }
    if (lower.contains("follow")) return "I am following. Stay in front of me."
    if (lower.contains("see") || lower.contains("looking") || lower.contains("where")) {
        return if (seeing.contains("lost", ignoreCase = true)) {
            "I don't see anyone right now."
        } else {
            "I see someone. $seeing."
        }
    }
    return "I heard you say $said."
}

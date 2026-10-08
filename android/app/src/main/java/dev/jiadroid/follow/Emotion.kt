package dev.jiadroid.follow

/** Face mood shared by conversation and the eyes. */
enum class Emotion {
    NEUTRAL,
    HAPPY,
    CURIOUS,
    LISTENING,
    THINKING,
    CONFUSED,
    SAD,
    EXCITED,
    ;

    companion object {
        fun parse(raw: String?): Emotion {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.name.lowercase() == key } ?: NEUTRAL
        }
    }
}

data class SpokenReply(val say: String, val emotion: Emotion)

/**
 * Pull a trailing `<<happy>>` (or similar) tag off model text so TTS stays clean.
 * Also accepts a one-line JSON object `{"say":"...","emotion":"happy"}`.
 */
fun parseSpokenReply(raw: String, fallback: Emotion = Emotion.NEUTRAL): SpokenReply {
    val text = raw.trim()
    if (text.isEmpty()) return SpokenReply("Yes?", fallback)
    if (text.startsWith("{")) {
        // Lightweight field read so JVM unit tests don't need Android's JSONObject stubs.
        val say = (jsonStringField(text, "say") ?: jsonStringField(text, "text")).orEmpty().trim()
        if (say.isNotEmpty()) {
            return SpokenReply(say, Emotion.parse(jsonStringField(text, "emotion")))
        }
    }
    val tagged = Regex("""<<\s*([a-zA-Z_]+)\s*>>\s*$""").find(text)
    if (tagged != null) {
        val emotion = Emotion.parse(tagged.groupValues[1])
        val say = text.removeRange(tagged.range).trim()
        return SpokenReply(say.ifEmpty { "Yes?" }, emotion)
    }
    return SpokenReply(text, fallback)
}

private fun jsonStringField(json: String, key: String): String? {
    val match = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return null
    return match.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}

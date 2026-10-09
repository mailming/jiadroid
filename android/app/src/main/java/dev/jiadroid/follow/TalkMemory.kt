package dev.jiadroid.follow

/**
 * Short-term conversation turns on the phone (this session). Sticky long-lived
 * facts live in [AudienceKb] and persist across restarts.
 */
class TalkMemory(
    private val maxMessages: Int = MAX_MESSAGES,
) {
    private val messages = ArrayDeque<TalkTurn>()

    @Synchronized
    fun rememberExchange(user: String, assistant: String) {
        val you = user.trim().replace(Regex("""\s+"""), " ")
        val me = assistant.trim().replace(Regex("""\s+"""), " ")
        if (you.isNotEmpty()) add("user", you)
        if (me.isNotEmpty()) add("assistant", me)
    }

    @Synchronized
    fun chatTurns(): List<TalkTurn> = messages.toList()

    @Synchronized
    fun clear() {
        messages.clear()
    }

    /** Compact recent-talk block for the model system prompt. */
    @Synchronized
    fun contextBlock(): String {
        if (messages.isEmpty()) return ""
        val lines = messages.joinToString("\n") { turn ->
            val who = if (turn.role == "user") "Them" else "You"
            "$who: ${turn.text}"
        }
        return "Recent talk:\n$lines"
    }

    /** Local answers about the latest utterance. */
    @Synchronized
    fun recallReply(heard: String): SpokenReply? {
        val lower = heard.trim().lowercase()
        if (lower.isEmpty()) return null
        if (Regex("""\b(what did i (just )?say|what was that|repeat that)\b""").containsMatchIn(lower)) {
            val last = messages.lastOrNull { it.role == "user" }?.text
            return if (last.isNullOrBlank()) {
                SpokenReply("I don't have anything recent from you yet.", Emotion.CURIOUS)
            } else {
                SpokenReply("You said $last.", Emotion.NEUTRAL)
            }
        }
        return null
    }

    private fun add(role: String, text: String) {
        messages.addLast(TalkTurn(role, text))
        while (messages.size > maxMessages) messages.removeFirst()
    }

    data class TalkTurn(val role: String, val text: String)

    private companion object {
        const val MAX_MESSAGES = 40
    }
}

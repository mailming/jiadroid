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
        rememberUser(user)
        rememberAssistant(assistant)
    }

    /** Stage the user line before calling the model so history includes this turn. */
    @Synchronized
    fun rememberUser(user: String) {
        val you = user.trim().replace(Regex("""\s+"""), " ")
        if (you.isEmpty()) return
        val last = messages.lastOrNull()
        if (last?.role == "user" && last.text == you) return
        // Drop an orphan user turn (e.g. reply never finished) so roles stay alternating.
        if (last?.role == "user") messages.removeLast()
        add("user", you)
    }

    @Synchronized
    fun rememberAssistant(assistant: String) {
        val me = assistant.trim().replace(Regex("""\s+"""), " ")
        if (me.isEmpty()) return
        add("assistant", me)
    }

    @Synchronized
    fun chatTurns(): List<TalkTurn> = messages.toList()

    @Synchronized
    fun clear() {
        messages.clear()
    }

    /**
     * After the audience KB corrects a mis-heard name/fact, rewrite that word in
     * recent turns so the model is not pulled back to the wrong value.
     */
    @Synchronized
    fun rewriteMention(from: String, to: String) {
        val src = from.trim()
        val dst = to.trim()
        if (src.isEmpty() || dst.isEmpty() || src.equals(dst, ignoreCase = true)) return
        val pattern = Regex("""\b${Regex.escape(src)}\b""", RegexOption.IGNORE_CASE)
        if (messages.none { pattern.containsMatchIn(it.text) }) return
        val rewritten = ArrayDeque<TalkTurn>()
        for (turn in messages) {
            rewritten.addLast(TalkTurn(turn.role, pattern.replace(turn.text, dst)))
        }
        messages.clear()
        messages.addAll(rewritten)
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

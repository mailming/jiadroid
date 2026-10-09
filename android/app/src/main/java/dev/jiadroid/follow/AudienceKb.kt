package dev.jiadroid.follow

/**
 * Persistent on-phone audience knowledge base.
 *
 * - **Pin:** `name`, `likes`, `from`, and compacted `summary` never expire or get FIFO'd.
 * - **Cap:** at most [maxFacts] freeform `fact-*` notes.
 * - **TTL:** freeform facts older than [factTtlMs] are dropped (default 30 days).
 * - **Compact:** when over the fact cap, oldest facts fold into `summary`.
 *
 * Stay on-device; no cloud sync. Storage is one `key|epochMs=value` line per note.
 */
class AudienceKb(
    private val loadJson: () -> String?,
    private val saveJson: (String) -> Unit,
    private val maxFacts: Int = MAX_FACTS,
    private val factTtlMs: Long = FACT_TTL_MS,
    private val summaryMaxChars: Int = SUMMARY_MAX_CHARS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val notes = LinkedHashMap<String, Note>()

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        notes.clear()
        val raw = loadJson()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val now = clockMs()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val left = unescape(trimmed.substring(0, eq)).trim()
            val value = unescape(trimmed.substring(eq + 1)).trim()
            if (left.isEmpty() || value.isEmpty()) continue
            val pipe = left.lastIndexOf('|')
            val key: String
            val updatedAt: Long
            if (pipe > 0 && left.substring(pipe + 1).all { it.isDigit() }) {
                key = left.substring(0, pipe).trim()
                updatedAt = left.substring(pipe + 1).toLongOrNull() ?: now
            } else {
                // Legacy `key=value` lines.
                key = left
                updatedAt = now
            }
            if (key.isEmpty()) continue
            notes[key] = Note(key, value, updatedAt, pinned = isPinnedKey(key))
        }
        maintain()
        persist()
    }

    @Synchronized
    fun rememberFrom(user: String) {
        val said = user.trim().replace(Regex("""\s+"""), " ")
        if (said.isEmpty()) return
        if (!absorb(said)) return
        maintain()
        persist()
    }

    @Synchronized
    fun clear() {
        notes.clear()
        persist()
    }

    @Synchronized
    fun promptBlock(): String {
        val changed = maintain()
        if (changed) persist()
        if (notes.isEmpty()) return ""
        val lines = orderedNotes().joinToString("\n") { "- ${it.key}: ${it.value}" }
        return "Audience knowledge base (persistent on this phone):\n$lines"
    }

    @Synchronized
    fun recallReply(heard: String): SpokenReply? {
        val lower = heard.trim().lowercase()
        if (lower.isEmpty()) return null
        val changed = maintain()
        if (changed) persist()
        if (Regex("""\b(what('s| is) my name|who am i)\b""").containsMatchIn(lower)) {
            val name = notes["name"]?.value
            return if (name.isNullOrBlank()) {
                SpokenReply("I don't know your name yet. Tell me, and I'll remember.", Emotion.CURIOUS)
            } else {
                SpokenReply("You're $name.", Emotion.HAPPY)
            }
        }
        if (Regex("""\b(what do you remember|what do you know about me|what have i told you)\b""")
                .containsMatchIn(lower)
        ) {
            if (notes.isEmpty()) {
                return SpokenReply(
                    "Nothing in my knowledge base yet. Say remember, or tell me your name or what you like.",
                    Emotion.CURIOUS,
                )
            }
            val summary = orderedNotes().take(8).joinToString("; ") { "${it.key} ${it.value}" }
            return SpokenReply("I remember $summary.", Emotion.HAPPY)
        }
        if (Regex("""\b(forget (everything|that|me)|clear (your )?memory)\b""").containsMatchIn(lower)) {
            notes.clear()
            persist()
            return SpokenReply("Okay, I cleared what I saved about you on this phone.", Emotion.NEUTRAL)
        }
        return null
    }

    @Synchronized
    fun noteCount(): Int = notes.size

    @Synchronized
    fun factCount(): Int = notes.keys.count { it.startsWith("fact-") }

    /** Test helper: run TTL + compact without new speech. */
    @Synchronized
    fun maintainNow() {
        if (maintain()) persist()
    }

    private fun absorb(user: String): Boolean {
        val lower = user.lowercase()
        var changed = false
        val now = clockMs()
        explicitName(lower)?.let {
            putPinned("name", it, now)
            changed = true
        }
        LIKE.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..80 }?.let {
            putPinned("likes", it, now)
            changed = true
        }
        PLACE.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..80 }?.let {
            putPinned("from", it, now)
            changed = true
        }
        REMEMBER.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..120 }?.let { fact ->
            putFact(fact, now)
            changed = true
        }
        return changed
    }

    private fun explicitName(lower: String): String? {
        val raw = NAME_EXPLICIT.find(lower)?.groupValues?.getOrNull(1)
            ?: NAME_IAM.find(lower)?.groupValues?.getOrNull(1)
            ?: return null
        val cleaned = raw.trim()
        if (cleaned.isEmpty() || cleaned in NAME_STOP) return null
        return cleaned.split(Regex("""\s+"""))
            .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.titlecase() } }
    }

    private fun putPinned(key: String, value: String, now: Long) {
        notes[key] = Note(key, value, now, pinned = true)
    }

    private fun putFact(value: String, now: Long) {
        val key = nextFactKey()
        notes[key] = Note(key, value, now, pinned = false)
    }

    /** @return true if notes changed. */
    private fun maintain(): Boolean {
        val before = notes.mapValues { it.value.copy() }
        pruneExpired(clockMs())
        compactIfNeeded()
        return notes != before
    }

    private fun pruneExpired(now: Long) {
        if (factTtlMs <= 0L) return
        val stale = notes.values.filter { !it.pinned && now - it.updatedAtMs > factTtlMs }.map { it.key }
        stale.forEach { notes.remove(it) }
    }

    private fun compactIfNeeded() {
        val facts = notes.values.filter { !it.pinned && it.key.startsWith("fact-") }
            .sortedBy { it.updatedAtMs }
        if (facts.size <= maxFacts) return
        val overflow = facts.size - maxFacts
        val foldCount = maxOf(overflow, facts.size / 2).coerceAtMost(facts.size)
        val folding = facts.take(foldCount)
        val chunk = folding.joinToString("; ") { it.value }
        val existing = notes["summary"]?.value.orEmpty()
        val merged = if (existing.isBlank()) chunk else "$existing; $chunk"
        val now = clockMs()
        notes["summary"] = Note("summary", trimSummary(merged), now, pinned = true)
        folding.forEach { notes.remove(it.key) }
    }

    private fun trimSummary(text: String): String {
        if (text.length <= summaryMaxChars) return text
        val cut = text.substring(text.length - summaryMaxChars)
        val semi = cut.indexOf("; ")
        return if (semi in 0..40) cut.substring(semi + 2) else cut
    }

    private fun orderedNotes(): List<Note> {
        val pinnedOrder = listOf("name", "likes", "from", "summary")
        val pinned = pinnedOrder.mapNotNull { notes[it] }
        val facts = notes.values
            .filter { !it.pinned && it.key.startsWith("fact-") }
            .sortedBy { it.updatedAtMs }
        return pinned + facts
    }

    private fun nextFactKey(): String {
        var n = 1
        while (notes.containsKey("fact-$n")) n += 1
        return "fact-$n"
    }

    private fun persist() {
        val body = buildString {
            append("#v2 pin+ttl+compact\n")
            for (note in orderedNotes()) {
                append(escape(note.key))
                append('|')
                append(note.updatedAtMs)
                append('=')
                append(escape(note.value))
                append('\n')
            }
        }
        saveJson(body)
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("=", "\\=")
            .replace("|", "\\|")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> out.append('\n')
                    '\\' -> out.append('\\')
                    '=' -> out.append('=')
                    '|' -> out.append('|')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }

    private data class Note(
        val key: String,
        val value: String,
        val updatedAtMs: Long,
        val pinned: Boolean,
    )

    private companion object {
        const val MAX_FACTS = 16
        const val FACT_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val SUMMARY_MAX_CHARS = 400
        val PINNED_KEYS = setOf("name", "likes", "from", "summary")

        fun isPinnedKey(key: String) = key in PINNED_KEYS

        val NAME_EXPLICIT = Regex(
            """(?:my name is|call me)\s+([a-z][a-z'’-]*(?:\s+[a-z][a-z'’-]*)?)""",
        )
        val NAME_IAM = Regex(
            """(?:i am|i'm)\s+([a-z][a-z'’-]*)\b""",
        )
        val NAME_STOP = setOf(
            "a", "an", "the", "here", "ready", "fine", "good", "okay", "ok", "sorry",
            "following", "lost", "hungry", "tired", "happy", "sad", "back", "home",
            "going", "coming", "done", "trying", "looking", "listening", "thinking",
        )
        val LIKE = Regex("""(?:i like|i love)\s+(.+?)(?:[.!?]|$)""")
        val PLACE = Regex("""(?:i live in|i'm from|i am from)\s+(.+?)(?:[.!?]|$)""")
        val REMEMBER = Regex("""(?:remember(?: that)?|don't forget(?: that)?)\s+(.+?)(?:[.!?]|$)""")
    }
}

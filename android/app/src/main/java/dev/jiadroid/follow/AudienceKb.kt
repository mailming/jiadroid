package dev.jiadroid.follow

/**
 * Persistent on-phone audience knowledge base (personal profile).
 *
 * Survives wake-window expiry, new conversations, and app restarts. Only short-term
 * [TalkMemory] resets when a new wake opens after silence.
 *
 * - **Pin:** `name`, `likes`, `from`, pet / favorite keys, and compacted `summary`
 *   never expire or get FIFO'd.
 * - **Replace:** repeating “my name is…”, “my dog’s name is…”, or “actually…” updates
 *   the same key instead of stacking conflicting notes.
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

    /**
     * Absorb durable facts from one user line.
     * @return keys that changed (with prior value when replacing), so [TalkMemory]
     * can rewrite mis-heard mentions in the live chat window.
     */
    @Synchronized
    fun rememberFrom(user: String): List<KbChange> {
        val said = user.trim().replace(Regex("""\s+"""), " ")
        if (said.isEmpty()) return emptyList()
        val changes = absorb(said)
        if (changes.isEmpty()) return emptyList()
        maintain()
        persist()
        return changes
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
        return "Audience profile (persistent across wake windows and app restarts):\n$lines"
    }

    @Synchronized
    fun recallReply(heard: String): SpokenReply? {
        val lower = heard.trim().lowercase()
        if (lower.isEmpty()) return null
        val changed = maintain()
        if (changed) persist()
        if (Regex("""\b(what('s| is) my name|who am i|do you (know|remember) my name)\b""")
                .containsMatchIn(lower)
        ) {
            val name = notes["name"]?.value
            return if (name.isNullOrBlank()) {
                SpokenReply("I don't know your name yet. Tell me, and I'll remember.", Emotion.CURIOUS)
            } else {
                SpokenReply("You're $name.", Emotion.HAPPY)
            }
        }
        if (Regex(
                """\b(what('s| is) my (dog|cat|bird|fish|hamster|rabbit)'?s? name|what('s| is) my (dog|cat|bird|fish|hamster|rabbit) called)\b""",
            ).containsMatchIn(lower)
        ) {
            val kind = Regex("""\b(dog|cat|bird|fish|hamster|rabbit)\b""").find(lower)?.value ?: return null
            val pet = notes["pet-$kind"]?.value
            return if (pet.isNullOrBlank()) {
                SpokenReply("I don't know your $kind's name yet.", Emotion.CURIOUS)
            } else {
                SpokenReply("Your $kind is $pet.", Emotion.HAPPY)
            }
        }
        if (Regex(
                """\b(what do you remember|what do you know about me|what have i told you|do you remember (anything|me))\b""",
            ).containsMatchIn(lower)
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

    private fun absorb(user: String): List<KbChange> {
        val lower = stripLeadIn(user.lowercase())
        val changes = mutableListOf<KbChange>()
        val now = clockMs()

        explicitName(lower)?.let { changes += putPinned("name", it, now) }
        correctionName(lower)?.let { changes += putPinned("name", it, now) }

        LIKE.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..80 }?.let {
            changes += putPinned("likes", it, now)
        }
        FAVORITE.find(lower)?.groupValues?.let { groups ->
            val kind = groups.getOrNull(1)?.trim().orEmpty()
                .replace(Regex("""\s+"""), "-")
            val value = groups.getOrNull(2)?.trim().orEmpty()
            if (kind.isNotEmpty() && value.length in 2..80) {
                changes += putPinned("favorite-$kind", value, now)
            }
        }
        PLACE.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..80 }?.let {
            changes += putPinned("from", it, now)
        }

        petName(lower)?.let { (kind, name) ->
            changes += putPinned("pet-$kind", name, now)
        }

        // Generic "I have a …" only when it is not already captured as a named pet.
        if (petName(lower) == null) {
            HAVE.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..80 }?.let { thing ->
                changes += putAppendFact("has $thing", now)
            }
        }

        REMEMBER.find(lower)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..120 }?.let { fact ->
            // Prefer structured pet capture inside "remember that my dog is named X".
            val nested = petName(fact) ?: petName("my $fact")
            if (nested != null) {
                changes += putPinned("pet-${nested.first}", nested.second, now)
            } else {
                changes += putAppendFact(fact, now)
            }
        }
        return changes.distinctBy { it.key to it.value }
    }

    private fun explicitName(lower: String): String? {
        val raw = NAME_EXPLICIT.find(lower)?.groupValues?.getOrNull(1)
            ?: NAME_IAM.find(lower)?.groupValues?.getOrNull(1)
            ?: return null
        return titleName(raw)
    }

    /** "not Gia, it's Jia" / "not Gia it is Jia" when correcting a mis-heard name. */
    private fun correctionName(lower: String): String? {
        val groups = NAME_CORRECTION.find(lower)?.groupValues ?: return null
        val wrong = titleName(groups.getOrNull(1) ?: return null) ?: return null
        val right = titleName(groups.getOrNull(2) ?: return null) ?: return null
        val current = notes["name"]?.value
        // Prefer this when it matches a stored wrong name, or when no name is stored yet
        // but the utterance is clearly a not-X-it's-Y correction.
        if (current != null && !current.equals(wrong, ignoreCase = true) &&
            !current.equals(right, ignoreCase = true)
        ) {
            return null
        }
        return right
    }

    private fun petName(lower: String): Pair<String, String>? {
        PET_NAME_IS.find(lower)?.groupValues?.let { groups ->
            val kind = groups.getOrNull(1)?.trim().orEmpty()
            val name = titleName(groups.getOrNull(2).orEmpty())
            if (kind in PET_KINDS && name != null) return kind to name
        }
        PET_NAMED.find(lower)?.groupValues?.let { groups ->
            val kind = groups.getOrNull(1)?.trim().orEmpty()
            val name = titleName(groups.getOrNull(2).orEmpty())
            if (kind in PET_KINDS && name != null) return kind to name
        }
        HAVE_PET.find(lower)?.groupValues?.let { groups ->
            val kind = groups.getOrNull(1)?.trim().orEmpty()
            val name = titleName(groups.getOrNull(2).orEmpty())
            if (kind in PET_KINDS && name != null) return kind to name
        }
        return null
    }

    private fun titleName(raw: String): String? {
        val cleaned = raw.trim().trimEnd('.', '!', '?', ',')
        if (cleaned.isEmpty() || cleaned in NAME_STOP) return null
        return cleaned.split(Regex("""\s+"""))
            .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.titlecase() } }
    }

    private fun putPinned(key: String, value: String, now: Long): KbChange {
        val previous = notes[key]?.value
        if (previous != null && !previous.equals(value, ignoreCase = true)) {
            scrubMention(previous)
        }
        notes[key] = Note(key, value, now, pinned = true)
        return KbChange(key, previous, value)
    }

    private fun putAppendFact(value: String, now: Long): KbChange {
        // Replace an identical freeform fact instead of duplicating it.
        val existing = notes.entries.firstOrNull {
            it.key.startsWith("fact-") && it.value.value.equals(value, ignoreCase = true)
        }
        if (existing != null) {
            notes[existing.key] = Note(existing.key, value, now, pinned = false)
            return KbChange(existing.key, existing.value.value, value)
        }
        val key = nextFactKey()
        notes[key] = Note(key, value, now, pinned = false)
        return KbChange(key, null, value)
    }

    /** Drop or rewrite older notes that still carry a superseded value. */
    private fun scrubMention(old: String) {
        val pattern = Regex("""\b${Regex.escape(old)}\b""", RegexOption.IGNORE_CASE)
        val staleKeys = mutableListOf<String>()
        for ((key, note) in notes) {
            if (key == "name" || key.startsWith("pet-") || key.startsWith("favorite-")) continue
            if (!pattern.containsMatchIn(note.value)) continue
            if (key == "summary") {
                val cleaned = pattern.replace(note.value, "")
                    .replace(Regex("""\s*;\s*;\s*"""), "; ")
                    .replace(Regex("""^;\s*|\s*;$"""), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (cleaned.isEmpty()) staleKeys += key
                else notes[key] = note.copy(value = cleaned)
            } else if (key.startsWith("fact-")) {
                staleKeys += key
            }
        }
        staleKeys.forEach { notes.remove(it) }
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
        val structured = notes.values
            .filter { it.pinned && it.key !in pinnedOrder }
            .sortedBy { it.key }
        val facts = notes.values
            .filter { !it.pinned && it.key.startsWith("fact-") }
            .sortedBy { it.updatedAtMs }
        return pinned + structured + facts
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

    data class KbChange(val key: String, val previous: String?, val value: String)

    private companion object {
        const val MAX_FACTS = 16
        const val FACT_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val SUMMARY_MAX_CHARS = 400
        val PINNED_KEYS = setOf("name", "likes", "from", "summary")
        val PET_KINDS = setOf("dog", "cat", "bird", "fish", "hamster", "rabbit")

        fun isPinnedKey(key: String) =
            key in PINNED_KEYS || key.startsWith("pet-") || key.startsWith("favorite-")

        fun stripLeadIn(lower: String): String {
            var s = lower.trim()
            val lead = Regex("""^(?:actually|no|nope|wait|sorry|correction|oh|um|uh)[,!]?\s+""")
            while (true) {
                val next = lead.replace(s, "")
                if (next == s) return s
                s = next.trim()
            }
        }

        val NAME_EXPLICIT = Regex(
            """(?:my name is|call me)\s+([a-z][a-z'’-]*(?:\s+[a-z][a-z'’-]*)?)""",
        )
        val NAME_IAM = Regex(
            """(?:i am|i'm)\s+([a-z][a-z'’-]*)\b""",
        )
        val NAME_CORRECTION = Regex(
            """\bnot\s+([a-z][a-z'’-]*)[,.]?\s+(?:it'?s|it is|but)\s+([a-z][a-z'’-]*)\b""",
        )
        val NAME_STOP = setOf(
            "a", "an", "the", "here", "ready", "fine", "good", "okay", "ok", "sorry",
            "following", "lost", "hungry", "tired", "happy", "sad", "back", "home",
            "going", "coming", "done", "trying", "looking", "listening", "thinking",
        )
        val LIKE = Regex("""(?:i like|i love)\s+(.+?)(?:[.!?]|$)""")
        val FAVORITE = Regex(
            """(?:my favorite|my favourite)\s+([a-z][a-z\s]{0,24}?)\s+is\s+(.+?)(?:[.!?]|$)""",
        )
        val PLACE = Regex("""(?:i live in|i'm from|i am from)\s+(.+?)(?:[.!?]|$)""")
        val HAVE = Regex("""(?:i have|i've got)\s+(?:a|an)\s+(.+?)(?:[.!?]|$)""")
        val HAVE_PET = Regex(
            """(?:i have|i've got)\s+(?:a|an)\s+(dog|cat|bird|fish|hamster|rabbit)\s+(?:named|called)\s+([a-z][a-z'’-]*)""",
        )
        val PET_NAME_IS = Regex(
            """(?:my\s+)?(dog|cat|bird|fish|hamster|rabbit)'?s?\s+name\s+is\s+([a-z][a-z'’-]*)""",
        )
        val PET_NAMED = Regex(
            """(?:my\s+)?(dog|cat|bird|fish|hamster|rabbit)\s+is\s+(?:named|called)\s+([a-z][a-z'’-]*)""",
        )
        val REMEMBER = Regex("""(?:remember(?: that)?|don't forget(?: that)?)\s+(.+?)(?:[.!?]|$)""")
    }
}

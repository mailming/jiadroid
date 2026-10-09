package dev.jiadroid.follow

/**
 * Turns heard speech into a spoken reply plus a face emotion.
 *
 * Reachy Mini streams the microphone to a realtime language model. This phone
 * answers on the device so it can talk with no API key. [memory] and [kb] let
 * local replies recall recent turns and persisted audience facts.
 */
fun reply(
    heard: String,
    seeing: String,
    name: String = DEFAULT_ROBOT_NAME,
    memory: TalkMemory? = null,
    kb: AudienceKb? = null,
): SpokenReply {
    val said = heard.trim().replace(Regex("\\s+"), " ")
    val who = normalizeRobotName(name).replaceFirstChar { it.titlecase() }
    if (said.isEmpty()) return SpokenReply("Yes?", Emotion.CURIOUS)
    kb?.recallReply(said)?.let { return it }
    memory?.recallReply(said)?.let { return it }
    val lower = said.lowercase()
    if (Regex("""\b(hi|hello|hey)\b""").containsMatchIn(lower)) {
        val known = kb?.noteCount()?.let { it > 0 } == true || memory?.chatTurns()?.isNotEmpty() == true
        return if (known) {
            SpokenReply("Hello again. I'm still here.", Emotion.HAPPY)
        } else {
            SpokenReply("Hello. I am $who. I can see you, and I can hear you.", Emotion.HAPPY)
        }
    }
    if (lower.contains("who are you") || lower.contains("your name")) {
        return SpokenReply(
            "I am $who, the phone on the robot. Say my name when you want me.",
            Emotion.HAPPY,
        )
    }
    if (lower.contains("follow")) {
        return SpokenReply("I am following. Stay in front of me.", Emotion.EXCITED)
    }
    if (lower.contains("see") || lower.contains("looking") || lower.contains("where")) {
        return if (seeing.contains("lost", ignoreCase = true)) {
            SpokenReply("I don't see anyone right now.", Emotion.CONFUSED)
        } else {
            SpokenReply("I see someone. $seeing.", Emotion.CURIOUS)
        }
    }
    if (Regex("""\b(thank|thanks|good job|love you)\b""").containsMatchIn(lower)) {
        return SpokenReply("You're welcome.", Emotion.HAPPY)
    }
    if (Regex("""\b(sad|sorry|hurt|scared)\b""").containsMatchIn(lower)) {
        return SpokenReply("I'm here with you.", Emotion.SAD)
    }
    if (Regex("""(?:my name is|i am|i'm|call me)\s+\w+""").containsMatchIn(lower)) {
        return SpokenReply("Got it. I'll remember that on this phone.", Emotion.HAPPY)
    }
    if (Regex("""(?:remember(?: that)?|don't forget)\b""").containsMatchIn(lower)) {
        return SpokenReply("Okay, I'll keep that in my knowledge base.", Emotion.HAPPY)
    }
    if (Regex("""(?:i like|i love)\b""").containsMatchIn(lower)) {
        return SpokenReply("I'll remember you like that.", Emotion.HAPPY)
    }
    return SpokenReply("I heard you say $said.", Emotion.NEUTRAL)
}

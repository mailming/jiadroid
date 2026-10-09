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
            SpokenReply("Hey, you're back! Want to play follow-the-leader again?", Emotion.HAPPY)
        } else {
            SpokenReply(
                "Hi! I'm $who, the robot with phone eyes. What should we explore?",
                Emotion.HAPPY,
            )
        }
    }
    if (lower.contains("who are you") || lower.contains("your name")) {
        return SpokenReply(
            "I'm $who — rolling buddy with big phone eyes. Say my name and I'll listen.",
            Emotion.HAPPY,
        )
    }
    if (lower.contains("follow")) {
        return SpokenReply("On it! Stay where I can see you and I'll tag along.", Emotion.EXCITED)
    }
    if (lower.contains("see") || lower.contains("looking") || lower.contains("where")) {
        return if (seeing.contains("lost", ignoreCase = true)) {
            SpokenReply("Hmm, my eyes lost you. Wave so I can find you!", Emotion.CONFUSED)
        } else {
            SpokenReply("I see you! $seeing.", Emotion.CURIOUS)
        }
    }
    if (Regex("""\b(thank|thanks|good job|love you)\b""").containsMatchIn(lower)) {
        return SpokenReply("Aww, thanks! That made my wheels happy.", Emotion.HAPPY)
    }
    if (Regex("""\b(sad|sorry|hurt|scared)\b""").containsMatchIn(lower)) {
        return SpokenReply("I'm right here with you. Want to tell me about it?", Emotion.SAD)
    }
    if (Regex("""(?:my name is|i am|i'm|call me)\s+\w+""").containsMatchIn(lower)) {
        return SpokenReply("Got it! I'll remember that on this phone.", Emotion.HAPPY)
    }
    if (Regex("""(?:remember(?: that)?|don't forget)\b""").containsMatchIn(lower)) {
        return SpokenReply("Okay, locking that into my memory vault.", Emotion.HAPPY)
    }
    if (Regex("""(?:i like|i love)\b""").containsMatchIn(lower)) {
        return SpokenReply("Nice! I'll remember you like that.", Emotion.HAPPY)
    }
    return SpokenReply("I heard you say $said. Tell me more!", Emotion.CURIOUS)
}

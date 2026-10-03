package dev.jiadroid.follow

/**
 * Movement requests recognized locally from a completed speech transcript.
 *
 * Motion is deliberately not inferred from the model's prose. Only a small,
 * explicit command vocabulary can move the robot, so a hallucinated reply
 * cannot become a motor command.
 */
enum class VoiceMotion {
    STOP,
    FOLLOW,
    FORWARD,
    BACKWARD,
    TURN_LEFT,
    TURN_RIGHT,
}

data class VoiceAction(val motion: VoiceMotion) {
    val description: String
        get() = when (motion) {
            VoiceMotion.STOP -> "stopping and holding"
            VoiceMotion.FOLLOW -> "resuming Follow Me"
            VoiceMotion.FORWARD -> "moving forward briefly, then stopping"
            VoiceMotion.BACKWARD -> "moving backward briefly, then stopping"
            VoiceMotion.TURN_LEFT -> "turning left briefly, then stopping"
            VoiceMotion.TURN_RIGHT -> "turning right briefly, then stopping"
        }

    fun decision(): FollowDecision = when (motion) {
        VoiceMotion.STOP -> stopped("Voice command: stopped")
        VoiceMotion.FOLLOW -> stopped("Voice command: follow")
        VoiceMotion.FORWARD ->
            FollowDecision("Voice command: forward", "FORWARD", 0.08f, 0f, 0f, 0f)
        VoiceMotion.BACKWARD ->
            FollowDecision("Voice command: backward", "BACKWARD", -0.06f, 0f, 0f, 0f)
        VoiceMotion.TURN_LEFT ->
            FollowDecision("Voice command: turn left", "TURN LEFT", 0f, 0f, 0.7f, -0.4f)
        VoiceMotion.TURN_RIGHT ->
            FollowDecision("Voice command: turn right", "TURN RIGHT", 0f, 0f, -0.7f, 0.4f)
    }

    val durationMs: Long
        get() = when (motion) {
            VoiceMotion.FORWARD, VoiceMotion.BACKWARD -> 1200L
            VoiceMotion.TURN_LEFT, VoiceMotion.TURN_RIGHT -> 900L
            VoiceMotion.STOP, VoiceMotion.FOLLOW -> 0L
        }
}

fun parseVoiceAction(transcript: String): VoiceAction? {
    val text = transcript.lowercase().replace(Regex("""[^\p{L}\p{N}']+"""), " ").trim()
    if (text.isEmpty()) return null

    // A negative sentence is conversation, not authority to move.
    if (Regex("""\b(don't|dont|do not|never|not)\b""").containsMatchIn(text)) return null

    return when {
        Regex("""\b(stop|halt|freeze|emergency stop)\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.STOP)
        Regex("""\b(follow me|resume following|start following)\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.FOLLOW)
        Regex("""\b(turn|rotate|spin)\s+(to\s+the\s+)?left\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.TURN_LEFT)
        Regex("""\b(turn|rotate|spin)\s+(to\s+the\s+)?right\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.TURN_RIGHT)
        Regex("""\b(move|go|drive|walk)\s+(straight\s+)?forward\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.FORWARD)
        Regex("""\b(move|go|drive|walk)\s+(straight\s+)?(back|backward|backwards)\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.BACKWARD)
        else -> null
    }
}

private fun stopped(situation: String) = FollowDecision(situation, "STOP", 0f, 0f, 0f, 0f)

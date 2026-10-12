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

    /** Short spoken confirmation so the user knows the motors got the command. */
    val spokenAck: String
        get() = when (motion) {
            VoiceMotion.STOP -> "Stopping."
            VoiceMotion.FOLLOW -> "Okay, following."
            VoiceMotion.FORWARD -> "Going forward."
            VoiceMotion.BACKWARD -> "Backing up."
            VoiceMotion.TURN_LEFT -> "Turning left."
            VoiceMotion.TURN_RIGHT -> "Turning right."
        }

    fun decision(): FollowDecision = when (motion) {
        VoiceMotion.STOP -> stopped("Voice command: stopped")
        VoiceMotion.FOLLOW -> stopped("Voice command: follow")
        VoiceMotion.FORWARD ->
            FollowDecision("Voice command: forward", "FORWARD", 0.04f, 0f, 0f, 0f)
        VoiceMotion.BACKWARD ->
            FollowDecision("Voice command: backward", "BACKWARD", -0.03f, 0f, 0f, 0f)
        VoiceMotion.TURN_LEFT ->
            FollowDecision("Voice command: turn left", "TURN LEFT", 0.05f, 0f, 0.20f, -0.20f)
        VoiceMotion.TURN_RIGHT ->
            FollowDecision("Voice command: turn right", "TURN RIGHT", 0.05f, 0f, -0.20f, 0.20f)
    }

    val durationMs: Long
        get() = when (motion) {
            VoiceMotion.FORWARD, VoiceMotion.BACKWARD -> 1_400L
            // Short nudge — keep the person in frame for Follow Me.
            VoiceMotion.TURN_LEFT, VoiceMotion.TURN_RIGHT -> 220L
            VoiceMotion.STOP, VoiceMotion.FOLLOW -> 0L
        }
}

fun parseVoiceAction(transcript: String): VoiceAction? {
    val text = transcript.lowercase().replace(Regex("""[^\p{L}\p{N}']+"""), " ").trim()
    if (text.isEmpty()) return null

    // A negative sentence is conversation, not authority to move.
    if (Regex("""\b(don't|dont|do not|never|not)\b""").containsMatchIn(text)) return null

    return when {
        Regex("""\b(stop|halt|freeze|emergency stop|hold still|stay)\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.STOP)
        Regex("""\b(follow me|resume following|start following|come follow)\b""").containsMatchIn(text) ->
            VoiceAction(VoiceMotion.FOLLOW)
        Regex(
            """\b((turn|rotate|spin|go|move|drive|walk|veer|head)(\s+(to(\s+the)?))?\s+left|left\s+turn|make a left)\b""",
        ).containsMatchIn(text) || text == "left" ->
            VoiceAction(VoiceMotion.TURN_LEFT)
        Regex(
            """\b((turn|rotate|spin|go|move|drive|walk|veer|head)(\s+(to(\s+the)?))?\s+right|right\s+turn|make a right)\b""",
        ).containsMatchIn(text) || text == "right" ->
            VoiceAction(VoiceMotion.TURN_RIGHT)
        Regex("""\b((move|go|drive|walk)(\s+straight)?\s+forward|come forward)\b""")
            .containsMatchIn(text) || text == "forward" ->
            VoiceAction(VoiceMotion.FORWARD)
        Regex(
            """\b((move|go|drive|walk)(\s+straight)?\s+(back|backward|backwards)|come back|back up|backup)\b""",
        ).containsMatchIn(text) || text == "back" || text == "backward" || text == "backwards" ->
            VoiceAction(VoiceMotion.BACKWARD)
        else -> null
    }
}

private fun stopped(situation: String) = FollowDecision(situation, "STOP", 0f, 0f, 0f, 0f)

package dev.jiadroid.follow

/**
 * Debug pad decisions. Speeds match [decide] / [VoiceAction] so bench tests
 * exercise the same `motion.velocity` values the follower sends.
 */
object ManualControl {
    fun forward() = FollowDecision("Manual: forward", "FORWARD", 0.12f, 0f, 0f, 0f)

    fun slowForward() = FollowDecision("Manual: slow forward", "SLOW DOWN", 0.05f, 0f, 0f, 0f)

    fun backward() = FollowDecision("Manual: backward", "BACKWARD", -0.06f, 0f, 0f, 0f)

    fun slowBackward() =
        FollowDecision("Manual: slow backward", "SLOW BACKWARD", -0.03f, 0f, 0f, 0f)

    fun left() = FollowDecision("Manual: left", "TURN LEFT", 0f, 0f, 0.7f, -0.4f)

    fun right() = FollowDecision("Manual: right", "TURN RIGHT", 0f, 0f, -0.7f, 0.4f)

    fun search() = FollowDecision("Manual: search", "SEARCH", 0f, 0f, 0.55f, 0.5f)

    fun stop() = FollowDecision("Manual: stop", "STOP", 0f, 0f, 0f, 0f)
}

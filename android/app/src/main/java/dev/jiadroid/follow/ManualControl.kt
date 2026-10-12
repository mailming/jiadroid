package dev.jiadroid.follow

/**
 * Debug pad decisions. Speeds match [decide] / [VoiceAction] so bench tests
 * exercise the same `motion.velocity` values the follower sends.
 */
object ManualControl {
    fun forward() = FollowDecision("Manual: forward", "FORWARD", 0.12f, 0f, 0f, 0f)

    fun slowForward() = FollowDecision("Manual: slow forward", "SLOW DOWN", 0.08f, 0f, 0f, 0f)

    fun backward() = FollowDecision("Manual: backward", "BACKWARD", -0.12f, 0f, 0f, 0f)

    fun slowBackward() =
        FollowDecision("Manual: slow backward", "SLOW BACKWARD", -0.08f, 0f, 0f, 0f)

    // Matched arcs: same |forward| and |yaw| so left/right feel the same speed.
    fun left() = FollowDecision("Manual: left", "TURN LEFT", 0.07f, 0f, 0.85f, -0.4f)

    fun right() = FollowDecision("Manual: right", "TURN RIGHT", 0.07f, 0f, -0.85f, 0.4f)

    /** Placeholder; the pad runs [LostSearch] phases instead of a fixed vector. */
    fun search() = FollowDecision("Manual: search", "SEARCH", 0f, 0f, 0.70f, 0.5f)

    fun stop() = FollowDecision("Manual: stop", "STOP", 0f, 0f, 0f, 0f)
}

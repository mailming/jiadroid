package dev.jiadroid.follow

/**
 * Debug pad decisions. Speeds match [decide] / [VoiceAction] so bench tests
 * exercise the same `motion.velocity` values the follower sends.
 */
object ManualControl {
    fun forward() = FollowDecision("Manual: forward", "FORWARD", 0.12f, 0f, 0f, 0f)

    fun slowForward() = FollowDecision("Manual: slow forward", "SLOW DOWN", 0.05f, 0f, 0f, 0f)

    fun backward() = FollowDecision("Manual: backward", "BACKWARD", -0.12f, 0f, 0f, 0f)

    fun slowBackward() =
        FollowDecision("Manual: slow backward", "SLOW BACKWARD", -0.05f, 0f, 0f, 0f)

    // Wide arc: both wheels drive forward at high PWM; outer side faster.
    // Pure yaw / sharp yaw parks the inner wheel near stall (one-wheel spin).
    fun left() = FollowDecision("Manual: left", "TURN LEFT", 0.12f, 0f, 0.40f, -0.4f)

    fun right() = FollowDecision("Manual: right", "TURN RIGHT", 0.12f, 0f, -0.40f, 0.4f)

    fun search() = FollowDecision("Manual: search", "SEARCH", 0.10f, 0f, 0.45f, 0.5f)

    fun stop() = FollowDecision("Manual: stop", "STOP", 0f, 0f, 0f, 0f)
}

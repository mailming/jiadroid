package dev.jiadroid.follow

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Printed width of the mini-person code, in millimeters. */
const val MARKER_WIDTH_MM = 60f

const val MARKER_PAYLOAD = "jiadroid:person"

private const val ALIGN_RAD = 0.28f
private const val CLOSE_M = 0.40f
private const val SLOW_M = 0.75f
private const val FAR_M = 1.40f

/**
 * Follow decisions for the phone test.
 *
 * The marker is a printed mini person, standing in for a real person.
 * [Scene.angle] is radians, positive when the marker is to the right.
 * [Scene.distance] is meters. Walk units match the Open Duck Mini:
 * forward is meters per second, yaw is radians per second, and head yaw is radians.
 */
data class Scene(
    val visible: Boolean,
    val angle: Float,
    val distance: Float,
)

data class FollowDecision(
    val situation: String,
    val command: String,
    val forward: Float,
    val lateral: Float,
    val yaw: Float,
    val headYaw: Float,
)

data class Pose(
    var x: Float,
    var y: Float,
    var heading: Float,
)

fun measure(
    centerX: Float,
    widthPx: Float,
    imageWidth: Int,
    hfovRad: Double,
    markerWidthM: Float,
): Scene {
    if (imageWidth <= 0 || widthPx < 8f || markerWidthM <= 0f) return Scene(false, 0f, 0f)
    if (hfovRad <= 0.2 || hfovRad >= 2.8) return Scene(false, 0f, 0f)
    val fx = (imageWidth / 2.0) / tan(hfovRad / 2.0)
    if (fx <= 1.0) return Scene(false, 0f, 0f)
    val distance = (markerWidthM * fx / widthPx).toFloat()
    if (distance < 0.05f || distance > 6f) return Scene(false, 0f, 0f)
    val angle = atan(((centerX - imageWidth / 2f) / fx).toDouble()).toFloat()
    return Scene(true, angle, distance)
}

fun smoothScene(previous: Scene, measured: Scene): Scene {
    if (!measured.visible) return measured
    if (!previous.visible) return measured
    return Scene(
        true,
        previous.angle * 0.5f + measured.angle * 0.5f,
        previous.distance * 0.6f + measured.distance * 0.4f,
    )
}

fun decide(scene: Scene): FollowDecision {
    if (!scene.visible) return stopped("Marker is lost")
    if (scene.distance < CLOSE_M) return stopped("Marker is too close")
    val headYaw = scene.angle.coerceIn(-0.5f, 0.5f)
    if (scene.angle < -ALIGN_RAD) {
        return FollowDecision("Marker is left", "TURN LEFT", 0.04f, 0f, 0.8f, headYaw)
    }
    if (scene.angle > ALIGN_RAD) {
        return FollowDecision("Marker is right", "TURN RIGHT", 0.04f, 0f, -0.8f, headYaw)
    }
    if (scene.distance > FAR_M) {
        return FollowDecision("Marker is too far", "FORWARD", 0.12f, 0f, 0f, headYaw)
    }
    if (scene.distance < SLOW_M) {
        return FollowDecision("Marker is centered", "SLOW DOWN", 0.05f, 0f, 0f, headYaw)
    }
    return FollowDecision("Marker is centered", "FORWARD", 0.08f, 0f, 0f, headYaw)
}

fun stepPose(pose: Pose, forward: Float, lateral: Float, yaw: Float, dt: Float) {
    val facingX = cos(pose.heading)
    val facingY = sin(pose.heading)
    val leftX = -sin(pose.heading)
    val leftY = cos(pose.heading)
    pose.x += (forward * facingX + lateral * leftX) * dt
    pose.y += (forward * facingY + lateral * leftY) * dt
    pose.heading = wrap(pose.heading + yaw * dt)
}

private fun stopped(situation: String) = FollowDecision(situation, "STOP", 0f, 0f, 0f, 0f)

private fun wrap(angle: Float): Float {
    val pi = PI.toFloat()
    var value = (angle + pi) % (2f * pi)
    if (value < 0f) value += 2f * pi
    return value - pi
}

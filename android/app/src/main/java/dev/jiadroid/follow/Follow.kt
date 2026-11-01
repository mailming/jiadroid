package dev.jiadroid.follow

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Printed width of the mini-person code, in millimeters. The large sheet is 120. */
const val MARKER_WIDTH_MM = 120f

const val MARKER_PAYLOAD = "jiadroid:person"

/** Standing height of the person to follow, in millimeters. An adult is about 1700. */
const val PERSON_HEIGHT_MM = 1700f

/** Shoulder joint to shoulder joint, as a share of standing height. */
private const val SHOULDER_SHARE = 0.22f

/** Shoulder midpoint to hip midpoint, as a share of standing height. */
private const val TORSO_SHARE = 0.29f

private const val ALIGN_RAD = 0.28f
/** Start easing backward inside this range (never a hard STOP — that kick-stuttered). */
private const val CLOSE_M = 0.35f
private const val FAR_M = 1.40f
/** Stand-off Follow Me tries to hold, in meters. */
private const val TARGET_M = 0.95f
/** Duck-unit m/s per meter of (distance − target). */
private const val DIST_GAIN = 0.12f
private const val MAX_FOLLOW_FORWARD = 0.11f
private const val MAX_FOLLOW_BACKUP = -0.055f
/** Turn rate vs bearing. Keeps yaw continuous so Follow Me does not chatter at ALIGN_RAD. */
private const val YAW_GAIN = 0.85f
private const val MAX_FOLLOW_YAW = 0.55f

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

/**
 * Horizontal field of view of the upright picture, in radians.
 *
 * A real phone lens is passed in. The emulator webcam reports a 1 mm lens,
 * which is rejected by the caller, so this falls back to 70 degrees across
 * the long side of the camera image. In portrait that long side is vertical,
 * and the width of the picture is the shorter, narrower view.
 */
fun uprightFieldOfView(
    rotation: Int,
    uprightWidth: Int,
    uprightHeight: Int,
    sensorHfov: Double = 0.0,
    sensorVfov: Double = 0.0,
): Double {
    if (sensorHfov > 0.0 && sensorVfov > 0.0) {
        return if (rotation == 90 || rotation == 270) sensorVfov else sensorHfov
    }
    val longFov = Math.toRadians(70.0)
    if (uprightWidth <= 0 || uprightHeight <= 0) return longFov
    val portrait = rotation == 90 || rotation == 270
    val shortOverLong = if (portrait) {
        uprightWidth.toDouble() / uprightHeight
    } else {
        uprightHeight.toDouble() / uprightWidth
    }
    val shortFov = 2.0 * atan(tan(longFov / 2.0) * shortOverLong.coerceIn(0.3, 1.0))
    return if (portrait) shortFov else longFov
}

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

/**
 * Reads a person from body landmarks in upright image pixels.
 *
 * Distance comes from shoulder width and from torso length, whichever says
 * closer. A person turned sideways has narrow shoulders but the same torso,
 * so they are never mistaken for someone far away.
 */
fun measurePerson(
    shoulders: Pair<Point, Point>,
    hips: Pair<Point, Point>?,
    imageWidth: Int,
    hfovRad: Double,
    personHeightM: Float,
): Scene {
    if (imageWidth <= 0 || personHeightM <= 0f) return Scene(false, 0f, 0f)
    if (hfovRad <= 0.2 || hfovRad >= 2.8) return Scene(false, 0f, 0f)
    val fx = (imageWidth / 2.0) / tan(hfovRad / 2.0)
    val (left, right) = shoulders
    val shoulderPx = left.distanceTo(right)
    val top = Point((left.x + right.x) / 2f, (left.y + right.y) / 2f)
    var distance = Float.MAX_VALUE
    if (shoulderPx >= 8f) {
        distance = (personHeightM * SHOULDER_SHARE * fx / shoulderPx).toFloat()
    }
    if (hips != null) {
        val bottom = Point((hips.first.x + hips.second.x) / 2f, (hips.first.y + hips.second.y) / 2f)
        val torsoPx = top.distanceTo(bottom)
        if (torsoPx >= 8f) {
            distance = minOf(distance, (personHeightM * TORSO_SHARE * fx / torsoPx).toFloat())
        }
    }
    if (distance < 0.05f || distance > 8f) return Scene(false, 0f, 0f)
    val angle = atan(((top.x - imageWidth / 2f) / fx).toDouble()).toFloat()
    return Scene(true, angle, distance)
}

data class Point(val x: Float, val y: Float) {
    fun distanceTo(other: Point): Float = hypot(other.x - x, other.y - y)
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

fun decide(scene: Scene, subject: String = "Marker"): FollowDecision {
    if (!scene.visible) return stopped("$subject is lost")
    val headYaw = scene.angle.coerceIn(-0.5f, 0.5f)
    // Continuous yaw — hard TURN/FORWARD flips at ALIGN_RAD made the chassis stutter.
    val yaw = (-scene.angle * YAW_GAIN).coerceIn(-MAX_FOLLOW_YAW, MAX_FOLLOW_YAW)
    // Continuous range keeping. A hard STOP when "too close" made catch-up feel like
    // move→stop→kick→move; ease backward instead and glide toward TARGET_M.
    val rangeError = scene.distance - TARGET_M
    var forward = (rangeError * DIST_GAIN).coerceIn(MAX_FOLLOW_BACKUP, MAX_FOLLOW_FORWARD)
    val align = (1f - abs(scene.angle) / ALIGN_RAD).coerceIn(0f, 1f)
    if (forward > 0f) forward *= 0.45f + 0.55f * align
    val situation = when {
        scene.distance < CLOSE_M -> "$subject is too close"
        scene.angle < -ALIGN_RAD -> "$subject is left"
        scene.angle > ALIGN_RAD -> "$subject is right"
        scene.distance > FAR_M -> "$subject is too far"
        else -> "$subject is centered"
    }
    val command = when {
        scene.distance < CLOSE_M || forward < -0.01f -> "BACKWARD"
        scene.angle < -ALIGN_RAD -> "TURN LEFT"
        scene.angle > ALIGN_RAD -> "TURN RIGHT"
        abs(forward) < 0.012f -> "HOLD"
        scene.distance < TARGET_M -> "SLOW DOWN"
        else -> "FORWARD"
    }
    return FollowDecision(situation, command, forward, 0f, yaw, headYaw)
}

/**
 * Low-pass Follow Me velocities and hold-band hysteresis so stand-off noise
 * does not zero/unzero the wheels (firmware kick made that feel like run/stop).
 */
class MotionSmoother(private val alpha: Float = 0.22f) {
    private var forward = 0f
    private var yaw = 0f
    private var armed = false
    private var holding = false

    fun clear() {
        forward = 0f
        yaw = 0f
        armed = false
        holding = false
    }

    fun apply(decision: FollowDecision): FollowDecision {
        if (decision.command == "STOP" && decision.forward == 0f && decision.yaw == 0f) {
            clear()
            return decision
        }
        var targetForward = decision.forward
        var targetYaw = decision.yaw
        // Enter hold near stand-off; only leave once the range error is clearly back.
        if (holding) {
            if (abs(targetForward) > 0.040f) {
                holding = false
            } else {
                targetForward = 0f
            }
        } else if (abs(targetForward) < 0.014f && abs(targetYaw) < 0.18f) {
            holding = true
            targetForward = 0f
        }
        if (!armed) {
            forward = targetForward
            yaw = targetYaw
            armed = true
        } else {
            forward += (targetForward - forward) * alpha
            yaw += (targetYaw - yaw) * alpha
        }
        if (holding && abs(forward) < 0.02f) forward = 0f
        val command = when {
            decision.command == "STOP" -> "STOP"
            holding && abs(forward) < 0.01f -> "HOLD"
            else -> decision.command
        }
        return decision.copy(command = command, forward = forward, yaw = yaw)
    }
}

/**
 * When the target disappears, wait briefly then scan: look left, look right,
 * ease backward, repeat — so the camera can catch the person again. Cancels
 * as soon as they are visible.
 */
class LostSearch(
    private val graceMs: Long = 1_200L,
    private val maxSearchMs: Long = 8_000L,
    private val sliceMs: Long = 500L,
    private val searchYaw: Float = 0.45f,
    private val searchBackup: Float = -0.08f,
) {
    private var lostSinceMs: Long? = null
    private var lastDrive: FollowDecision? = null
    var searching: Boolean = false
        private set

    fun clear() {
        lostSinceMs = null
        searching = false
        lastDrive = null
    }

    /** Start scanning immediately (manual Search pad), skipping the grace wait. */
    fun begin(nowMs: Long) {
        lostSinceMs = nowMs - graceMs
        searching = true
    }

    fun enrich(scene: Scene, follow: FollowDecision, nowMs: Long, subject: String): FollowDecision {
        if (scene.visible) {
            if (follow.command != "STOP") lastDrive = follow
            lostSinceMs = null
            searching = false
            return follow
        }
        val started = lostSinceMs ?: nowMs.also { lostSinceMs = it }
        val lostFor = nowMs - started
        if (lostFor < graceMs) {
            searching = false
            // Keep rolling through brief pose drops instead of STOP→kick stutter.
            return lastDrive ?: follow
        }
        if (lostFor > maxSearchMs) {
            searching = false
            return follow
        }
        searching = true
        val phase = ((lostFor - graceMs) / sliceMs % 3L).toInt()
        return when (phase) {
            0 -> FollowDecision(
                "$subject is lost — looking left",
                "SEARCH",
                0f,
                0f,
                searchYaw,
                searchYaw.coerceIn(-0.5f, 0.5f),
            )
            1 -> FollowDecision(
                "$subject is lost — looking right",
                "SEARCH",
                0f,
                0f,
                -searchYaw,
                (-searchYaw).coerceIn(-0.5f, 0.5f),
            )
            else -> FollowDecision(
                "$subject is lost — backing up",
                "SEARCH",
                searchBackup,
                0f,
                0f,
                0f,
            )
        }
    }
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

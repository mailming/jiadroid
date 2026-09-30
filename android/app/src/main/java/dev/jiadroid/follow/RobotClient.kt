package dev.jiadroid.follow

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RobotException(val code: String, message: String) : Exception(message)

/** Inclusive limit a robot announced for one command field. */
data class Limit(val low: Double, val high: Double)

/**
 * TCP client for protocol 0.2 (and 0.1 robots). Each message is one JSON object and a newline.
 *
 * On connect the robot says what it is and which robot-level commands it accepts, with
 * limits. `walk` then sends the Follow Me decision as `motion.velocity`, rescaled to the
 * robot's limits, plus `head.pose` if the robot has a head. A 0.1 duck still gets the
 * old `walk.velocity`.
 */
class RobotClient private constructor(private val socket: Socket) {
    var name: String = ""
        private set
    var kind: String = "other"
        private set
    var servoCount: Int = 0
        private set
    var deviceCount: Int = 0
        private set

    /** op -> field -> limit, as announced in `session.hello`. */
    var controls: Map<String, Map<String, Limit>> = emptyMap()
        private set

    @Volatile
    var safety: String = "ready"
        private set

    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<JSONObject>>()
    private val hello = LinkedBlockingQueue<JSONObject>()
    private val thread = Thread(::readLoop, "jiadroid-read")

    fun supports(op: String): Boolean = controls.containsKey(op)

    fun walk(decision: FollowDecision) {
        if (supports("motion.velocity")) {
            val limits = controls.getValue("motion.velocity")
            val motion = JSONObject()
            motion.put("forward", fit(decision.forward.toDouble(), DUCK_FORWARD, limits["forward"]))
            motion.put("lateral", fit(decision.lateral.toDouble(), DUCK_LATERAL, limits["lateral"]))
            motion.put("yaw", fit(decision.yaw.toDouble(), DUCK_YAW, limits["yaw"]))
            noteSafety(request("motion.velocity", motion, 2000))
            if (supports("head.pose")) {
                val head = JSONObject()
                head.put("yaw", clamp(decision.headYaw.toDouble(), controls.getValue("head.pose")["yaw"]))
                noteSafety(request("head.pose", head, 2000))
            }
            return
        }
        val body = JSONObject()
        body.put("forward", decision.forward.toDouble())
        body.put("lateral", decision.lateral.toDouble())
        body.put("yaw", decision.yaw.toDouble())
        body.put("neck_pitch", 0.0)
        body.put("head_pitch", 0.0)
        body.put("head_yaw", decision.headYaw.toDouble())
        body.put("head_roll", 0.0)
        noteSafety(request("walk.velocity", body, 2000))
    }

    fun stop() {
        noteSafety(request("robot.stop", JSONObject(), 2000))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val bye = JSONObject()
            bye.put("v", 1)
            bye.put("id", "bye")
            bye.put("kind", "req")
            bye.put("op", "session.bye")
            bye.put("body", JSONObject())
            write(bye)
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
        thread.join(1000)
    }

    private fun start() {
        thread.isDaemon = true
        thread.start()
    }

    private fun awaitHello(timeoutMs: Long) {
        val message = hello.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IllegalStateException("Simulator did not say hello")
        if (message.optString("kind") != "evt") {
            throw IllegalStateException("Simulator connection closed")
        }
        val body = message.optJSONObject("body")
            ?: throw IllegalStateException("Simulator hello was empty")
        val version = body.optString("version")
        if (body.optString("protocol") != "jiadroid" || version !in SUPPORTED_VERSIONS) {
            throw IllegalStateException("Not a Jiadroid robot")
        }
        val robot = body.optJSONObject("robot")
            ?: throw IllegalStateException("Simulator hello was empty")
        name = robot.optString("name").ifEmpty { "Robot" }
        kind = robot.optString("kind").ifEmpty { if (version == "0.1") "biped" else "other" }
        safety = body.optJSONObject("safety")?.optString("state")?.ifEmpty { "ready" } ?: "ready"
        servoCount = countServos(body.optJSONArray("devices"))
        deviceCount = body.optJSONArray("devices")?.length() ?: 0
        controls = if (version == "0.1") legacyDuckControls() else parseControls(body.optJSONObject("controls"))
    }

    private fun request(op: String, body: JSONObject, timeoutMs: Long): JSONObject {
        if (closed.get()) throw IllegalStateException("Simulator connection closed")
        val id = ids.getAndIncrement().toString()
        val waiter = LinkedBlockingQueue<JSONObject>(1)
        pending[id] = waiter
        try {
            val message = JSONObject()
            message.put("v", 1)
            message.put("id", id)
            message.put("kind", "req")
            message.put("op", op)
            message.put("body", body)
            write(message)
            val result = waiter.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("Timed out waiting for the simulator")
            if (result.optString("kind") != "res") {
                throw IllegalStateException("Simulator connection closed")
            }
            if (result.has("error")) {
                val error = result.getJSONObject("error")
                throw RobotException(error.optString("code"), error.optString("message"))
            }
            return result.optJSONObject("body") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    private fun noteSafety(body: JSONObject) {
        val state = body.optJSONObject("safety")?.optString("state").orEmpty()
        if (state.isNotEmpty()) safety = state
    }

    private fun write(message: JSONObject) {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun readLoop() {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (!closed.get()) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                val message = JSONObject(line)
                when (message.optString("kind")) {
                    "res" -> pending[message.optString("id")]?.offer(message)
                    "evt" -> if (message.optString("op") == "session.hello") hello.offer(message)
                }
            }
        } catch (_: Exception) {
        } finally {
            val closedMessage = JSONObject().put("kind", "closed")
            hello.offer(closedMessage)
            pending.values.forEach { it.offer(closedMessage) }
        }
    }

    companion object {
        private val SUPPORTED_VERSIONS = setOf("0.1", "0.2")

        // Follow Me decides in Open Duck Mini units; `fit` rescales to the connected body.
        private const val DUCK_FORWARD = 0.15
        private const val DUCK_LATERAL = 0.2
        private const val DUCK_YAW = 1.0

        fun connect(host: String, port: Int): RobotClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.tcpNoDelay = true
            } catch (_: Exception) {
                socket.close()
                throw IllegalStateException("Can't reach $host:$port")
            }
            val client = RobotClient(socket)
            try {
                client.start()
                client.awaitHello(5000)
            } catch (error: Exception) {
                client.close()
                throw error
            }
            return client
        }
    }
}

private fun countServos(devices: JSONArray?): Int {
    if (devices == null) return 0
    var count = 0
    for (index in 0 until devices.length()) {
        if (devices.optJSONObject(index)?.optString("type") == "servo") count += 1
    }
    return count
}

internal fun parseControls(controls: JSONObject?): Map<String, Map<String, Limit>> {
    if (controls == null) return emptyMap()
    val result = HashMap<String, Map<String, Limit>>()
    for (op in controls.keys()) {
        val limits = controls.optJSONObject(op) ?: continue
        val fields = HashMap<String, Limit>()
        for (field in limits.keys()) {
            val span = limits.optJSONArray(field) ?: continue
            if (span.length() != 2) continue
            fields[field] = Limit(span.optDouble(0, 0.0), span.optDouble(1, 0.0))
        }
        result[op] = fields
    }
    return result
}

internal fun legacyDuckControls(): Map<String, Map<String, Limit>> = mapOf(
    "walk.velocity" to emptyMap(),
    "motion.velocity" to mapOf(
        "forward" to Limit(-0.15, 0.15),
        "lateral" to Limit(-0.2, 0.2),
        "yaw" to Limit(-1.0, 1.0),
    ),
    "head.pose" to mapOf(
        "neck_pitch" to Limit(-0.34, 1.1),
        "pitch" to Limit(-0.78, 0.3),
        "yaw" to Limit(-0.5, 0.5),
        "roll" to Limit(-0.5, 0.5),
    ),
)

/** Rescale a duck-unit value to another robot's limit. Unannounced fields become 0. */
internal fun fit(value: Double, duckBound: Double, limit: Limit?): Double {
    if (limit == null) return 0.0
    val bound = if (value >= 0) limit.high else -limit.low
    return value / duckBound * bound
}

internal fun clamp(value: Double, limit: Limit?): Double {
    if (limit == null) return 0.0
    return value.coerceIn(limit.low, limit.high)
}

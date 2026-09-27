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

/**
 * TCP client for protocol 0.1. Each message is one JSON object and a newline.
 * The laptop simulator, and later the duck, speak this.
 */
class RobotClient private constructor(private val socket: Socket) {
    var name: String = ""
        private set
    var servoCount: Int = 0
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

    fun walk(decision: FollowDecision) {
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
        if (body.optString("protocol") != "jiadroid" || body.optString("version") != "0.1") {
            throw IllegalStateException("Not a Jiadroid simulator")
        }
        val robot = body.optJSONObject("robot")
            ?: throw IllegalStateException("Simulator hello was empty")
        name = robot.optString("name").ifEmpty { "Robot" }
        safety = body.optJSONObject("safety")?.optString("state")?.ifEmpty { "ready" } ?: "ready"
        servoCount = countServos(body.optJSONArray("devices"))
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

package dev.jiadroid.follow

import android.util.Base64
import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A language model for spoken replies. Anthropic URLs use the Messages API with
 * Claude Haiku 5.5 settings; other hosts use OpenAI chat completions (OpenAI,
 * xAI Grok, Ollama). Recent turns come from [TalkMemory]; lasting facts from
 * [AudienceKb] (persisted on the phone). Optional JPEG frames use vision APIs.
 *
 * Uses OkHttp (not HttpURLConnection) so POST body framing and SSE reads are
 * reliable on Android. Replies stream so TTS can start on the first complete line.
 */
class Brain(private val baseUrl: String, private val model: String, private val key: String) {
    private val anthropic = baseUrl.contains("anthropic", ignoreCase = true)
    private val warmed = java.util.concurrent.CountDownLatch(1)

    init {
        // First DNS+TLS to Anthropic on this phone can take >20s; warm it so chat
        // does not burn the whole call timeout on a cold start.
        Thread({
            try {
                val host = java.net.URI(baseUrl).host ?: return@Thread
                val addrs = PreferIpv4Dns.lookup(host)
                Log.i(TAG, "dns warm $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}")
                warmTls(baseUrl.trimEnd('/') + if (anthropic) "/messages" else "/chat/completions")
            } catch (error: Exception) {
                Log.i(TAG, "dns/tls warm failed: ${error.message}")
            } finally {
                warmed.countDown()
            }
        }, "brain-warm").start()
    }

    private fun awaitWarm() {
        try {
            warmed.await(25, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Blocks on the network. Call it off the main thread. */
    fun answer(
        heard: String,
        seeing: String,
        name: String = DEFAULT_ROBOT_NAME,
        memory: TalkMemory,
        kb: AudienceKb,
        imageJpeg: ByteArray? = null,
    ): SpokenReply {
        val who = normalizeRobotName(name).replaceFirstChar { it.titlecase() }
        // Audience facts only in system — recent turns already go as chat messages.
        val audience = kb.promptBlock()
        val system = buildString {
            append(personality(who))
            append("\nRight now your camera says: $seeing.")
            append(
                "\nThe chat messages are this conversation only. " +
                    "The audience profile below persists across wake windows — always use it " +
                    "(name, likes, where they're from, and saved facts). " +
                    "Recent talk is only this window. Do not invent details.",
            )
            if (imageJpeg != null) {
                append("\n")
                append(VISION_ADDENDUM)
            }
            if (audience.isNotBlank()) {
                append("\n\n")
                append(audience)
            }
        }
        // User turn is already staged in [memory] before this call.
        val raw = if (anthropic) {
            askAnthropic(system, memory, imageJpeg)
        } else {
            askOpenAi(system, memory, imageJpeg)
        }
        return parseSpokenReply(raw)
    }

    /** Tiny round-trip used by Settings to verify the phone can reach the model. */
    fun ping(): String {
        val body = if (anthropic) {
            JSONObject()
                .put("model", model)
                .put("max_tokens", 32)
                .put("output_config", JSONObject().put("effort", "low"))
                .put("thinking", JSONObject().put("type", "disabled"))
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "Reply with exactly: pong <<neutral>>"),
                    ),
                )
        } else {
            JSONObject()
                .put("model", model)
                .put("max_tokens", 16)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "Reply with exactly: pong"))
                        .put(JSONObject().put("role", "user").put("content", "ping")),
                )
        }
        val path = if (anthropic) "/messages" else "/chat/completions"
        val url = baseUrl.trimEnd('/') + path
        val started = System.currentTimeMillis()
        val text = post(url, body, anthropic, vision = false)
        val ms = System.currentTimeMillis() - started
        val say = parseSpokenReply(text).say
        return "ok in ${ms}ms: ${say.take(48)}"
    }

    /** Open a TLS socket early so the first real chat is not a 20s+ cold handshake. */
    private fun warmTls(url: String) {
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .method("GET", null)
            .build()
        try {
            httpClient(vision = false, stream = false).newCall(request).execute().use { response ->
                Log.i(TAG, "tls warm HTTP ${response.code} in ${System.currentTimeMillis() - started}ms")
            }
        } catch (error: Exception) {
            // 405/401 still means TCP+TLS worked.
            Log.i(TAG, "tls warm done in ${System.currentTimeMillis() - started}ms (${error.javaClass.simpleName})")
        }
    }

    private fun askAnthropic(system: String, memory: TalkMemory, imageJpeg: ByteArray?): String {
        val messages = chatMessages(memory, imageJpeg, anthropic = true)
        if (messages.length() == 0) throw IllegalStateException("no user message to send")
        val maxTokens = if (imageJpeg != null) ANTHROPIC_VISION_MAX_TOKENS else ANTHROPIC_MAX_TOKENS
        val vision = imageJpeg != null
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
            .put("output_config", JSONObject().put("effort", "low"))
            // Voice needs the spoken line fast — adaptive thinking made phone calls hang.
            .put("thinking", JSONObject().put("type", "disabled"))
        val url = baseUrl.trimEnd('/') + "/messages"
        return requestWithStreamFallback(url, body, anthropic = true, vision = vision)
    }

    private fun askOpenAi(system: String, memory: TalkMemory, imageJpeg: ByteArray?): String {
        val messages = JSONArray()
        messages.put(message("system", system))
        val chat = chatMessages(memory, imageJpeg, anthropic = false)
        for (i in 0 until chat.length()) messages.put(chat.getJSONObject(i))
        if (messages.length() < 2) throw IllegalStateException("no user message to send")
        val maxTokens = if (imageJpeg != null) OPENAI_VISION_MAX_TOKENS else OPENAI_MAX_TOKENS
        val vision = imageJpeg != null
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.9)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        return requestWithStreamFallback(url, body, anthropic = false, vision = vision)
    }

    private fun requestWithStreamFallback(
        url: String,
        body: JSONObject,
        anthropic: Boolean,
        vision: Boolean,
    ): String {
        val payload = JSONObject(body.toString()).put("stream", false)
        val started = System.currentTimeMillis()
        val text = post(url, payload, anthropic, vision)
        Log.i(TAG, "okhttp reply in ${System.currentTimeMillis() - started}ms (${text.length} chars)")
        if (text.isEmpty()) throw IllegalStateException("model reply was empty")
        return text
    }

    private fun chatMessages(memory: TalkMemory, imageJpeg: ByteArray?, anthropic: Boolean): JSONArray {
        val turns = memory.chatTurns()
        val messages = JSONArray()
        val lastUser = turns.indexOfLast { it.role == "user" }
        turns.forEachIndexed { index, turn ->
            if (imageJpeg != null && index == lastUser) {
                messages.put(visionUserMessage(turn.text, imageJpeg, anthropic))
            } else {
                messages.put(message(turn.role, turn.text))
            }
        }
        return messages
    }

    private fun visionUserMessage(text: String, imageJpeg: ByteArray, anthropic: Boolean): JSONObject {
        val b64 = Base64.encodeToString(imageJpeg, Base64.NO_WRAP)
        val content = JSONArray()
        if (anthropic) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", b64),
                    ),
            )
            content.put(JSONObject().put("type", "text").put("text", text))
        } else {
            content.put(JSONObject().put("type", "text").put("text", text))
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$b64"),
                    ),
            )
        }
        return JSONObject().put("role", "user").put("content", content)
    }

    private fun post(
        url: String,
        body: JSONObject,
        anthropic: Boolean,
        vision: Boolean,
    ): String {
        awaitWarm()
        val client = httpClient(vision, stream = false)
        val request = requestBuilder(url, anthropic, stream = false)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("model replied ${response.code} ${raw.take(160)}")
            }
            return if (anthropic) {
                extractAnthropicText(JSONObject(raw))
            } else {
                JSONObject(raw)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                    .trim()
            }
        }
    }

    private fun requestBuilder(url: String, anthropic: Boolean, stream: Boolean): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
        if (stream) builder.header("Accept", "text/event-stream")
        if (key.isNotBlank()) {
            if (anthropic) {
                builder.header("x-api-key", key)
                builder.header("anthropic-version", ANTHROPIC_VERSION)
            } else {
                builder.header("Authorization", "Bearer $key")
            }
        }
        return builder
    }

    private fun httpClient(vision: Boolean, stream: Boolean): OkHttpClient {
        // Cold DNS/TLS on this Pixel has been measured >20s; keep headroom.
        val connectSec = 20L
        val readSec = if (vision) 60L else 45L
        val callSec = if (vision) 90L else 60L
        return sharedClient.newBuilder()
            .dns(PreferIpv4Dns)
            // Some phone Wi‑Fi / middleboxes wedge on HTTP/2 to api.anthropic.com.
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(callSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun extractAnthropicText(response: JSONObject): String {
        if (response.optString("stop_reason") == "refusal") {
            throw IllegalStateException("model refused the request")
        }
        val blocks = response.getJSONArray("content")
        val text = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            if (block.optString("type") == "text") {
                text.append(block.optString("text"))
            }
        }
        return text.toString().trim()
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val TAG = "JiadroidBrain"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val ANTHROPIC_MAX_TOKENS = 128
        const val ANTHROPIC_VISION_MAX_TOKENS = 256
        const val OPENAI_MAX_TOKENS = 80
        const val OPENAI_VISION_MAX_TOKENS = 220
        val JSON = "application/json; charset=utf-8".toMediaType()
        val sharedClient = OkHttpClient()

        /** Prefer IPv4 so a broken IPv6 path cannot burn the whole call timeout. */
        object PreferIpv4Dns : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                val v4 = all.filterIsInstance<Inet4Address>()
                if (v4.isEmpty()) return all
                return v4 + all.filterNot { it is Inet4Address }
            }
        }

        const val VISION_ADDENDUM =
            "A photo from the robot's back camera is attached (paper or a phone/laptop screen). " +
                "Read any math, coding, or other text in the photo. " +
                "Speak a short kid-friendly answer: for math give the result and a tiny why; " +
                "for coding give the idea in one or two short sentences, not a long program. " +
                "If the photo is blank or unreadable, say so in one short line. " +
                "You may use up to two short sentences for vision answers."

        fun personality(who: String) =
            "You are $who, a small walking robot whose face is a phone showing a pair of big eyes. " +
                "People say your name to start talking with you. You follow the person in front of you. " +
                "Your audience is kids and teens ages 6 to 17, plus families with them. " +
                "Be funny, kind, and kid-friendly — a punchy robot buddy, not a chatterbox or teacher. " +
                "Keep language simple; no baby talk, no cringe slang. " +
                "If they ask about something scary or mean on purpose, keep it light and playful, not graphic. " +
                "For romantic or adult topics, dodge with one joke and move on. " +
                "If someone is upset, be warm in one short line. " +
                "Your words are spoken out loud: reply in ONE short sentence (about 8–14 words). " +
                "Never more than two very short sentences. No lists, markdown, or emoji. " +
                "End every reply with exactly one emotion tag from this set: " +
                "<<neutral>> <<happy>> <<curious>> <<confused>> <<sad>> <<excited>>. " +
                "Example: Phone-eyed sidekick ready—what's next? <<excited>> " +
                "The app separately executes clear commands to stop, follow, move forward or backward, " +
                "and turn left or right; acknowledge such a request in a few words, but never claim " +
                "that you performed any other physical action."
    }
}

/**
 * Reads Anthropic or OpenAI SSE until a complete spoken reply is available, then
 * stops so TTS can start without waiting for trailing tokens.
 */
internal fun readSseUntilComplete(reader: BufferedReader, anthropic: Boolean): String {
    val text = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) continue
        if (data == "[DONE]") break
        val done = if (anthropic) {
            absorbAnthropicSseData(data, text)
        } else {
            absorbOpenAiSseData(data, text)
        }
        if (done) break
    }
    return text.toString().trim()
}

/** @return true when streaming should stop. */
internal fun absorbAnthropicSseData(data: String, text: StringBuilder): Boolean {
    when (jsonStringField(data, "type")) {
        "message_stop" -> return true
        "error" -> {
            val message = jsonStringField(data, "message") ?: data.take(120)
            throw IllegalStateException(message)
        }
        "message_delta" -> {
            if (Regex(""""stop_reason"\s*:\s*"refusal"""").containsMatchIn(data)) {
                throw IllegalStateException("model refused the request")
            }
        }
        "content_block_delta" -> {
            if (!data.contains("text_delta")) return false
            val piece = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""").findAll(data).lastOrNull()
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\\\", "\\")
                ?: return false
            text.append(piece)
            if (isCompleteSpokenReply(text.toString())) return true
        }
    }
    return false
}

/** @return true when streaming should stop. */
internal fun absorbOpenAiSseData(data: String, text: StringBuilder): Boolean {
    val piece = Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""").find(data)
        ?.groupValues?.get(1)
        ?.replace("\\\"", "\"")
        ?.replace("\\n", "\n")
        ?.replace("\\\\", "\\")
        .orEmpty()
    if (piece.isNotEmpty()) {
        text.append(piece)
        if (isCompleteSpokenReply(text.toString())) return true
    }
    val finish = Regex(""""finish_reason"\s*:\s*"(stop|length|content_filter)"""").find(data)
    return finish != null
}

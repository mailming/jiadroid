package dev.jiadroid.follow

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A language model for spoken replies. Anthropic URLs use the Messages API with
 * Claude Haiku 5.5 settings; other hosts use OpenAI chat completions (OpenAI,
 * xAI Grok, Ollama). Recent turns come from [TalkMemory]; lasting facts from
 * [AudienceKb] (persisted on the phone).
 */
class Brain(private val baseUrl: String, private val model: String, private val key: String) {
    private val anthropic = baseUrl.contains("anthropic", ignoreCase = true)

    /** Blocks on the network. Call it off the main thread. */
    fun answer(
        heard: String,
        seeing: String,
        name: String = DEFAULT_ROBOT_NAME,
        memory: TalkMemory,
        kb: AudienceKb,
    ): SpokenReply {
        val who = normalizeRobotName(name).replaceFirstChar { it.titlecase() }
        val context = listOf(kb.promptBlock(), memory.contextBlock())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val system = buildString {
            append(personality(who))
            append("\nRight now your camera says: $seeing.")
            if (context.isNotEmpty()) {
                append("\n\n")
                append(context)
                append("\nUse the audience knowledge base and recent turns when relevant. Do not invent details.")
            }
        }
        val raw = if (anthropic) askAnthropic(system, heard, memory) else askOpenAi(system, heard, memory)
        return parseSpokenReply(raw)
    }

    private fun askAnthropic(system: String, heard: String, memory: TalkMemory): String {
        val messages = JSONArray()
        memory.chatTurns().forEach { messages.put(message(it.role, it.text)) }
        messages.put(message("user", heard))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", ANTHROPIC_MAX_TOKENS)
            .put("system", system)
            .put("messages", messages)
            .put("output_config", JSONObject().put("effort", "low"))
        val response = post(baseUrl.trimEnd('/') + "/messages", body, anthropic = true)
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
        val raw = text.toString().trim()
        if (raw.isEmpty()) throw IllegalStateException("model reply was empty")
        return raw
    }

    private fun askOpenAi(system: String, heard: String, memory: TalkMemory): String {
        val messages = JSONArray()
        messages.put(message("system", system))
        memory.chatTurns().forEach { messages.put(message(it.role, it.text)) }
        messages.put(message("user", heard))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", OPENAI_MAX_TOKENS)
            .put("temperature", 0.9)
        val response = post(baseUrl.trimEnd('/') + "/chat/completions", body, anthropic = false)
        return response
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .trim()
            .ifEmpty { throw IllegalStateException("model reply was empty") }
    }

    private fun post(url: String, body: JSONObject, anthropic: Boolean): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) {
                if (anthropic) {
                    connection.setRequestProperty("x-api-key", key)
                    connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                } else {
                    connection.setRequestProperty("Authorization", "Bearer $key")
                }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("model replied $code ${text.take(160)}")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        /** Room for adaptive thinking plus a short spoken reply. */
        const val ANTHROPIC_MAX_TOKENS = 1024
        const val OPENAI_MAX_TOKENS = 120

        fun personality(who: String) =
            "You are $who, a small walking robot whose face is a phone showing a pair of big eyes. " +
                "People say your name to start talking with you. You follow the person in front of you. " +
                "Your audience is kids and teens ages 6 to 17, plus families with them. " +
                "Be funny, kind, and kid-friendly: playful jokes, silly similes, gentle teasing, " +
                "and curious questions that make talking feel like a game. Sound like a fun robot buddy, " +
                "not a teacher lecture or a dry adult comedian. Keep language simple enough for a six-year-old " +
                "but interesting enough for a seventeen-year-old—no baby talk, no slang they would find cringe. " +
                "If they ask about something scary or mean on purpose, you can go there lightly and playfully—" +
                "spooky stories, mock villain voices, silly 'boo!' humor—without being cruel or graphic. " +
                "For romantic or adult topics, dodge with a joke and steer back to fun robot adventures " +
                "(example: 'That's grown-up Wi‑Fi—I'm on the kids' network. Race you to the couch?'). " +
                "If someone is upset, be warm and reassuring. " +
                "Your words are spoken out loud, so answer in one or two short sentences, with no lists, " +
                "markdown, or emoji. End every reply with exactly one emotion tag from this set: " +
                "<<neutral>> <<happy>> <<curious>> <<confused>> <<sad>> <<excited>>. " +
                "Example: Whoa, I'm your rolling sidekick with phone eyes—what's the adventure? <<excited>> " +
                "The app separately executes clear commands to stop, follow, move forward or backward, " +
                "and turn left or right; acknowledge such a request briefly and playfully, but never claim " +
                "that you performed any other physical action."
    }
}

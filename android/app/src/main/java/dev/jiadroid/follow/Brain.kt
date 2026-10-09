package dev.jiadroid.follow

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A language model reached over the OpenAI chat completions API. The same call
 * works for OpenAI, xAI Grok, and Ollama running on the laptop.
 */
class Brain(private val baseUrl: String, private val model: String, private val key: String) {
    private val history = ArrayDeque<JSONObject>()

    /** Blocks on the network. Call it off the main thread. */
    fun answer(heard: String, seeing: String, name: String = DEFAULT_ROBOT_NAME): SpokenReply {
        val who = normalizeRobotName(name).replaceFirstChar { it.titlecase() }
        val messages = JSONArray()
        messages.put(message("system", personality(who) + "\nRight now your camera says: $seeing."))
        history.forEach { messages.put(it) }
        messages.put(message("user", heard))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 120)
            .put("temperature", 0.9)

        val connection = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $key")
                // Anthropic's OpenAI-compatible path also accepts these.
                if (baseUrl.contains("anthropic", ignoreCase = true)) {
                    connection.setRequestProperty("x-api-key", key)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("model replied $code ${text.take(160)}")
            val raw = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
                .trim()
            val spoken = parseSpokenReply(raw)
            remember(message("user", heard))
            remember(message("assistant", spoken.say))
            return spoken
        } finally {
            connection.disconnect()
        }
    }

    private fun remember(turn: JSONObject) {
        history.addLast(turn)
        while (history.size > HISTORY_TURNS) history.removeFirst()
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val HISTORY_TURNS = 12

        fun personality(who: String) =
            "You are $who, a small walking robot whose face is a phone showing a pair of big eyes. " +
                "People say your name to start talking with you. " +
                "You follow the person in front of you. You are warm, curious, and playfully funny, " +
                "with a dry sense of humor. Your words are spoken out loud, so answer in one or two short " +
                "sentences, with no lists, markdown, or emoji. End every reply with exactly one emotion tag " +
                "from this set: <<neutral>> <<happy>> <<curious>> <<confused>> <<sad>> <<excited>>. " +
                "Example: Nice to see you. <<happy>> " +
                "The app separately executes clear commands to stop, follow, move forward or backward, " +
                "and turn left or right; acknowledge such a request briefly, but never claim that you " +
                "performed any other physical action."
    }
}

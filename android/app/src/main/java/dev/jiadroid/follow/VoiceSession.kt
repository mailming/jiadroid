package dev.jiadroid.follow

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Listens with the phone microphone and speaks the reply. Speech is turned into
 * text by Android's [SpeechRecognizer] (usually Google's on-device / cloud engine).
 * The mic mostly pauses while the phone talks; a wake-name barge-in path stays
 * open so "hey Vicky…" can interrupt a long reply.
 */
class VoiceSession(
    private val activity: AppCompatActivity,
    private val seeing: () -> String,
    private val onLine: (String) -> Unit,
    private val onTurn: (who: String, text: String) -> Unit,
    private val onHearing: (String) -> Unit,
    private val onEmotion: (Emotion) -> Unit,
    private val onAction: (VoiceAction) -> Unit,
) {
    /** When null, replies come from the phone's own short list. */
    @Volatile var brain: Brain? = null
    @Volatile var robotName: String = DEFAULT_ROBOT_NAME
    private val thinker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val memory = TalkMemory()
    private val kb = AudienceKb(
        loadJson = {
            activity.getSharedPreferences(KB_PREFS, AppCompatActivity.MODE_PRIVATE)
                .getString(KB_KEY, null)
        },
        saveJson = { json ->
            activity.getSharedPreferences(KB_PREFS, AppCompatActivity.MODE_PRIVATE)
                .edit()
                .putString(KB_KEY, json)
                .commit()
        },
    )
    private var speech: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var alive = false
    private var speaking = false
    private var listening = false
    private var readyToSpeak = false
    private var speakGeneration = 0
    private var restartGeneration = 0
    private var lastSpoken = ""
    private var ignoreHearUntilMs = 0L
    private var earlyStopAtMs = 0L
    private val attention = AttentionSession()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!alive || speaking) return
            onEmotion(Emotion.LISTENING)
            onLine("Listening")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Keep [listening] true until onResults/onError so a scheduled restart
            // cannot open a second recognizer mid-finalization.
        }

        override fun onError(error: Int) {
            listening = false
            Log.i(TAG, "speech error: $error")
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onLine("Microphone permission is required to listen.")
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Often happens after cancel while speaking; just wait to restart.
                    scheduleListen(delayMs = 80L)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleListen(delayMs = 500L)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    onLine("Speech service network glitch. Listening again.")
                    scheduleListen(delayMs = 600L)
                }
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                -> {
                    // Rapid restarts make Google's recognizer stick in a disconnect loop.
                    onLine("Speech service reconnecting…")
                    rebuildSpeech()
                    scheduleListen(delayMs = 1_500L)
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal idle end — reopen quickly so the next wake is not missed.
                    if (attention.isEngaged(SystemClock.elapsedRealtime())) {
                        onLine("Listening")
                    }
                    scheduleListen(delayMs = 120L)
                }
                else -> scheduleListen(delayMs = 200L)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = bestResult(results)
            val wasSpeaking = speaking
            // Allow wake-word barge-in even while TTS is playing.
            if (!text.isNullOrBlank() && alive) {
                answer(text)
            }
            // Full listen after a turn ends; keep a wake-only mic open while talking.
            if (!speaking) {
                scheduleListen()
            } else if (wasSpeaking) {
                scheduleListen(delayMs = 250L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!alive) return
            val text = bestResult(partialResults) ?: return
            if (text.isBlank()) return
            // Halt as soon as "stop" appears — don't wait for end-of-speech silence.
            if (tryEarlyStop(text)) return
            // Stop talking if the user calls the wake name over the reply.
            if (speaking && parseAttention(text, robotName).addressed) {
                bargeIn()
            }
            if (speaking) return
            onEmotion(Emotion.LISTENING)
            onHearing(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        speaker = TextToSpeech(activity) { status ->
            readyToSpeak = status == TextToSpeech.SUCCESS
            speaker?.language = Locale.US
            speaker?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = activity.runOnUiThread {
                    // Barge-in already cleared [speaking]; don't re-arm echo cooldown.
                    if (speaking) resume()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = activity.runOnUiThread {
                    if (speaking) resume()
                }
            })
        }
    }

    fun start() {
        if (alive) return
        alive = true
        ensureSpeech()
        beginListen()
    }

    fun stop() {
        alive = false
        speaking = false
        speakGeneration += 1
        restartGeneration += 1
        attention.clear()
        cancelListen()
        speaker?.stop()
    }

    /** Drop the current listening session and start again. Debug mode can reset the emulator mic. */
    fun reopen() {
        if (!alive) return
        cancelListen()
        scheduleListen(delayMs = 120L)
    }

    fun destroy() {
        stop()
        thinker.shutdownNow()
        speaker?.shutdown()
        speaker = null
        speech?.destroy()
        speech = null
    }

    private fun ensureSpeech() {
        if (speech != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            onLine("Speech recognition is not available on this phone")
            return
        }
        speech = SpeechRecognizer.createSpeechRecognizer(activity).also {
            it.setRecognitionListener(listener)
        }
    }

    /** Tear down a dead recognizer binder before the next listen attempt. */
    private fun rebuildSpeech() {
        try {
            speech?.destroy()
        } catch (error: Exception) {
            Log.i(TAG, "destroy speech: ${error.message}")
        }
        speech = null
        ensureSpeech()
    }

    private fun beginListen() {
        if (!alive || listening) return
        val speech = speech
        if (speech == null) {
            ensureSpeech()
            if (this.speech == null) return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            // Keep silence short so "stop" finalizes quickly while following.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        }
        try {
            listening = true
            this.speech!!.startListening(intent)
            if (!speaking) {
                onEmotion(Emotion.LISTENING)
                onLine("Listening")
            }
        } catch (error: Exception) {
            listening = false
            Log.w(TAG, "startListening failed", error)
            onLine("Could not start listening: ${error.message}")
            scheduleListen(delayMs = 800L)
        }
    }

    private fun cancelListen() {
        listening = false
        restartGeneration += 1
        try {
            speech?.cancel()
        } catch (error: Exception) {
            Log.i(TAG, "cancel listening: ${error.message}")
        }
    }

    private fun scheduleListen(delayMs: Long = 180L) {
        if (!alive) return
        val generation = ++restartGeneration
        main.postDelayed({
            if (generation != restartGeneration) return@postDelayed
            if (!alive || listening) return@postDelayed
            beginListen()
        }, delayMs)
    }

    private fun resume() {
        speaking = false
        speakGeneration += 1
        val now = SystemClock.elapsedRealtime()
        // Keep the conversation window open across the reply so follow-ups work.
        if (attention.isEngaged(now)) attention.keepAlive(now)
        // Room echo of our own TTS often lands right after playback ends.
        ignoreHearUntilMs = now + ECHO_COOLDOWN_MS
        if (!alive) return
        onEmotion(Emotion.LISTENING)
        onLine("Listening")
        scheduleListen(delayMs = ECHO_COOLDOWN_MS)
    }

    /** Stop TTS so a wake word said over the reply can be handled. */
    private fun bargeIn() {
        speakGeneration += 1
        try {
            speaker?.stop()
        } catch (error: Exception) {
            Log.i(TAG, "barge-in stop: ${error.message}")
        }
        speaking = false
        ignoreHearUntilMs = 0L
        onLine("Listening")
    }

    /**
     * Fire STOP from a partial transcript. Google often waits for silence before
     * finals; while Follow Me is driving that delay feels dangerous.
     */
    private fun tryEarlyStop(heard: String): Boolean {
        if (parseVoiceAction(heard)?.motion != VoiceMotion.STOP) return false
        val now = SystemClock.elapsedRealtime()
        if (now - earlyStopAtMs < 1_200L) return true
        earlyStopAtMs = now
        answer(heard)
        return true
    }

    /** Halt motors immediately — no wake name, no chat model. */
    private fun applyStopNow(heard: String) {
        val action = VoiceAction(VoiceMotion.STOP)
        if (speaking) bargeIn()
        ignoreHearUntilMs = 0L
        attention.keepAlive(SystemClock.elapsedRealtime())
        onTurn("You", heard)
        onAction(action)
        onTurn("Action", action.description)
        speaking = true
        cancelListen()
        speak(
            SpokenReply(action.spokenAck, Emotion.NEUTRAL),
            rememberUser = heard,
            userAlreadyStaged = false,
        )
    }

    /**
     * Answers [heard]. Microphone lines need the robot's name once to open a short
     * conversation window; later turns keep going until ~30s of silence. Typed Debug
     * lines pass [requireName]=false so the keyboard can talk without saying it.
     * Exception: "stop" / "halt" always works, even mid-Follow with no wake name.
     */
    fun answer(heard: String, requireName: Boolean = true) {
        if (isNoise(heard)) return
        if (isEchoOfSelf(heard)) return
        val now = SystemClock.elapsedRealtime()
        // Safety stop first — works outside the wake window and during TTS.
        if (parseVoiceAction(heard)?.motion == VoiceMotion.STOP) {
            applyStopNow(heard)
            return
        }
        val name = normalizeRobotName(robotName)
        val wake = parseAttention(heard, name)
        // Saying the wake name over TTS interrupts the reply.
        if (speaking) {
            if (wake.addressed) bargeIn() else return
        }
        // After TTS, ignore loudspeaker echo — but never drop a wake / open-window turn.
        if (now < ignoreHearUntilMs) {
            val keep =
                !requireName || wake.addressed || attention.isEngaged(now)
            if (!keep) return
        }
        val gate = attention.consider(heard, name, requireName, now)
        if (!gate.addressed) return
        // New wake after the hold expired: clear short-term chat only. AudienceKb
        // (name / likes / facts) stays on disk and is reloaded for this conversation.
        if (gate.newConversation) {
            memory.clear()
            kb.reload()
        }
        onTurn("You", heard)
        if (gate.utterance.isEmpty()) {
            speaking = true
            cancelListen()
            speak(SpokenReply("Yes?", Emotion.CURIOUS), rememberUser = null, userAlreadyStaged = false)
            return
        }
        // Motion commands move the body immediately and skip the chat model so
        // "turn left" is not delayed or dropped while Thinking…
        parseVoiceAction(gate.utterance)?.let { action ->
            onAction(action)
            onTurn("Action", action.description)
            speaking = true
            cancelListen()
            attention.keepAlive(SystemClock.elapsedRealtime())
            speak(
                SpokenReply(action.spokenAck, Emotion.EXCITED),
                rememberUser = gate.utterance,
                userAlreadyStaged = false,
            )
            return
        }
        speaking = true
        cancelListen()
        attention.keepAlive(SystemClock.elapsedRealtime())
        val seen = seeing()
        val request = gate.utterance
        // Deterministic KB / short-term recall before spending a model call.
        // Do this before staging the new user turn so "what did I just say" still works.
        val localRecall = kb.recallReply(request) ?: memory.recallReply(request)
        if (localRecall != null) {
            speak(localRecall, rememberUser = request, userAlreadyStaged = false)
            return
        }
        // Absorb facts and stage this user line BEFORE the model runs so the KB
        // and chat history are visible on this same turn. Corrections also rewrite
        // mis-heard names already sitting in short-term TalkMemory.
        absorbIntoMemory(request)
        val brain = brain
        if (brain == null) {
            val spoken = reply(request, seen, name, memory, kb)
            speak(spoken, rememberUser = request, userAlreadyStaged = true)
            return
        }
        onEmotion(Emotion.THINKING)
        onLine("Thinking…")
        // Keep a wake mic open so a long model call can still be interrupted.
        scheduleListen(delayMs = 200L)
        thinker.execute {
            val spoken = try {
                brain.answer(request, seen, name, memory, kb)
            } catch (error: Exception) {
                activity.runOnUiThread { onTurn("Model", "failed: ${error.message}") }
                reply(request, seen, name, memory, kb)
            }
            activity.runOnUiThread {
                attention.keepAlive(SystemClock.elapsedRealtime())
                speak(spoken, rememberUser = request, userAlreadyStaged = true)
            }
        }
    }

    private fun absorbIntoMemory(request: String) {
        val changes = kb.rememberFrom(request)
        memory.rememberUser(request)
        for (change in changes) {
            val previous = change.previous ?: continue
            memory.rewriteMention(previous, change.value)
        }
    }

    private fun speak(spoken: SpokenReply, rememberUser: String?, userAlreadyStaged: Boolean) {
        if (rememberUser != null) {
            if (userAlreadyStaged) {
                memory.rememberAssistant(spoken.say)
            } else {
                absorbIntoMemory(rememberUser)
                memory.rememberAssistant(spoken.say)
            }
        }
        cancelListen()
        lastSpoken = spoken.say
        onEmotion(spoken.emotion)
        onTurn("Me", spoken.say)
        onLine(spoken.say)
        val speaker = speaker
        val generation = ++speakGeneration
        if (!readyToSpeak || speaker == null ||
            speaker.speak(spoken.say, TextToSpeech.QUEUE_FLUSH, null, "reply") == TextToSpeech.ERROR
        ) {
            onTurn("Voice", "text to speech is not ready")
            resume()
            return
        }
        // Wake-only mic while talking so "hey Vicky" can interrupt.
        scheduleListen(delayMs = 350L)
        // Safety valve: stop TTS then cool down.
        main.postDelayed({
            if (speaking && generation == speakGeneration) {
                speaker.stop()
                resume()
            }
        }, SPEAK_LIMIT_MS)
    }

    private fun isNoise(heard: String): Boolean {
        val words = heard.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.isEmpty() || words.all { it in NOISE }
    }

    /** Drop transcripts that look like the mic picked up our own loudspeaker. */
    private fun isEchoOfSelf(heard: String): Boolean {
        val spoken = normalizeForEcho(lastSpoken)
        val mine = normalizeForEcho(heard)
        if (spoken.isEmpty() || mine.isEmpty()) return false
        if (spoken.contains(mine) || mine.contains(spoken.take(48))) return true
        val heardWords = mine.split(' ').filter { it.length > 2 }.toSet()
        val spokenWords = spoken.split(' ').filter { it.length > 2 }.toSet()
        if (heardWords.isEmpty()) return false
        val hit = heardWords.count { it in spokenWords }
        return hit.toDouble() / heardWords.size >= 0.6
    }

    private fun normalizeForEcho(text: String): String =
        text.lowercase()
            .replace(Regex("""[^a-z0-9\s]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun bestResult(results: Bundle?): String? {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return matches?.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private companion object {
        const val TAG = "Jiadroid"
        const val SPEAK_LIMIT_MS = 10_000L
        /** Short mute after TTS so the loudspeaker is not transcribed as the user. */
        const val ECHO_COOLDOWN_MS = 450L
        const val KB_PREFS = "jiadroid_audience_kb"
        const val KB_KEY = "notes_json"
        val NOISE = setOf("huh", "uh", "um", "ah", "hmm", "mm", "hm", "mhm", "oh", "the", "a", "an")
    }
}

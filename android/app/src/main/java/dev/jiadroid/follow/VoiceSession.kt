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
    /** Back-camera JPEG for “look at this / solve this”. Call off the main thread. */
    private val snapshot: () -> ByteArray?,
    /** Bring the eyes (front) screen forward so the audience sees the answer. */
    private val onShowEyes: () -> Unit,
    /** Show or clear the large vision answer on the eyes screen. */
    private val onVisionResult: (String?) -> Unit,
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
                .apply()
        },
    )
    private var speech: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var alive = false
    private var speaking = false
    private var listening = false
    private var readyToSpeak = false
    private var speakGeneration = 0
    private var thinkGeneration = 0
    private var restartGeneration = 0
    private var lastSpoken = ""
    private var ignoreHearUntilMs = 0L
    private var earlyStopAtMs = 0L
    private var noMatchStreak = 0
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
                    noMatchStreak = 0
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
                    // Motor noise / silence often ends as no-match. After a streak,
                    // rebuild the recognizer so the session does not go deaf.
                    noMatchStreak += 1
                    if (noMatchStreak >= 6) {
                        noMatchStreak = 0
                        Log.i(TAG, "speech no-match streak — rebuilding recognizer")
                        rebuildSpeech()
                        scheduleListen(delayMs = 400L)
                    } else {
                        if (attention.isEngaged(SystemClock.elapsedRealtime())) {
                            onLine("Listening")
                        }
                        scheduleListen(delayMs = 120L)
                    }
                }
                else -> scheduleListen(delayMs = 200L)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            noMatchStreak = 0
            val text = bestResult(results)
            val wasSpeaking = speaking
            if (!text.isNullOrBlank() && alive) {
                // While TTS plays, only wake/stop may interrupt — otherwise we hear ourselves.
                if (speaking && !isWakeOrStop(text)) {
                    Log.i(TAG, "ignore loudspeaker while talking: $text")
                } else {
                    answer(text)
                }
            }
            // Full listen after a turn ends; wake/stop mic while talking.
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
            // Only the wake name may interrupt TTS (not open-window chatter / echo).
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
        thinkGeneration += 1
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

    /** Stop TTS so a wake name said over the reply can be handled. */
    private fun bargeIn() {
        speakGeneration += 1
        thinkGeneration += 1
        try {
            speaker?.stop()
        } catch (error: Exception) {
            Log.i(TAG, "barge-in stop: ${error.message}")
        }
        speaking = false
        // Brief mute so the interrupted TTS trail is not heard as the user.
        ignoreHearUntilMs = SystemClock.elapsedRealtime() + ECHO_COOLDOWN_MS
        onLine("Listening")
    }

    private fun isWakeOrStop(heard: String): Boolean {
        if (parseVoiceAction(heard)?.motion == VoiceMotion.STOP) return true
        return parseAttention(heard, robotName).addressed
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
        // TTS can be interrupted by wake name only (stop already handled above).
        if (speaking) {
            if (wake.addressed) bargeIn() else {
                Log.i(TAG, "drop while speaking: $heard")
                return
            }
        }
        // A new user turn cancels any in-flight model reply still "Thinking…".
        thinkGeneration += 1
        // After TTS, ignore loudspeaker echo. Do not accept open-window follow-ups
        // here — that is how the robot's own reply gets treated as the user.
        if (now < ignoreHearUntilMs) {
            if (requireName && !wake.addressed) return
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
            speak(SpokenReply("Yes?", Emotion.CURIOUS), rememberUser = null, userAlreadyStaged = false)
            return
        }
        // Motion commands move the body immediately and skip the chat model so
        // "turn left" is not delayed or dropped while Thinking…
        parseVoiceAction(gate.utterance)?.let { action ->
            onAction(action)
            onTurn("Action", action.description)
            attention.keepAlive(SystemClock.elapsedRealtime())
            speak(
                SpokenReply(action.spokenAck, Emotion.EXCITED),
                rememberUser = gate.utterance,
                userAlreadyStaged = false,
            )
            return
        }
        attention.keepAlive(SystemClock.elapsedRealtime())
        val seen = seeing()
        val request = gate.utterance
        val look = wantsCameraLook(request)
        // Deterministic KB / short-term recall before spending a model call.
        // Do this before staging the new user turn so "what did I just say" still works.
        // Vision asks always go to the camera + model (skip local recall).
        if (!look) {
            val localRecall = kb.recallReply(request) ?: memory.recallReply(request)
            if (localRecall != null) {
                speak(localRecall, rememberUser = request, userAlreadyStaged = false)
                return
            }
        }
        // Absorb facts and stage this user line BEFORE the model runs so the KB
        // and chat history are visible on this same turn. Corrections also rewrite
        // mis-heard names already sitting in short-term TalkMemory.
        absorbIntoMemory(request)
        val brain = brain
        if (brain == null) {
            val spoken = if (look) {
                SpokenReply("Add a model key so I can look at that.", Emotion.CURIOUS)
            } else {
                reply(request, seen, name, memory, kb)
            }
            speak(spoken, rememberUser = request, userAlreadyStaged = true, vision = look)
            return
        }
        // Stay listening while the model runs — do not hold the speaking lock here,
        // or follow-ups get dropped until TTS finishes.
        val thinkId = thinkGeneration
        onEmotion(Emotion.THINKING)
        if (look) {
            onShowEyes()
            onVisionResult("Looking with the back camera…")
            onLine("Looking with the back camera…")
        } else {
            onVisionResult(null)
            onLine("Thinking…")
        }
        scheduleListen(delayMs = 120L)
        thinker.execute {
            val jpeg = if (look) snapshot() else null
            val spoken = when {
                look && jpeg == null -> SpokenReply(
                    "Point the back camera at the paper or screen.",
                    Emotion.CURIOUS,
                )
                else -> try {
                    if (look) {
                        activity.runOnUiThread { onTurn("Camera", "back camera photo sent to the model") }
                    }
                    brain.answer(request, seen, name, memory, kb, jpeg)
                } catch (error: Exception) {
                    activity.runOnUiThread { onTurn("Model", "failed: ${error.message}") }
                    reply(request, seen, name, memory, kb)
                }
            }
            activity.runOnUiThread {
                if (thinkId != thinkGeneration) {
                    Log.i(TAG, "discard stale model reply")
                    return@runOnUiThread
                }
                attention.keepAlive(SystemClock.elapsedRealtime())
                speak(spoken, rememberUser = request, userAlreadyStaged = true, vision = look)
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

    private fun speak(
        spoken: SpokenReply,
        rememberUser: String?,
        userAlreadyStaged: Boolean,
        vision: Boolean = false,
    ) {
        if (rememberUser != null) {
            if (userAlreadyStaged) {
                memory.rememberAssistant(spoken.say)
            } else {
                absorbIntoMemory(rememberUser)
                memory.rememberAssistant(spoken.say)
            }
        }
        speaking = true
        cancelListen()
        lastSpoken = spoken.say
        onEmotion(spoken.emotion)
        onTurn("Me", spoken.say)
        onLine(spoken.say)
        if (vision) {
            onShowEyes()
            onVisionResult(spoken.say)
        } else {
            onVisionResult(null)
        }
        val speaker = speaker
        val generation = ++speakGeneration
        if (!readyToSpeak || speaker == null ||
            speaker.speak(spoken.say, TextToSpeech.QUEUE_FLUSH, null, "reply") == TextToSpeech.ERROR
        ) {
            onTurn("Voice", "text to speech is not ready")
            resume()
            return
        }
        // Barge-in mic while talking so wake / follow-ups can interrupt.
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
        // Lower threshold — short replies often share most content words with the echo.
        return hit.toDouble() / heardWords.size >= 0.45
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
        /** Mute after TTS so the loudspeaker is not transcribed as the user. */
        const val ECHO_COOLDOWN_MS = 1_000L
        const val KB_PREFS = "jiadroid_audience_kb"
        const val KB_KEY = "notes_json"
        val NOISE = setOf("huh", "uh", "um", "ah", "hmm", "mm", "hm", "mhm", "oh", "the", "a", "an")
    }
}

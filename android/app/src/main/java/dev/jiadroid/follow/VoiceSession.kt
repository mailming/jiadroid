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
 * Listening pauses while the phone talks.
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
    private var speech: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var alive = false
    private var speaking = false
    private var listening = false
    private var readyToSpeak = false
    private var speakGeneration = 0
    private var restartGeneration = 0
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
            listening = false
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
                SpeechRecognizer.ERROR_SERVER -> {
                    onLine("Speech service unavailable. Listening again.")
                    scheduleListen(delayMs = 800L)
                }
                else -> scheduleListen(delayMs = 200L)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = bestResult(results)
            if (!text.isNullOrBlank() && alive && !speaking) {
                answer(text)
            }
            scheduleListen()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!alive || speaking) return
            val text = bestResult(partialResults) ?: return
            if (text.isBlank()) return
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
                override fun onDone(utteranceId: String?) = activity.runOnUiThread { resume() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = activity.runOnUiThread { resume() }
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

    private fun beginListen() {
        if (!alive || speaking || listening) return
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
        }
        try {
            listening = true
            this.speech!!.startListening(intent)
            onEmotion(Emotion.LISTENING)
            onLine("Listening")
        } catch (error: Exception) {
            listening = false
            Log.w(TAG, "startListening failed", error)
            onLine("Could not start listening: ${error.message}")
            scheduleListen(delayMs = 800L)
        }
    }

    private fun cancelListen() {
        listening = false
        try {
            speech?.cancel()
        } catch (error: Exception) {
            Log.i(TAG, "cancel listening: ${error.message}")
        }
    }

    private fun scheduleListen(delayMs: Long = 180L) {
        if (!alive || speaking) return
        val generation = ++restartGeneration
        main.postDelayed({
            if (generation != restartGeneration) return@postDelayed
            if (!alive || speaking || listening) return@postDelayed
            beginListen()
        }, delayMs)
    }

    private fun resume() {
        speaking = false
        speakGeneration += 1
        if (!alive) return
        onEmotion(Emotion.LISTENING)
        onLine("Listening")
        scheduleListen(delayMs = 250L)
    }

    /**
     * Answers [heard]. Microphone lines need the robot's name once to open a short
     * conversation window; later turns keep going until ~30s of silence. Typed Debug
     * lines pass [requireName]=false so the keyboard can talk without saying it.
     */
    fun answer(heard: String, requireName: Boolean = true) {
        if (speaking) return
        if (isNoise(heard)) return
        val name = normalizeRobotName(robotName)
        val gate = attention.consider(heard, name, requireName, SystemClock.elapsedRealtime())
        if (!gate.addressed) return
        onTurn("You", heard)
        if (gate.utterance.isEmpty()) {
            speaking = true
            cancelListen()
            speak(SpokenReply("Yes?", Emotion.CURIOUS))
            return
        }
        parseVoiceAction(gate.utterance)?.let { action ->
            onAction(action)
            onTurn("Action", action.description)
        }
        speaking = true
        cancelListen()
        val seen = seeing()
        val brain = brain
        if (brain == null) {
            speak(reply(gate.utterance, seen, name))
            return
        }
        onEmotion(Emotion.THINKING)
        onLine("Thinking")
        thinker.execute {
            val spoken = try {
                brain.answer(gate.utterance, seen, name)
            } catch (error: Exception) {
                activity.runOnUiThread { onTurn("Model", "failed: ${error.message}") }
                reply(gate.utterance, seen, name)
            }
            activity.runOnUiThread { speak(spoken) }
        }
    }

    private fun speak(spoken: SpokenReply) {
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
        main.postDelayed({
            if (speaking && generation == speakGeneration) resume()
        }, SPEAK_LIMIT_MS)
    }

    private fun isNoise(heard: String): Boolean {
        val words = heard.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.isEmpty() || words.all { it in NOISE }
    }

    private fun bestResult(results: Bundle?): String? {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return matches?.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private companion object {
        const val TAG = "Jiadroid"
        const val SPEAK_LIMIT_MS = 8000L
        val NOISE = setOf("huh", "uh", "um", "ah", "hmm", "mm", "hm", "mhm", "oh", "the", "a", "an")
    }
}

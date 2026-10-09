package dev.jiadroid.follow

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Listens with the phone microphone and speaks the reply. Speech is turned into
 * text on the phone by Vosk, so it works without Google speech or a network.
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
    private var model: Model? = null
    private var loading = false
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var listenThread: Thread? = null
    private var speaker: TextToSpeech? = null
    private var alive = false
    private var speaking = false
    private var readyToSpeak = false
    private var speakGeneration = 0
    private var reportedDrop = false
    private val micLock = Any()
    private val attention = AttentionSession()

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
        val model = model
        if (model != null) {
            listen(model)
        } else if (!loading) {
            loading = true
            onLine("Getting ready to listen")
            StorageService.unpack(activity, "model-en-us", "model", { loaded ->
                loading = false
                this.model = loaded
                if (alive) listen(loaded)
            }, { error ->
                loading = false
                onLine("Could not load the speech model: ${error.message}")
            })
        }
    }

    fun stop() {
        alive = false
        speaking = false
        speakGeneration += 1
        attention.clear()
        closeRecorder()
        listenThread?.join(500)
        listenThread = null
        recognizer?.close()
        recognizer = null
        speaker?.stop()
    }

    /** Drop the current recording and open the microphone again. Debug mode can reset the emulator mic. */
    fun reopen() {
        if (!alive) return
        reportedDrop = false
        closeRecorder()
    }

    fun destroy() {
        stop()
        thinker.shutdownNow()
        speaker?.shutdown()
        speaker = null
        model?.close()
        model = null
    }

    private fun listen(model: Model) {
        if (listenThread != null) return
        try {
            recognizer = Recognizer(model, SAMPLE_RATE)
        } catch (error: Exception) {
            onLine("Could not open the microphone: ${error.message}")
            return
        }
        val thread = Thread({ pump() }, "jiadroid-mic")
        listenThread = thread
        thread.start()
        onEmotion(Emotion.LISTENING)
        onLine("Listening")
    }

    private fun pump() {
        val buffer = ShortArray(BUFFER_SAMPLES)
        while (alive && !Thread.currentThread().isInterrupted) {
            val rec = openRecorder()
            if (rec == null) {
                noteDrop()
                sleepBriefly()
                continue
            }
            val read = rec.read(buffer, 0, buffer.size)
            if (read < 0) {
                Log.i(TAG, "microphone read failed: $read")
                closeRecorder()
                noteDrop()
                continue
            }
            if (reportedDrop) {
                reportedDrop = false
                activity.runOnUiThread {
                    if (alive && !speaking) {
                        onEmotion(Emotion.LISTENING)
                        onLine("Listening")
                    }
                }
            }
            if (speaking || read == 0) continue
            val heard = accept(buffer, read) ?: continue
            activity.runOnUiThread {
                if (!alive || speaking) return@runOnUiThread
                if (heard.partial) {
                    onEmotion(Emotion.LISTENING)
                    onHearing(heard.text)
                } else {
                    answer(heard.text)
                }
            }
        }
        closeRecorder()
    }

    private fun accept(buffer: ShortArray, read: Int): Heard? {
        val recognizer = recognizer ?: return null
        val finished = recognizer.acceptWaveForm(buffer, read)
        val json = if (finished) recognizer.result else recognizer.partialResult
        val text = JSONObject(json).optString(if (finished) "text" else "partial")
        if (text.isBlank()) return null
        return Heard(text, partial = !finished)
    }

    private fun openRecorder(): AudioRecord? {
        synchronized(micLock) {
            recorder?.let { return it }
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(min, BUFFER_SAMPLES * 2),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return null
            }
            recorder = rec
            return rec
        }
    }

    private fun closeRecorder() {
        synchronized(micLock) {
            recorder?.release()
            recorder = null
        }
    }

    private fun noteDrop() {
        if (reportedDrop) return
        reportedDrop = true
        activity.runOnUiThread { if (alive) onLine("Microphone dropped. Listening again.") }
    }

    private fun sleepBriefly() {
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun resume() {
        speaking = false
        speakGeneration += 1
        if (!alive) return
        onEmotion(Emotion.LISTENING)
        onLine("Listening")
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
            speak(SpokenReply("Yes?", Emotion.CURIOUS))
            return
        }
        parseVoiceAction(gate.utterance)?.let { action ->
            onAction(action)
            onTurn("Action", action.description)
        }
        speaking = true
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
        activity.window.decorView.postDelayed({
            if (speaking && generation == speakGeneration) resume()
        }, SPEAK_LIMIT_MS)
    }

    private fun isNoise(heard: String): Boolean {
        val words = heard.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.isEmpty() || words.all { it in NOISE }
    }

    private data class Heard(val text: String, val partial: Boolean)

    private companion object {
        const val TAG = "Jiadroid"
        const val SAMPLE_RATE = 16000f
        const val SAMPLE_RATE_HZ = 16000
        const val BUFFER_SAMPLES = 1600
        const val SPEAK_LIMIT_MS = 8000L
        val NOISE = setOf("huh", "uh", "um", "ah", "hmm", "mm", "hm", "mhm", "oh", "the", "a", "an")
    }
}

package dev.jiadroid.follow

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.Executors

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
) {
    /** When null, replies come from the phone's own short list. */
    @Volatile var brain: Brain? = null
    private val thinker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var loading = false
    private var ears: SpeechService? = null
    private var speaker: TextToSpeech? = null
    private var alive = false
    private var speaking = false
    private var readyToSpeak = false

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
        ears?.stop()
        ears?.shutdown()
        ears = null
        speaker?.stop()
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
        if (ears != null) return
        try {
            ears = SpeechService(Recognizer(model, SAMPLE_RATE), SAMPLE_RATE).also {
                it.startListening(listener)
            }
            onLine("Listening")
        } catch (error: Exception) {
            onLine("Could not open the microphone: ${error.message}")
        }
    }

    private fun resume() {
        speaking = false
        if (!alive) return
        ears?.setPause(false)
        onLine("Listening")
    }

    /** Answers [heard] as if it came from the microphone. Typed lines in Debug use this too. */
    fun answer(heard: String) {
        if (speaking) return
        onTurn("You", heard)
        speaking = true
        ears?.setPause(true)
        val seen = seeing()
        val brain = brain
        if (brain == null) {
            speak(reply(heard, seen))
            return
        }
        onLine("Thinking")
        thinker.execute {
            val spoken = try {
                brain.answer(heard, seen)
            } catch (error: Exception) {
                activity.runOnUiThread { onTurn("Model", "failed: ${error.message}") }
                reply(heard, seen)
            }
            activity.runOnUiThread { speak(spoken) }
        }
    }

    private fun speak(spoken: String) {
        onTurn("Me", spoken)
        onLine(spoken)
        val speaker = speaker
        if (!readyToSpeak || speaker == null) {
            onTurn("Voice", "text to speech is not ready")
            resume()
            return
        }
        speaker.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val partial = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
            if (partial.isNotBlank() && !speaking) onHearing(partial)
        }

        override fun onResult(hypothesis: String?) {
            val heard = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
            if (heard.isNotBlank() && !speaking) answer(heard)
        }

        override fun onFinalResult(hypothesis: String?) {}
        override fun onTimeout() {}

        override fun onError(exception: Exception?) {
            onLine("Listening failed: ${exception?.message}")
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
    }
}

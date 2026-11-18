package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** What the hands-free voice loop is doing right now, for the status line in the top bar. */
enum class HezamVoicePhase {
    Off,
    /** Listening for the wake word. */
    Listening,
    /** Wake word heard on its own; the next utterance is the command. */
    AwaitingCommand,
    /** A command was sent and the assistant is working on it. */
    Working,
    /** Reading the reply aloud. */
    Speaking,
    /** The phone has no speech recognition service, or the mic was refused. */
    Unavailable,
}

/**
 * Hands-free voice control: listens through the phone's own speech recognition service in
 * Filipino, waits for the wake word "Agent47", hands the spoken command to [onCommand], and
 * reads the assistant's reply aloud with the system text-to-speech voice.
 *
 * Only runs while the chat screen that owns it is on screen; it is not a background service.
 * Every method must be called on the main thread - SpeechRecognizer requires it.
 */
class HezamVoiceController(
    private val context: Context,
    private val onCommand: (String) -> Unit,
    /** Offered every command first; returns true when it was handled on the spot (stop,
     *  play, scroll) and must not be sent to the assistant. */
    private val onQuickCommand: (String) -> Boolean = { false },
    /** Speaks with the voice chosen in the app's speech settings (for example a cloned
     *  voice). Returns false when there is none or it failed, to fall back to the phone's
     *  own text-to-speech voice. */
    private val customSpeaker: (suspend (String) -> Boolean)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speakJob: Job? = null

    var phase by mutableStateOf(HezamVoicePhase.Off)
        private set

    /** Last thing the recognizer heard, shown so the user can see why nothing was sent. */
    var lastHeard by mutableStateOf("")
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var enabled = false
    private var awaitingCommand = false

    val isEnabled: Boolean get() = enabled

    fun start() {
        if (enabled) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            phase = HezamVoicePhase.Unavailable
            return
        }
        enabled = true
        awaitingCommand = false
        lastHeard = ""
        ensureTts()
        listen()
    }

    fun stop() {
        enabled = false
        awaitingCommand = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { tts?.stop() }
        speakJob?.cancel()
        speakJob = null
        phase = HezamVoicePhase.Off
    }

    fun release() {
        stop()
        scope.cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    /** The assistant finished replying to a voice command: read [reply] aloud, then listen again. */
    fun onReply(reply: String) {
        if (!enabled) return
        val text = cleanForSpeech(reply)
        if (text.isBlank()) {
            backToListening()
        } else {
            speak(text)
        }
    }

    /** The command is over without a reply to read (stopped, or nothing came back). */
    fun resumeListening() {
        if (enabled && phase == HezamVoicePhase.Working) backToListening()
    }

    /** Says [text] aloud, then goes back to listening. */
    fun announce(text: String) {
        if (enabled) speak(text)
    }

    private fun backToListening() {
        awaitingCommand = false
        phase = HezamVoicePhase.Listening
        scheduleListen(RESTART_DELAY_MS)
    }

    private fun listen() {
        if (!enabled) return
        // While a command is being worked on the mic stays open, but only so "Hezam, stop"
        // can interrupt it - the phase keeps saying Working.
        if (phase != HezamVoicePhase.Working) {
            phase = if (awaitingCommand) HezamVoicePhase.AwaitingCommand else HezamVoicePhase.Listening
        }
        val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { current.startListening(intent) }.onFailure { scheduleListen(RETRY_DELAY_MS) }
    }

    private fun scheduleListen(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun handleResults(candidates: List<String>) {
        if (!enabled) return
        val first = candidates.firstOrNull().orEmpty().trim()
        if (first.isNotEmpty()) lastHeard = first

        if (phase == HezamVoicePhase.Working) {
            val quick = candidates.firstNotNullOfOrNull { extractCommand(it) }
            if (!quick.isNullOrBlank()) onQuickCommand(quick)
            if (enabled && phase != HezamVoicePhase.Speaking) scheduleListen(RESTART_DELAY_MS)
            return
        }

        if (awaitingCommand) {
            if (first.isEmpty()) {
                scheduleListen(RESTART_DELAY_MS)
                return
            }
            // The user may repeat the wake word before the command; drop it if so.
            dispatch(extractCommand(first) ?: first)
            return
        }

        val command = candidates.firstNotNullOfOrNull { extractCommand(it) }
        when {
            command == null -> scheduleListen(RESTART_DELAY_MS)
            command.isBlank() -> {
                awaitingCommand = true
                speak(PROMPT_FOR_COMMAND)
            }
            else -> dispatch(command)
        }
    }

    private fun dispatch(command: String) {
        awaitingCommand = false
        if (command.isBlank()) {
            scheduleListen(RESTART_DELAY_MS)
            return
        }
        if (onQuickCommand(command)) {
            if (enabled && phase != HezamVoicePhase.Speaking) backToListening()
            return
        }
        phase = HezamVoicePhase.Working
        onCommand(command)
        scheduleListen(RESTART_DELAY_MS)
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val filipino = Locale.forLanguageTag(LANGUAGE_TAG)
                    val result = runCatching { engine.setLanguage(filipino) }.getOrNull()
                    if (result == null ||
                        result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        runCatching { engine.setLanguage(Locale.getDefault()) }
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            handler.post { onSpeechFinished() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handler.post { onSpeechFinished() }
                        }
                    })
                    ttsReady = true
                    pendingSpeech?.let { queued ->
                        pendingSpeech = null
                        speak(queued)
                    }
                } else {
                    // No usable voice: stay hands-free, just without spoken replies.
                    ttsReady = false
                    pendingSpeech = null
                    if (phase == HezamVoicePhase.Speaking) onSpeechFinished()
                }
            }
        }
    }

    private fun speak(text: String) {
        if (!enabled) return
        runCatching { recognizer?.cancel() }
        handler.removeCallbacksAndMessages(null)
        phase = HezamVoicePhase.Speaking
        val custom = customSpeaker
        if (custom != null) {
            speakJob?.cancel()
            speakJob = scope.launch {
                val spoken = try {
                    custom(text)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    false
                }
                if (spoken) onSpeechFinished() else speakWithPhoneVoice(text)
            }
            return
        }
        speakWithPhoneVoice(text)
    }

    private fun speakWithPhoneVoice(text: String) {
        if (!enabled) return
        val engine = tts
        if (engine == null) {
            pendingSpeech = text
            ensureTts()
            return
        }
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) onSpeechFinished()
    }

    private fun onSpeechFinished() {
        if (!enabled || phase != HezamVoicePhase.Speaking) return
        scheduleListen(RESTART_DELAY_MS)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            handleResults(candidates)
        }

        override fun onError(error: Int) {
            if (!enabled) return
            // Only keep looping while we are actually waiting on the microphone.
            if (phase != HezamVoicePhase.Listening &&
                phase != HezamVoicePhase.AwaitingCommand &&
                phase != HezamVoicePhase.Working
            ) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    enabled = false
                    phase = HezamVoicePhase.Unavailable
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer?.cancel() }
                    scheduleListen(RETRY_DELAY_MS)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleListen(RESTART_DELAY_MS)
                else -> scheduleListen(RETRY_DELAY_MS)
            }
        }
    }

    companion object {
        const val LANGUAGE_TAG = "fil-PH"
        private const val UTTERANCE_ID = "hezam-voice"
        private const val RESTART_DELAY_MS = 300L
        private const val RETRY_DELAY_MS = 1200L
        private const val MAX_SPOKEN_CHARS = 700
        private const val PROMPT_FOR_COMMAND = "Ano po iyon?"

        // Speech recognition rarely spells the name the same way twice, so accept the
        // spellings it tends to produce for "Hezam".
        // Wake word "Agent47". Recognizers write the number as digits or as words, in
        // English or Filipino, and often mishear "agent" - accept the common forms.
        private val WAKE_WORD = Regex(
            "\\b(?:hey\\s+|hi\\s+|ok\\s+|okay\\s+)?" +
                "(?:agent|ejent|eydyent|aydyent|age\\s?nt|asian|aging|agents)" +
                "[\\s-]*" +
                "(?:47|4\\s?7|forty[\\s-]?seven|fourty[\\s-]?seven|for\\s?tea[\\s-]?seven|" +
                "apatnapu'?t?\\s*pito|apat\\s+na\\s+pu'?t?\\s*pito|kwarenta['\\s-]*y?\\s*siyete|" +
                "kuwarenta['\\s-]*y?\\s*siyete|kwarenta['\\s-]*y?\\s*syete)" +
                "\\b[\\s,.!?:;]*",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Returns what was said after the wake word in [utterance] (blank when the wake word
         * was said on its own), or null when the wake word is not in it at all.
         */
        fun extractCommand(utterance: String): String? {
            val match = WAKE_WORD.find(utterance) ?: return null
            return utterance.substring(match.range.last + 1).trim()
        }

        /** Strips Markdown and trims long replies so the spoken version stays listenable. */
        fun cleanForSpeech(reply: String): String {
            val plain = reply
                .replace(Regex("```[\\s\\S]*?```"), " ")
                .replace(Regex("`([^`]*)`"), "$1")
                .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_#>|~]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (plain.length > MAX_SPOKEN_CHARS) plain.take(MAX_SPOKEN_CHARS) else plain
        }
    }
}

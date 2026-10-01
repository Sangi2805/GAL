package com.sangar.gal.sidekick

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
import android.util.Log
import com.sangar.gal.R
import java.util.Locale

/**
 * Ears and mouth. Wraps SpeechRecognizer for input and TextToSpeech for output
 * behind one main-thread-only API.
 *
 * Both platform classes are fussy: SpeechRecognizer must be created and driven
 * from the main thread, and TextToSpeech is unusable until its init callback
 * lands. Everything here funnels through [main] so callers never have to care.
 */
class VoiceEngine(
    context: Context,
    private val listener: Listener,
    /** Spoken at random when a command is understood. Swap freely. */
    private val acknowledgements: List<String> = DEFAULT_ACKNOWLEDGEMENTS,
    /** Spoken when nothing in the launcher matched. */
    private val confusedLines: List<String> = DEFAULT_CONFUSED_LINES,
) {

    interface Listener {
        /** Mic is hot; the character should look like it is listening. */
        fun onListeningStarted()

        /** Recogniser n-best list, most confident first. Never empty. */
        fun onHeard(candidates: List<String>)

        fun onListenFailed(reason: String)

        fun onSpeakStarted()

        fun onSpeakFinished()
    }

    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var released = false

    /** Utterances queued before TTS finished initialising. */
    private val pending = ArrayDeque<Pair<String, String>>()
    private val doneCallbacks = HashMap<String, () -> Unit>()
    private var utteranceCounter = 0

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val listenTimeout = Runnable {
        if (listening) {
            listening = false
            runCatching { recognizer?.cancel() }
            listener.onListenFailed(appContext.getString(R.string.voice_timeout))
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            // Fires on a binder thread.
            main.post {
                if (released) return@post
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(progressListener)
                    selectLanguage()
                    flushPending()
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status $status")
                    failPending()
                }
            }
        }
    }

    fun randomAcknowledgement(): String = acknowledgements.random()

    fun randomConfusedLine(): String = confusedLines.random()

    // ---- Output -----------------------------------------------------------

    /** Speaks [text], interrupting anything already playing. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (released) {
            onDone?.invoke()
            return
        }
        val id = "gal-${utteranceCounter++}"
        if (onDone != null) doneCallbacks[id] = onDone

        val engine = tts
        if (ttsReady && engine != null) {
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak() returned $result")
                finishUtterance(id)
            }
        } else {
            pending.addLast(text to id)
            // Never strand the caller if no TTS engine ever comes up.
            main.postDelayed({
                if (!ttsReady && !released) {
                    Log.w(TAG, "TTS still not ready; dropping queued speech")
                    failPending()
                }
            }, TTS_INIT_TIMEOUT_MS)
        }
    }

    /**
     * Cuts off whatever is being said or waiting to be said. Every pending done callback still runs, at once, so
     * nothing waiting on the speech is stranded.
     */
    fun stopSpeaking() {
        if (released) return
        pending.clear()
        runCatching { tts?.stop() }
        doneCallbacks.keys.toList().forEach { finishUtterance(it) }
    }

    private fun selectLanguage() {
        val engine = tts ?: return
        val preferred = Locale.getDefault()
        val result = engine.setLanguage(preferred)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale.US)
        }
    }

    private fun flushPending() {
        val engine = tts ?: return
        while (pending.isNotEmpty()) {
            val (text, id) = pending.removeFirst()
            if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                finishUtterance(id)
            }
        }
    }

    /** Releases queued callbacks so a stuck TTS engine cannot wedge the flow. */
    private fun failPending() {
        val ids = pending.map { it.second }
        pending.clear()
        ids.forEach { finishUtterance(it) }
    }

    private fun finishUtterance(id: String?) {
        val callback = if (id != null) doneCallbacks.remove(id) else null
        callback?.invoke()
        listener.onSpeakFinished()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { if (!released) listener.onSpeakStarted() }
        }

        override fun onDone(utteranceId: String?) {
            main.post { if (!released) finishUtterance(utteranceId) }
        }

        // Interrupted by stop() or by a newer QUEUE_FLUSH utterance. Without this its done callback never runs.
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            main.post { if (!released) finishUtterance(utteranceId) }
        }

        // Deprecated upstream, but the platform still declares it abstract.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "TTS error on $utteranceId")
            main.post { if (!released) finishUtterance(utteranceId) }
        }
    }

    // ---- Input ------------------------------------------------------------

    /** Caller must have already confirmed RECORD_AUDIO is granted. */
    fun startListening() {
        if (released || listening) return

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onListenFailed(appContext.getString(R.string.voice_no_recognizer))
            return
        }

        val engine = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(recognitionListener)
                recognizer = it
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createSpeechRecognizer failed", t)
            listener.onListenFailed(appContext.getString(R.string.voice_no_recognizer))
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // GAL is offline. We hold no INTERNET permission, but the recogniser is another app that might,
            // so ask it to stay on the phone. With no offline pack for the language it fails with a
            // language or network error, which describeError() turns into "download the offline pack".
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // App names are short; stop listening quickly after they stop talking.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_MS,
            )
        }

        listening = true
        main.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
        try {
            engine.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            stopListenTimer()
            listening = false
            listener.onListenFailed(appContext.getString(R.string.voice_no_recognizer))
        }
    }

    fun cancelListening() {
        if (!listening) return
        stopListenTimer()
        listening = false
        runCatching { recognizer?.cancel() }
    }

    private fun stopListenTimer() = main.removeCallbacks(listenTimeout)

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!released) listener.onListeningStarted()
        }

        override fun onResults(results: Bundle?) {
            stopListenTimer()
            listening = false
            if (released) return
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (candidates.isEmpty()) {
                listener.onListenFailed(appContext.getString(R.string.voice_heard_nothing))
            } else {
                listener.onHeard(candidates)
            }
        }

        override fun onError(error: Int) {
            stopListenTimer()
            listening = false
            if (released) return
            listener.onListenFailed(describeError(error))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun describeError(error: Int): String {
        val res = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> R.string.voice_err_audio
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_err_permission
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> R.string.voice_err_network
            SpeechRecognizer.ERROR_NO_MATCH -> R.string.voice_heard_nothing
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_heard_nothing
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_err_busy
            // API 31+: no offline model for the language, or it is still downloading.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> R.string.voice_err_language
            else -> R.string.voice_err_generic
        }
        return appContext.getString(res)
    }

    // ---- Teardown ---------------------------------------------------------

    fun release() {
        released = true
        stopListenTimer()
        main.removeCallbacksAndMessages(null)
        doneCallbacks.clear()
        pending.clear()
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
    }

    private companion object {
        const val TAG = "VoiceEngine"
        const val MAX_RESULTS = 5

        /**
         * Must be Int, not Long. These extras are read with Bundle.getInt(); a
         * Long is silently rejected and the recogniser falls back to 0, which
         * logs "expected Integer but value was a java.lang.Long".
         */
        const val SILENCE_MS = 1_200
        const val LISTEN_TIMEOUT_MS = 12_000L
        const val TTS_INIT_TIMEOUT_MS = 3_000L

        val DEFAULT_ACKNOWLEDGEMENTS = listOf(
            "Yes boss",
            "Sure",
            "On it",
            "Right away",
            "You got it",
            "Coming up",
        )

        val DEFAULT_CONFUSED_LINES = listOf(
            "No idea what that is, boss.",
            "Never heard of it.",
            "Can't find that one.",
        )
    }
}

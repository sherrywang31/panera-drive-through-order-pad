package com.driveorderpad.bagels

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Utterance restarts collect a batch while held; each instance accepts its final callback once. */
class OfflineBagelSpeech(private val context: Context, private val onPartial: (String, String) -> Unit, private val onFinal: (String, String, Boolean, String?) -> Unit) {
    val available: Boolean get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionId: String? = null
    private var generation = 0
    private var held = false
    private var phraseFinished = false
    private val segments = mutableListOf<String>()
    private var partial = ""
    private var timeout: Runnable? = null
    fun start(id: String): Boolean {
        if (!available || sessionId != null) return false
        sessionId = id; held = true; phraseFinished = false; segments.clear(); partial = ""
        listen()
        return true
    }
    private fun text() = (segments + partial.takeIf { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString(". ")
    @android.annotation.SuppressLint("MissingPermission") // Entry is gated by the runtime microphone permission in BagelApp.
    private fun listen() {
        val id = sessionId ?: return
        if (!held) { finish(false, null); return }
        recognizer?.destroy()
        if (Build.VERSION.SDK_INT < 31) { finish(true, "On-device recognition requires Android 12 or later."); return }
        val source = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) } catch (e: RuntimeException) {
            finish(true, "Could not open on-device recognition: ${e.message}"); return
        }
        recognizer = source
        val token = ++generation
        var terminal = false
        fun active() = !terminal && token == generation && sessionId == id
        source.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(results: Bundle?) {
                if (!active()) return
                partial = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onPartial(id, text())
            }
            override fun onResults(results: Bundle?) {
                if (!active()) return
                terminal = true; phraseFinished = true; clearTimeout()
                val final = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (final.isNotBlank()) segments += final
                partial = ""
                onPartial(id, text())
                if (held) handler.postDelayed({ if (sessionId == id && held) listen() }, 180) else finish(false, null)
            }
            override fun onError(error: Int) {
                if (!active()) return
                terminal = true; phraseFinished = false
                // Never silently restart around an error; the cashier needs to know words may be missing.
                finish(true, when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed. Heard words are kept."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download the English offline speech model in your phone’s speech settings, then retry."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This phone’s on-device recognizer does not support English."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No clear speech heard. Saved words are kept; try again or type."
                    else -> "Recognition stopped (error $error). Review the saved words for anything missing."
                })
            }
        })
        try {
            source.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (e: RuntimeException) { finish(true, "Could not start microphone: ${e.message}") }
    }
    fun release() {
        if (sessionId == null) return
        held = false
        if (recognizer == null || phraseFinished) { finish(false, null); return }
        try { recognizer?.stopListening() } catch (_: RuntimeException) { finish(true, "Recognition stopped; check the saved words."); return }
        clearTimeout()
        timeout = Runnable { if (sessionId != null) finish(true, "Final speech result timed out; review the saved words.") }.also { handler.postDelayed(it, 5000) }
    }
    fun interrupt() { if (sessionId != null) finish(true, "Capture interrupted. Heard words are saved; check whether the last phrase is complete.") }
    private fun clearTimeout() { timeout?.let(handler::removeCallbacks); timeout = null }
    private fun finish(interrupted: Boolean, message: String?) {
        val id = sessionId ?: return
        val captured = text()
        sessionId = null; held = false; phraseFinished = false; generation++
        clearTimeout(); handler.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        onFinal(id, captured, interrupted, message)
    }
    fun destroy() { interrupt(); recognizer?.destroy(); recognizer = null; handler.removeCallbacksAndMessages(null) }
}

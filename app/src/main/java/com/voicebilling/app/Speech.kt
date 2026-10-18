package com.voicebilling.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listens continuously with Android's own speech recognizer.
 * Prefers the on-device recognizer (never goes online). Every event goes to the page as JSON.
 * All calls must happen on the main thread.
 */
class Speech(private val act: Activity, private val emit: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var onDevice = false
    private var listening = false
    private var lang = "kn-IN"
    private var phrases = ArrayList<String>()
    private var errorsInARow = 0

    private fun create(): SpeechRecognizer? {
        rec?.destroy()
        rec = null
        onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)
        val r = when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(act)
            SpeechRecognizer.isRecognitionAvailable(act) -> SpeechRecognizer.createSpeechRecognizer(act)
            else -> null
        }
        r?.setRecognitionListener(listener)
        rec = r
        return r
    }

    private fun intentFor(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        // Nudge the recognizer towards this shop's item names (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 && phrases.isNotEmpty()) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, phrases)
        }
    }

    fun start(language: String, itemNames: List<String>) {
        lang = language
        phrases = ArrayList(itemNames.filter { it.isNotBlank() }.take(100))
        if (rec == null) create()
        if (rec == null) {
            listening = false
            emit(JSONObject().put("type", "error").put("code", "no-recognizer").put("fatal", true))
            emitState()
            return
        }
        listening = true
        errorsInARow = 0
        listenOnce()
        emitState()
    }

    private fun listenOnce() {
        if (!listening) return
        try {
            rec?.startListening(intentFor(lang))
        } catch (e: Exception) {
            main.postDelayed({ listenOnce() }, 500)
        }
    }

    private fun restartSoon(ms: Long) {
        if (listening) main.postDelayed({ listenOnce() }, ms)
    }

    fun stop() {
        listening = false
        main.removeCallbacksAndMessages(null)
        try { rec?.cancel() } catch (e: Exception) { }
        emitState()
    }

    fun destroy() {
        listening = false
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
    }

    private fun emitState() {
        emit(JSONObject().put("type", "state").put("listening", listening).put("onDevice", onDevice))
    }

    private fun texts(b: Bundle?): JSONArray {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        return JSONArray(list)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            emit(JSONObject().put("type", "ready"))
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = texts(partialResults)
            if (t.length() > 0) emit(JSONObject().put("type", "partial").put("texts", t))
        }

        override fun onResults(results: Bundle?) {
            errorsInARow = 0
            val t = texts(results)
            if (t.length() > 0) emit(JSONObject().put("type", "final").put("texts", t))
            restartSoon(150)
        }

        override fun onError(error: Int) {
            if (!listening) return
            when (error) {
                // Silence or nothing understood: just keep listening.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    try { rec?.cancel() } catch (e: Exception) { }
                    restartSoon(400)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal("mic-denied")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, 11 /* ERROR_SERVER_DISCONNECTED */ -> fatal("needs-offline-pack")
                12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ -> fatal("language-not-supported")
                13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> fatal("needs-offline-pack")
                else -> {
                    errorsInARow++
                    if (errorsInARow >= 5) fatal("error-$error") else restartSoon(500)
                }
            }
        }
    }

    private fun fatal(code: String) {
        emit(JSONObject().put("type", "error").put("code", code).put("fatal", true))
        stop()
    }

    /** Is this language available offline? Answers with "available", "downloading", "downloadable", "online" or "unavailable". */
    fun checkOffline(language: String) {
        if (Build.VERSION.SDK_INT < 33) {
            emitOffline(language, if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)) "unknown" else "unavailable")
            return
        }
        val r = rec ?: create()
        if (r == null) { emitOffline(language, "unavailable"); return }
        try {
            r.checkRecognitionSupport(intentFor(language), act.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val state = when {
                        matches(s.installedOnDeviceLanguages, language) -> "available"
                        matches(s.pendingOnDeviceLanguages, language) -> "downloading"
                        matches(s.supportedOnDeviceLanguages, language) -> "downloadable"
                        matches(s.onlineLanguages, language) -> "online"
                        else -> "unavailable"
                    }
                    emitOffline(language, state)
                }
                override fun onError(error: Int) { emitOffline(language, "unknown") }
            })
        } catch (e: Exception) {
            emitOffline(language, "unknown")
        }
    }

    /** Asks the phone to download the offline speech pack for this language. */
    fun download(language: String) {
        if (Build.VERSION.SDK_INT < 33) { emitOffline(language, "unknown"); return }
        val r = rec ?: create() ?: return
        try {
            r.triggerModelDownload(intentFor(language))
            emitOffline(language, "downloading")
            // Check again after a while so the label updates by itself.
            main.postDelayed({ checkOffline(language) }, 20000)
        } catch (e: Exception) {
            emitOffline(language, "unknown")
        }
    }

    private fun matches(list: List<String>, language: String): Boolean {
        val want = language.lowercase().replace('_', '-')
        val base = want.substringBefore('-')
        return list.any {
            val l = it.lowercase().replace('_', '-')
            l == want || l == base || l.substringBefore('-') == base
        }
    }

    private fun emitOffline(language: String, state: String) {
        emit(JSONObject().put("type", "offline").put("lang", language).put("state", state))
    }
}

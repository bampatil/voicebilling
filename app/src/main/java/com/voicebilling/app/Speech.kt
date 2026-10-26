package com.voicebilling.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listens continuously with the phone's speech recognizer.
 * - A language with an offline pack on this phone uses the on-device recognizer (never goes online).
 * - Any other language (Kannada on most phones today) uses Google's online recognizer.
 *   The app itself has no internet permission; Google's speech service makes the connection.
 * Every event goes to the page as JSON. All calls must happen on the main thread.
 */
class Speech(private val act: Activity, private val emit: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var device: SpeechRecognizer? = null   // on-device, offline
    private var cloud: SpeechRecognizer? = null    // Google online
    private var active: SpeechRecognizer? = null
    private var activeOffline = false
    private var listening = false
    private var lang = "kn-IN"
    private var phrases = ArrayList<String>()
    private var errorsInARow = 0
    private val offline = HashMap<String, String>()   // language → last offline check result

    private fun deviceRec(): SpeechRecognizer? {
        if (device == null && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)) {
            device = SpeechRecognizer.createOnDeviceSpeechRecognizer(act).also { it.setRecognitionListener(listener) }
        }
        return device
    }

    /** Google's recognizer when installed (Samsung phones default to Samsung's own), else the phone's default. */
    private fun cloudRec(): SpeechRecognizer? {
        if (cloud == null && SpeechRecognizer.isRecognitionAvailable(act)) {
            val google = googleService()
            cloud = (if (google != null) SpeechRecognizer.createSpeechRecognizer(act, google)
                     else SpeechRecognizer.createSpeechRecognizer(act)).also { it.setRecognitionListener(listener) }
        }
        return cloud
    }

    private fun googleService(): ComponentName? {
        val services = try {
            act.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
        } catch (e: Exception) { emptyList() }
        val order = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        for (pkg in order) {
            val s = services.firstOrNull { it.serviceInfo?.packageName == pkg }?.serviceInfo ?: continue
            return ComponentName(s.packageName, s.name)
        }
        return null
    }

    private fun intentFor(language: String, preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        // Nudge the recognizer towards this shop's item names (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 && phrases.isNotEmpty()) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, phrases)
        }
    }

    fun start(language: String, itemNames: List<String>) {
        lang = language
        phrases = ArrayList(itemNames.filter { it.isNotBlank() }.take(100))
        try { active?.cancel() } catch (e: Exception) { }
        val wantOffline = offline[language] == "available"
        active = if (wantOffline) deviceRec() ?: cloudRec() else cloudRec() ?: deviceRec()
        activeOffline = active != null && active === device
        if (active == null) {
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
            active?.startListening(intentFor(lang, activeOffline))
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
        try { active?.cancel() } catch (e: Exception) { }
        emitState()
    }

    fun destroy() {
        listening = false
        main.removeCallbacksAndMessages(null)
        try { device?.destroy() } catch (e: Exception) { }
        try { cloud?.destroy() } catch (e: Exception) { }
        device = null; cloud = null; active = null
    }

    private fun emitState() {
        emit(JSONObject().put("type", "state").put("listening", listening).put("offline", activeOffline))
    }

    private fun texts(b: Bundle?): JSONArray {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        return JSONArray(list)
    }

    @Volatile private var peakRms = -100f

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            peakRms = -100f
            emit(JSONObject().put("type", "ready").put("offline", activeOffline))
        }
        override fun onBeginningOfSpeech() {}
        // Google reports loudness on its own scale (about -2 quiet to 10 loud). We keep the loudest moment of each phrase.
        override fun onRmsChanged(rmsdB: Float) { if (rmsdB > peakRms) peakRms = rmsdB }
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
            if (t.length() > 0) emit(JSONObject().put("type", "final").put("texts", t)
                .put("loud", if (peakRms < -50f) -1 else ((peakRms + 2f) / 12f * 100f).toInt().coerceIn(0, 100))
                .put("engine", "google"))
            restartSoon(50)
        }

        override fun onError(error: Int) {
            if (!listening) return
            // Keeps listening until the shopkeeper says "stop" or taps Stop. Only a missing mic permission ends it.
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(50)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    try { active?.cancel() } catch (e: Exception) { }
                    restartSoon(300)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal("mic-denied")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, 11 /* ERROR_SERVER_DISCONNECTED */, 10 /* ERROR_TOO_MANY_REQUESTS */ -> {
                    notice(if (activeOffline) "needs-offline-pack" else "needs-internet")
                    restartSoon(2000)
                }
                12 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> {
                    // The offline recognizer can't do this language: switch to Google online and carry on.
                    if (activeOffline && cloudRec() != null) {
                        offline[lang] = "unavailable"
                        active = cloud; activeOffline = false
                        emitState()
                        restartSoon(100)
                    } else fatal("language-not-supported")
                }
                else -> {
                    errorsInARow++
                    restartSoon(if (errorsInARow > 5) 2000 else 300)
                }
            }
        }
    }

    private fun notice(code: String) {
        emit(JSONObject().put("type", "error").put("code", code).put("fatal", false))
    }

    private fun fatal(code: String) {
        emit(JSONObject().put("type", "error").put("code", code).put("fatal", true))
        stop()
    }

    /** Is this language available offline? Answers "available", "downloading", "downloadable", "online" or "unavailable". */
    fun checkOffline(language: String) {
        if (Build.VERSION.SDK_INT < 33) { emitOffline(language, "unknown"); return }
        val r = deviceRec()
        if (r == null) { emitOffline(language, "unavailable"); return }
        try {
            r.checkRecognitionSupport(intentFor(language, true), act.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val state = when {
                        matches(s.installedOnDeviceLanguages, language) -> "available"
                        matches(s.pendingOnDeviceLanguages, language) -> "downloading"
                        matches(s.supportedOnDeviceLanguages, language) -> "downloadable"
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
        val r = deviceRec() ?: return
        try {
            r.triggerModelDownload(intentFor(language, true))
            emitOffline(language, "downloading")
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
            l == want || l == base || (base != "en" && l.substringBefore('-') == base)
        }
    }

    private fun emitOffline(language: String, state: String) {
        offline[language] = state
        emit(JSONObject().put("type", "offline").put("lang", language).put("state", state))
    }
}

package com.voicebilling.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The screen is the Voice Billing page (assets/index.html) shown in a WebView.
 * The page asks this activity for the microphone, speech recognition and the voice through "AndroidSpeech".
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var speech: Speech
    private lateinit var store: ModelStore
    private lateinit var local: LocalSpeech
    private lateinit var kw: KeywordListener
    private var kwOn = false             // keyword listening (test mode), set by the page
    private var kwItems = "[]"
    private var useLocal = true   // offline Kannada model when downloaded; the page can switch to Google
    private var crashReport: JSONObject? = null
    private var tts: TextToSpeech? = null
    private var pending: Pair<String, List<String>>? = null
    private var lastStart: Pair<String, List<String>>? = null
    private var resumeListening = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        crashReport = CrashGuard.collect(this)
        // If the offline engine closed the app last time, don't use it again until the shopkeeper turns it back on.
        val phase = crashReport?.optString("phase") ?: ""
        if (phase == "model" || phase == "vad" || phase == "decode") useLocal = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Stay on our own page.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onPageFinished(view: WebView, url: String) {
                crashReport?.let { send(it) }
                crashReport = null
            }
        }

        speech = Speech(this) { send(it) }
        store = ModelStore(this) { send(it) }
        local = LocalSpeech(this, store) { send(it) }
        kw = KeywordListener(this, store) { send(it) }
        web.addJavascriptInterface(Bridge(), "AndroidSpeech")
        web.loadUrl("file:///android_asset/index.html")

        // Google's voice has Kannada; Samsung's default voice often doesn't.
        val googleTts = try { packageManager.getPackageInfo("com.google.android.tts", 0); true } catch (e: Exception) { false }
        tts = if (googleTts) TextToSpeech(this, { }, "com.google.android.tts") else TextToSpeech(this) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { send(JSONObject().put("type", "ttsdone")) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { send(JSONObject().put("type", "ttsdone")) }
        })
    }

    private fun send(ev: JSONObject) {
        main.post { web.evaluateJavascript("window.onNativeSpeech && window.onNativeSpeech(${ev})", null) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(lang: String, namesJson: String) {
            val names = try {
                val a = JSONArray(namesJson); List(a.length()) { a.optString(it) }
            } catch (e: Exception) { emptyList() }
            main.post { lastStart = lang to names; startWithPermission(lang, names) }
        }

        @JavascriptInterface
        fun stop() { main.post { lastStart = null; speech.stop(); local.stop(); kw.stop() } }

        // Offline Kannada model
        @JavascriptInterface
        fun modelStatus() { main.post { store.status() } }

        @JavascriptInterface
        fun downloadModel() { main.post { store.download() } }

        @JavascriptInterface
        fun deleteModel() { main.post { local.stop(); kw.stop(); store.delete() } }

        @JavascriptInterface
        fun engineCheck() { EngineCheck.run(this@MainActivity, store) { send(it) } }

        @JavascriptInterface
        fun setKeywords(on: Boolean, itemsJson: String) { main.post { kwOn = on; kwItems = itemsJson } }

        @JavascriptInterface
        fun setEngine(offline: Boolean) { main.post { useLocal = offline } }

        @JavascriptInterface
        fun setMic(gain: Float, sensitivity: Float) {
            local.gain = gain.coerceIn(0.5f, 6f)
            kw.gain = gain.coerceIn(0.5f, 6f)
            kw.sensitivity = sensitivity.coerceIn(0.2f, 0.8f)
            local.sensitivity = sensitivity.coerceIn(0.2f, 0.8f)
        }

        @JavascriptInterface
        fun checkOffline(lang: String) { main.post { speech.checkOffline(lang) } }

        @JavascriptInterface
        fun downloadOffline(lang: String) { main.post { speech.download(lang) } }

        @JavascriptInterface
        fun speak(text: String, lang: String) { main.post { say(text, lang) } }

        @JavascriptInterface
        fun copy(text: String) {
            main.post {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Bill", text))
            }
        }
    }

    private fun startWithPermission(lang: String, names: List<String>) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(lang, names)
        } else {
            pending = lang to names
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val p = pending
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && p != null) {
            begin(p.first, p.second)
        } else {
            send(JSONObject().put("type", "error").put("code", "mic-denied").put("fatal", true))
            send(JSONObject().put("type", "state").put("listening", false))
        }
    }

    /** Kannada with the downloaded model → fully offline; anything else → the phone's recognizer (Google). */
    private fun begin(lang: String, names: List<String>) {
        if (lang == "kn-IN" && useLocal && store.isReady()) {
            if (kwOn) { local.stop(); kw.start(kwItems) } else { kw.stop(); local.start() }
        } else {
            local.stop()
            kw.stop()
            speech.start(lang, names)
        }
    }

    private fun say(text: String, lang: String) {
        val t = tts
        if (t == null) { send(JSONObject().put("type", "ttsdone")); return }
        val r = t.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            send(JSONObject().put("type", "toast").put("text", "No voice for this language on the phone"))
        }
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "total") != TextToSpeech.SUCCESS) {
            send(JSONObject().put("type", "ttsdone"))
        }
    }

    // Leaving the app pauses listening; coming back picks up again.
    override fun onPause() {
        super.onPause()
        resumeListening = lastStart != null && pending == null
        speech.stop()
        local.stop()
        kw.stop()
    }

    override fun onResume() {
        super.onResume()
        val s = lastStart
        if (resumeListening && s != null) {
            resumeListening = false
            startWithPermission(s.first, s.second)
        }
    }

    override fun onDestroy() {
        speech.destroy()
        local.destroy()
        kw.destroy()
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}

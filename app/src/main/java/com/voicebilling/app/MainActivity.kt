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
    private var tts: TextToSpeech? = null
    private var pending: Pair<String, List<String>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Stay on our own page; the app has no internet access anyway.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }

        speech = Speech(this) { send(it) }
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
            main.post { startWithPermission(lang, names) }
        }

        @JavascriptInterface
        fun stop() { main.post { speech.stop() } }

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
            speech.start(lang, names)
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
            speech.start(p.first, p.second)
        } else {
            send(JSONObject().put("type", "error").put("code", "mic-denied").put("fatal", true))
            send(JSONObject().put("type", "state").put("listening", false))
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

    override fun onPause() {
        super.onPause()
        speech.stop()
    }

    override fun onDestroy() {
        speech.destroy()
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}

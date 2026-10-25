package com.voicebilling.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The offline Kannada speech model: AI4Bharat IndicConformer (MIT licence), exported for sherpa-onnx.
 * Downloaded once (~190 MB) into the app's private storage; after that no internet is needed.
 * Downloads resume where they stopped if the connection drops.
 *
 * The file comes from this app's own GitHub release "model-kn-1": the original model, with a format
 * label fixed so the phone's speech engine can open it, and test-run before publishing
 * (see tools/prepare_model.py). A model from an older app version is removed automatically.
 */
class ModelStore(private val ctx: Context, private val emit: (JSONObject) -> Unit) {

    companion object {
        const val MODEL_VERSION = "model-kn-1"
        private const val BASE = "https://github.com/bampatil/voicebilling/releases/download/$MODEL_VERSION"
        val FILES = listOf(
            "tokens.txt" to "$BASE/tokens.txt",
            "model.int8.onnx" to "$BASE/model.int8.onnx"
        )
        private const val MIN_MODEL_BYTES = 30L * 1024 * 1024   // anything smaller is a broken download
    }

    val dir: File get() = File(ctx.filesDir, "models/kn")
    val modelPath: String get() = File(dir, "model.int8.onnx").absolutePath
    val tokensPath: String get() = File(dir, "tokens.txt").absolutePath

    @Volatile private var downloading = false

    private val versionFile: File get() = File(dir, "version.txt")

    fun isReady(): Boolean {
        val m = File(dir, "model.int8.onnx"); val t = File(dir, "tokens.txt")
        return m.exists() && m.length() >= MIN_MODEL_BYTES && t.exists() && t.length() > 0 &&
            versionFile.exists() && versionFile.readText().trim() == MODEL_VERSION
    }

    /** A complete model from an older version (e.g. the one 0.4 couldn't open) is deleted so it gets re-downloaded. */
    private fun dropOutdated() {
        val m = File(dir, "model.int8.onnx")
        val current = versionFile.exists() && versionFile.readText().trim() == MODEL_VERSION
        if (m.exists() && !current) dir.deleteRecursively()
    }

    fun sizeMb(): Long = dir.listFiles()?.sumOf { it.length() }?.div(1024 * 1024) ?: 0

    fun status() {
        if (!downloading) dropOutdated()
        val state = when {
            downloading -> "downloading"
            isReady() -> "ready"
            else -> "missing"
        }
        emit(JSONObject().put("type", "model").put("state", state).put("mb", sizeMb()))
    }

    fun download() {
        if (downloading || isReady()) { status(); return }
        downloading = true
        status()
        thread(name = "model-download") {
            try {
                dropOutdated()
                dir.mkdirs()
                for ((name, url) in FILES) fetch(name, url)
                versionFile.writeText(MODEL_VERSION)
                downloading = false
                if (isReady()) emit(JSONObject().put("type", "model").put("state", "ready").put("mb", sizeMb()))
                else emit(JSONObject().put("type", "model").put("state", "error").put("message", "Download incomplete. Tap to try again."))
            } catch (e: Exception) {
                downloading = false
                emit(JSONObject().put("type", "model").put("state", "error")
                    .put("message", "Download stopped (${e.javaClass.simpleName}). Check internet and tap to continue."))
            }
        }
    }

    private fun fetch(name: String, url: String) {
        val done = File(dir, name)
        if (done.exists() && (name != "model.int8.onnx" || done.length() >= MIN_MODEL_BYTES)) return
        val part = File(dir, "$name.part")
        var have = if (part.exists()) part.length() else 0L
        var conn = open(url, have)
        var code = conn.responseCode
        if (code == 416) { // already complete
            conn.disconnect(); part.renameTo(done); return
        }
        if (code == 200 && have > 0) { have = 0; part.delete() }   // server ignored resume: start over
        if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
        val total = (conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L).let { if (it > 0) it + have else -1L }
        var lastPct = -1
        conn.inputStream.use { input ->
            FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                var got = have
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (total > 0) {
                        val pct = (got * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            emit(JSONObject().put("type", "model").put("state", "downloading")
                                .put("file", name).put("pct", pct).put("mb", got / (1024 * 1024)))
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (!part.renameTo(done)) throw IllegalStateException("Could not save $name")
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        var u = URL(url)
        repeat(6) {   // follow redirects (Hugging Face sends files from a CDN)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "VoiceBilling/${BuildInfo.VERSION}")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                c.disconnect()
                u = URL(u, loc)
            } else return c
        }
        throw IllegalStateException("Too many redirects")
    }

    fun delete() {
        dir.deleteRecursively()
        status()
    }
}

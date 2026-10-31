package com.voicebilling.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.voicebilling.app.kws.Fbank
import com.voicebilling.app.kws.KeywordEngine
import com.voicebilling.app.kws.Keywords
import com.voicebilling.app.kws.Spotter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Keyword listening (test mode). Instead of waiting for a pause and writing down every word, the app looks at
 * the last 3 seconds every half second and searches only for numbers, units and the shop's items.
 *
 *  mic → ring buffer (last 6 s) → every 0.5 s: last 3 s → sound picture (Fbank) → model (ONNX Runtime)
 *      → rough guesses → KeywordEngine (search + pairing) → "order" or "item without number" to the screen.
 *
 * The voice detector (Silero) only decides whether anyone spoke in the last 3 s; if not, the model isn't run
 * (saves battery). Each order carries how loud it was and how long after speaking it was added.
 */
class KeywordListener(private val act: Activity, private val store: ModelStore, private val emit: (JSONObject) -> Unit) {

    private val rate = 16000
    private val win = 3 * rate
    private val hop = rate / 2
    private val ringSize = 6 * rate
    private val ring = FloatArray(ringSize)        // raw sound, before mic boost (for loudness)
    @Volatile private var total = 0L               // samples recorded since start
    @Volatile private var lastSpeech = -1L         // sample index when the voice detector last heard speech
    @Volatile private var running = false
    @Volatile var gain = 1.5f
    @Volatile var sensitivity = 0.5f              // voice detector threshold, from "Pick up softer voices"
    @Volatile private var micRestarts = 0
    @Volatile private var gen = 0                 // each start gets a new number; old threads stop when it changes
    private var recordThread: Thread? = null
    private var workThread: Thread? = null

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var spotter: Spotter? = null
    private var engine: KeywordEngine? = null
    private val fbank = Fbank()

    fun isRunning() = running

    /** itemsJson: [{"id":"3","words":["ಸಕ್ಕರೆ","ಸಕ್ರಿ"]}, …] — Kannada spellings only. */
    fun start(itemsJson: String) {
        if (running) return
        running = true
        val g = ++gen
        micRestarts = 0
        synchronized(ring) { total = 0; lastSpeech = -1 }
        emit(JSONObject().put("type", "state").put("listening", true).put("offline", true).put("engine", "keywords"))
        workThread = Thread({ work(itemsJson, g) }, "kws").also { it.start() }
    }

    private fun alive(g: Int) = running && gen == g

    private fun work(itemsJson: String, g: Int) {
        try {
            if (session == null) {
                emit(JSONObject().put("type", "loading"))
                CrashGuard.mark(act, "kws")
                env = OrtEnvironment.getEnvironment()
                session = env!!.createSession(store.modelPath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
                spotter = Spotter(File(store.tokensPath).readLines())
                engine = KeywordEngine(spotter)
                CrashGuard.clear(act)
            }
            val words = ArrayList<Keywords.Word>(Keywords.numbers())
            words.addAll(Keywords.units())
            val items = JSONArray(itemsJson)
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val ws = o.getJSONArray("words")
                for (j in 0 until ws.length()) words.add(Keywords.item(o.getString("id"), ws.getString(j)))
            }
            val t0 = SystemClock.elapsedRealtime()
            engine!!.setWords(words)
            emit(JSONObject().put("type", "kwready").put("words", engine!!.wordCount()).put("ms", SystemClock.elapsedRealtime() - t0))
        } catch (e: Throwable) {
            CrashGuard.clear(act)
            running = false
            emit(JSONObject().put("type", "error").put("code", "model-failed").put("fatal", true).put("detail", e.javaClass.simpleName + ": " + (e.message ?: "")))
            emit(JSONObject().put("type", "state").put("listening", false))
            return
        }
        if (!alive(g)) return
        act.runOnUiThread { if (alive(g)) startRecording(g) }
        loop(g)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(g: Int) {
        val vad = try {
            Vad(act.assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", threshold = sensitivity,
                    minSilenceDuration = 0.3f, minSpeechDuration = 0.1f, windowSize = 512, maxSpeechDuration = 20f),
                sampleRate = rate, numThreads = 1, provider = "cpu", debug = false))
        } catch (e: Throwable) { null }
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            vad?.release()
            emit(JSONObject().put("type", "error").put("code", "mic-denied").put("fatal", true))
            emit(JSONObject().put("type", "state").put("listening", false))
            return
        }
        recordThread = Thread({
            val chunk = ShortArray(512)
            val f = FloatArray(512)
            var zeroSamples = 0
            var silentReported = false
            var lastLevel = 0L
            try {
                rec.startRecording()
                emit(JSONObject().put("type", "ready").put("offline", true))
                while (alive(g)) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n < 0) {   // the phone took the mic away (error code); try to get it back
                        emit(JSONObject().put("type", "kwmic").put("state", "error").put("code", n))
                        break
                    }
                    if (n == 0) continue
                    // All-zero sound for 2 s = Android is giving this app silence (another app is using the mic).
                    var allZero = true
                    for (i in 0 until n) if (chunk[i].toInt() != 0) { allZero = false; break }
                    if (allZero) {
                        zeroSamples += n
                        if (zeroSamples >= 2 * rate && !silentReported) { silentReported = true; emit(JSONObject().put("type", "kwmic").put("state", "silent")) }
                    } else {
                        if (silentReported) emit(JSONObject().put("type", "kwmic").put("state", "back"))
                        zeroSamples = 0; silentReported = false
                    }
                    var sum = 0.0
                    val gn = gain
                    synchronized(ring) {
                        for (i in 0 until n) {
                            val v = chunk[i] / 32768f
                            ring[((total + i) % ringSize).toInt()] = v
                            f[i] = min(1f, max(-1f, v * gn))     // the voice detector hears the boosted sound, like normal listening
                            sum += (v * v).toDouble()
                        }
                        total += n
                    }
                    val db = 20 * log10(sqrt(sum / n) * gn + 1e-9)
                    // Someone may be speaking: the voice detector says so, or the sound is clearly above room level.
                    if (db > -45) lastSpeech = total
                    if (vad != null) {
                        vad.acceptWaveform(if (n == f.size) f else f.copyOf(n))
                        if (vad.isSpeechDetected()) lastSpeech = total
                        while (!vad.empty()) vad.pop()
                    } else lastSpeech = total
                    val now = System.currentTimeMillis()
                    if (now - lastLevel > 200) {
                        lastLevel = now
                        emit(JSONObject().put("type", "level").put("db", db.toInt()))
                    }
                }
            } catch (e: Throwable) {
                emit(JSONObject().put("type", "error").put("code", "mic-error").put("fatal", false).put("detail", e.message ?: ""))
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
                vad?.release()
                if (alive(g) && micRestarts < 5) {      // still meant to be listening: open the mic again
                    micRestarts++
                    Thread.sleep(500)
                    act.runOnUiThread { if (alive(g)) startRecording(g) }
                }
            }
        }, "kws-mic").also { it.start() }
    }

    private fun loop(g: Int) {
        val eng = engine ?: return
        val sess = session ?: return
        val e = env ?: return
        var next = win.toLong()
        var runs = 0; var modelMs = 0L; var searchMs = 0L; var firstStat = true
        var checks = 0L
        var seenTotal = -1L; var seenAt = SystemClock.elapsedRealtime(); var stallReported = false
        var lastIdle = 0L
        while (alive(g)) {
            if (total < next) {
                Thread.sleep(20)
                // The mic stopped delivering sound altogether (not even silence): say so once.
                if (total != seenTotal) { seenTotal = total; seenAt = SystemClock.elapsedRealtime(); stallReported = false }
                else if (!stallReported && SystemClock.elapsedRealtime() - seenAt > 2000) {
                    stallReported = true
                    emit(JSONObject().put("type", "kwmic").put("state", "stalled"))
                }
                continue
            }
            val end = total
            next = max(next + hop, end - hop / 2)          // if the phone falls behind, skip ahead instead of piling up
            if (lastSpeech < end - win) {                 // nobody spoke in the last 3 s: don't run the model
                if (end - lastIdle >= rate) { lastIdle = end; emit(JSONObject().put("type", "kwidle")) }
                continue
            }
            val start = end - win
            val clip = FloatArray(win)
            synchronized(ring) {
                for (i in 0 until win) clip[i] = min(1f, max(-1f, ring[((start + i) % ringSize).toInt()] * gain))
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                val feats = fbank.compute(clip)
                val frames = Fbank.numFrames(clip.size)
                val lp = OnnxTensor.createTensor(e, FloatBuffer.wrap(feats), longArrayOf(1, Fbank.BANDS.toLong(), frames.toLong())).use { x ->
                    OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { len ->
                        sess.run(mapOf("audio_signal" to x, "length" to len)).use { r ->
                            @Suppress("UNCHECKED_CAST")
                            ((r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)[0]
                        }
                    }
                }
                val t1 = SystemClock.elapsedRealtime()
                val results = eng.process(lp, start / rate.toDouble(), end / rate.toDouble())
                val t2 = SystemClock.elapsedRealtime()
                modelMs += t1 - t0; searchMs += t2 - t1; runs++
                // What the model wrote down for this stretch (the model's own best guess), so the screen shows it is listening.
                checks++
                emit(JSONObject().put("type", "kwheard").put("text", greedy(lp, spotter!!)).put("n", checks))
                val nowSec = total / rate.toDouble()
                for (res in results) {
                    val o = JSONObject().put("type", "kw").put("kind", res.kind).put("item", res.item.word.key)
                        .put("words", res.words()).put("loud", loudness(res.start(), res.end()))
                        .put("delayMs", ((nowSec - res.end()) * 1000).toInt())
                    if (res.kind == "order") {
                        o.put("n", res.qty())
                        res.unit()?.let { o.put("unit", it) }
                        res.doubt()?.let { o.put("doubt", it) }
                    }
                    emit(o)
                }
                if (firstStat || runs % 10 == 0) {
                    firstStat = false
                    emit(JSONObject().put("type", "kwstat").put("modelMs", modelMs / runs).put("searchMs", searchMs / runs))
                    runs = 0; modelMs = 0; searchMs = 0
                }
            } catch (t: Throwable) {
                emit(JSONObject().put("type", "error").put("code", "decode-error").put("fatal", false).put("detail", t.javaClass.simpleName + ": " + (t.message ?: "")))
            }
        }
    }

    private fun greedy(lp: Array<FloatArray>, sp: Spotter): String {
        val sb = StringBuilder()
        var prev = -1
        for (row in lp) {
            var best = 0
            for (i in row.indices) if (row[i] > row[best]) best = i
            if (best != prev && best != sp.blank) sb.append(sp.token(best))
            prev = best
        }
        return sb.toString().replace('▁', ' ').trim()
    }

    /** Loudness 0-100 of the stretch where the order was heard: same measure and scale as the "What it heard" loudness. */
    private fun loudness(fromSec: Double, toSec: Double): Int {
        val a = (fromSec * rate).toLong(); val b = (toSec * rate).toLong()
        if (b - a < 512 || total - a > ringSize) return -1
        val levels = ArrayList<Double>()
        synchronized(ring) {
            var i = a
            while (i + 512 <= b) {
                var sum = 0.0
                for (j in 0 until 512) { val v = ring[((i + j) % ringSize).toInt()]; sum += (v * v).toDouble() }
                levels.add(20 * log10(sqrt(sum / 512) + 1e-9))
                i += 512
            }
        }
        levels.sortDescending()
        val top = levels.take(max(1, levels.size / 3)).average()
        return ((top + 60) / 50 * 100).toInt().coerceIn(0, 100)
    }

    fun stop() {
        if (!running) return
        running = false
        try { recordThread?.join(500) } catch (e: Exception) { }
        recordThread = null
        emit(JSONObject().put("type", "state").put("listening", false).put("engine", "keywords"))
    }

    fun destroy() {
        stop()
        try { workThread?.join(1000) } catch (e: Exception) { }
        session?.close(); session = null
    }
}

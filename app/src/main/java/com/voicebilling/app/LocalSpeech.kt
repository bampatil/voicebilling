package com.voicebilling.app

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fully offline listening: the app records the microphone itself, a small voice detector (Silero VAD)
 * cuts the sound into spoken phrases, and the Kannada model on the phone turns each phrase into text.
 * Because the app owns the audio, it can boost quiet or distant voices (mic boost).
 */
class LocalSpeech(private val act: Activity, private val store: ModelStore, private val emit: (JSONObject) -> Unit) {

    private val rate = 16000
    private val decoder = Executors.newSingleThreadExecutor()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var running = false
    @Volatile var gain = 1.5f
    @Volatile var sensitivity = 0.5f      // voice detector threshold: lower = picks up softer speech
    private var recordThread: Thread? = null

    /** Loads the model (a second or two the first time) and starts listening. */
    fun start() {
        if (running) return
        running = true
        emit(JSONObject().put("type", "state").put("listening", true).put("offline", true).put("engine", "local"))
        decoder.execute {
            try {
                if (recognizer == null) {
                    emit(JSONObject().put("type", "loading"))
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(model = store.modelPath),
                            tokens = store.tokensPath,
                            numThreads = 4,
                            debug = false,
                            provider = "cpu",
                            modelType = "nemo_ctc"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    recognizer = OfflineRecognizer(config = config)
                }
                if (running) act.runOnUiThread { startRecording() }
            } catch (e: Throwable) {
                running = false
                emit(JSONObject().put("type", "error").put("code", "model-failed").put("fatal", true)
                    .put("detail", e.javaClass.simpleName + ": " + (e.message ?: "")))
                emit(JSONObject().put("type", "state").put("listening", false))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vad = try {
            Vad(act.assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = sensitivity,
                    minSilenceDuration = 0.6f,   // a pause this long ends a phrase
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15f
                ),
                sampleRate = rate,
                numThreads = 1,
                provider = "cpu",
                debug = false
            ))
        } catch (e: Throwable) {
            running = false
            emit(JSONObject().put("type", "error").put("code", "vad-failed").put("fatal", true).put("detail", e.message ?: ""))
            emit(JSONObject().put("type", "state").put("listening", false))
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            vad.release()
            emit(JSONObject().put("type", "error").put("code", "mic-denied").put("fatal", true))
            emit(JSONObject().put("type", "state").put("listening", false))
            return
        }
        recordThread = Thread({
            val chunk = ShortArray(512)
            val samples = FloatArray(512)
            var speaking = false
            var lastLevel = 0L
            try {
                rec.startRecording()
                emit(JSONObject().put("type", "ready").put("offline", true))
                while (running) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    val g = gain
                    for (i in 0 until n) {
                        // Mic boost: amplify, then clip so loud voices don't distort badly.
                        val v = min(1f, max(-1f, chunk[i] / 32768f * g))
                        samples[i] = v
                        sum += (v * v).toDouble()
                    }
                    val buf = if (n == samples.size) samples else samples.copyOf(n)
                    vad.acceptWaveform(buf)
                    val now = System.currentTimeMillis()
                    if (now - lastLevel > 200) {
                        lastLevel = now
                        val db = 20 * log10(sqrt(sum / n) + 1e-9)
                        emit(JSONObject().put("type", "level").put("db", db.toInt()))
                    }
                    val s = vad.isSpeechDetected()
                    if (s != speaking) {
                        speaking = s
                        emit(JSONObject().put("type", "hearing").put("on", s))
                    }
                    while (!vad.empty()) {
                        val seg = vad.front().samples
                        vad.pop()
                        decoder.execute { decode(seg) }
                    }
                }
            } catch (e: Throwable) {
                emit(JSONObject().put("type", "error").put("code", "mic-error").put("fatal", false).put("detail", e.message ?: ""))
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
                vad.release()
            }
        }, "mic")
        recordThread?.start()
    }

    private fun decode(seg: FloatArray) {
        val r = recognizer ?: return
        try {
            val stream = r.createStream()
            stream.acceptWaveform(seg, rate)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()
            if (text.isNotEmpty()) emit(JSONObject().put("type", "final").put("texts", JSONArray(listOf(text))))
        } catch (e: Throwable) {
            emit(JSONObject().put("type", "error").put("code", "decode-error").put("fatal", false).put("detail", e.message ?: ""))
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { recordThread?.join(500) } catch (e: Exception) { }
        recordThread = null
        emit(JSONObject().put("type", "state").put("listening", false).put("engine", "local"))
    }

    fun isRunning() = running

    fun destroy() {
        stop()
        decoder.execute { recognizer?.release(); recognizer = null }
        decoder.shutdown()
    }
}

package com.voicebilling.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.concurrent.thread

/**
 * Test for keyword listening, step "do the two engines clash?".
 *
 * Keyword listening needs the model's rough guesses (how likely each sound piece is, every 40 ms), which the
 * speech library (sherpa-onnx) doesn't hand out. So the app also gets ONNX Runtime's own Android library to run
 * the model directly. Both contain a file called libonnxruntime.so (both version 1.17.1); the build keeps one.
 * This check proves, on the phone, that:
 *   1. the model opens with the new engine,
 *   2. it runs on 3 seconds of sound and gives guesses of the expected shape, and how long that takes,
 *   3. the existing engine still works in the same app.
 * The "sound" here is silence: this checks the plumbing and speed, not what the model hears.
 */
object EngineCheck {
    @Volatile private var running = false

    fun run(act: Activity, store: ModelStore, emit: (JSONObject) -> Unit) {
        if (running) return
        running = true
        thread(name = "engine-check") {
            val out = StringBuilder()
            fun line(s: String) {
                out.append(s).append('\n')
                emit(JSONObject().put("type", "enginecheck").put("text", out.toString()).put("done", false))
            }
            try {
                if (!store.isReady()) { line("Download the offline Kannada model first."); return@thread }

                CrashGuard.mark(act, "enginecheck")
                line("1. Opening the model with the new engine (ONNX Runtime)…")
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
                var t = SystemClock.elapsedRealtime()
                env.createSession(store.modelPath, opts).use { session ->
                    line("   opened in ${SystemClock.elapsedRealtime() - t} ms. Inputs: ${session.inputNames.joinToString()}")

                    val frames = 300   // the sound picture has one column per 10 ms: 300 = 3 seconds
                    val best = LongArray(3)
                    var shape = ""
                    for (i in 0 until 3) {
                        OnnxTensor.createTensor(env, FloatBuffer.allocate(80 * frames), longArrayOf(1, 80, frames.toLong())).use { x ->
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { len ->
                                t = SystemClock.elapsedRealtime()
                                session.run(mapOf("audio_signal" to x, "length" to len)).use { r ->
                                    best[i] = SystemClock.elapsedRealtime() - t
                                    shape = (r.get(0) as OnnxTensor).info.shape.joinToString(", ", "[", "]")
                                }
                            }
                        }
                    }
                    line("2. 3 seconds of sound → guesses $shape (expected [1, 75, 5633])")
                    line("   time per run: ${best.joinToString(" / ")} ms (needs to stay well under 500 ms)")
                }

                line("3. Checking the existing engine still works…")
                t = SystemClock.elapsedRealtime()
                val rec = OfflineRecognizer(config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = store.modelPath),
                        tokens = store.tokensPath, numThreads = 4, debug = false, provider = "cpu", modelType = "nemo_ctc"
                    ),
                    decodingMethod = "greedy_search"
                ))
                val s = rec.createStream()
                s.acceptWaveform(FloatArray(16000), 16000)
                rec.decode(s)
                s.release()
                rec.release()
                line("   OK (${SystemClock.elapsedRealtime() - t} ms)")
                CrashGuard.clear(act)
                line("All good: both engines work side by side.")
            } catch (e: Throwable) {
                CrashGuard.clear(act)
                line("FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
                emit(JSONObject().put("type", "enginecheck").put("text", out.toString()).put("done", true))
            }
        }
    }
}

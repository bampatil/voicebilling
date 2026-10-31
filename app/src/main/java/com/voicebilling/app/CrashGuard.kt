package com.voicebilling.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Finds out why the app closed last time.
 * - Kotlin/Java crashes: the error is saved to a file just before the app closes.
 * - Crashes inside the speech engine (C++) can't be caught, so before each risky step we write down
 *   "now doing X". If the app reopens and that note is still there, step X closed the app.
 * - The app's own system log from just before the crash is collected too, so the engine's last message is visible.
 */
object CrashGuard {
    private const val PREFS = "crashguard"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(app.filesDir, "crash.txt").writeText("Thread: ${t.name}\n" + Log.getStackTraceString(e)) } catch (x: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    /** Write down the risky step about to start. commit() so it is on disk even if the next line kills the app. */
    fun mark(ctx: Context, phase: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("phase", phase).putLong("at", System.currentTimeMillis()).commit()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().remove("phase").remove("at").commit()
    }

    /** Called once at start-up: returns what happened last time, or null if the app closed normally. */
    fun collect(ctx: Context): JSONObject? {
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        val phase = prefs.getString("phase", null)
        val at = prefs.getLong("at", 0)
        val f = File(ctx.filesDir, "crash.txt")
        val stack = if (f.exists()) f.readText().take(4000) else null
        if (phase == null && stack == null) return null
        val log = readLog()
        clear(ctx)
        f.delete()
        return JSONObject().put("type", "crash").put("phase", phase ?: "").put("at", at)
            .put("stack", stack ?: "").put("log", log)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            .put("version", BuildInfo.VERSION)
    }

    private fun readLog(): String = try {
        // Android lets an app read its own log lines, including those from just before the last crash.
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "-v", "brief"))
        val lines = p.inputStream.bufferedReader().readLines()
        val keep = Regex("(?i)sherpa|onnx|vad|nemo|recogni|model|fatal|signal|abort|AndroidRuntime|libc|exit|error")
        lines.filter { keep.containsMatchIn(it) }.takeLast(60).joinToString("\n")
    } catch (e: Exception) {
        "System log not available: ${e.message}"
    }
}

object BuildInfo {
    const val VERSION = "0.5.3"
}

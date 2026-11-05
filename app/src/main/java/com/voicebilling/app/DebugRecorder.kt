package com.voicebilling.app

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test recorder, for finding out why an order was missed or wrong.
 *
 * While switched on, each listening run (Speak → Stop) is saved as one "test" in the app's private storage:
 *   recording.wav — exactly the sound the app heard (16 kHz mono, before mic boost), up to 15 minutes;
 *   log.txt       — every line with its time into the recording: what the model heard on each check,
 *                   which numbers/items it found, each order, and every bill change, spoken or typed.
 * Nothing leaves the phone until the shopkeeper taps "Share last test". The last 3 tests are kept.
 */
object DebugRecorder {
    private const val RATE = 16000
    private const val MAX_SAMPLES = 15L * 60 * RATE
    @Volatile var enabled = false

    private var dir: File? = null
    private var wav: FileOutputStream? = null
    private var log: BufferedWriter? = null
    private var samples = 0L
    private var limitLogged = false
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun root(ctx: Context) = File(ctx.filesDir, "tests")

    @Synchronized
    fun begin(ctx: Context, header: String) {
        if (!enabled || log != null) return
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val d = File(root(ctx), name).apply { mkdirs() }
        dir = d
        samples = 0; limitLogged = false
        wav = FileOutputStream(File(d, "recording.wav")).also { it.write(ByteArray(44)) }   // header filled in at the end
        log = File(d, "log.txt").bufferedWriter()
        log!!.write("Voice Billing ${BuildInfo.VERSION} test $name\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
        log!!.write(header.trimEnd() + "\n")
        log!!.write("Times: clock time, then seconds into recording.wav\n\n")
        log!!.flush()
        // keep only the last 3 tests
        root(ctx).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.deleteRecursively() }
    }

    val active: Boolean @Synchronized get() = log != null

    @Synchronized
    fun audio(chunk: ShortArray, n: Int) {
        val out = wav ?: return
        if (samples >= MAX_SAMPLES) {
            if (!limitLogged) { limitLogged = true; write("NOTE", "15-minute recording limit reached; the log continues without sound") }
            return
        }
        val b = ByteArray(n * 2)
        for (i in 0 until n) { val v = chunk[i].toInt(); b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = (v shr 8 and 0xff).toByte() }
        out.write(b)
        samples += n
    }

    /** One line in log.txt. kind: CHECK, HITS, ORDER, LONE, HEARD, BILL, MIC, NOTE … */
    @Synchronized
    fun event(kind: String, text: String) { write(kind, text) }

    private fun write(kind: String, text: String) {
        val l = log ?: return
        l.write(String.format(Locale.US, "%s  +%7.2fs  %-6s %s\n", clock.format(Date()), samples / RATE.toDouble(), kind, text))
        if (kind != "CHECK" && kind != "HITS") l.flush()
    }

    @Synchronized
    fun end() {
        val l = log ?: return
        write("END", "stopped")
        l.close(); log = null
        wav?.close(); wav = null
        dir?.let { d ->
            // fill in the WAV header now that the length is known
            RandomAccessFile(File(d, "recording.wav"), "rw").use { f ->
                val data = samples * 2
                val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                h.put("data".toByteArray()).putInt(data.toInt())
                f.seek(0); f.write(h.array())
            }
        }
    }

    private fun latest(ctx: Context): File? =
        root(ctx).listFiles()?.filter { it.isDirectory && File(it, "log.txt").exists() }?.maxByOrNull { it.name }

    fun logText(ctx: Context): String? {
        synchronized(this) { log?.flush() }
        return latest(ctx)?.let { File(it, "log.txt").readText() }
    }

    /** Zips the latest test (sound + log) into the share folder and returns the file. */
    fun zipLatest(ctx: Context): File? {
        end()
        val d = latest(ctx) ?: return null
        val shareDir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val zip = File(shareDir, "voicebilling-test-${d.name}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (f in d.listFiles() ?: emptyArray()) {
                z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return zip
    }
}

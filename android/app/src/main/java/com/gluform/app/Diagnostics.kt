package com.gluform.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only, intentionally verbose diagnostics for field-test builds.
 *
 * One append-only UTF-8 JSON-lines file per process, persisted in internal storage.
 * Events are queued off the UI/GL threads. No network transport, no file deletion/rotation.
 * Export streams all historical sessions; log size is limited only by device free space.
 */
object Diagnostics {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "glu-diagnostics") }
    private var main: Handler? = null
    private val eventIds = AtomicLong(0)
    @Volatile private var sessionFile: File? = null
    @Volatile private var directory: File? = null
    @Volatile private var initialized = false
    private var writer: BufferedWriter? = null
    private var unflushed = 0

    @Synchronized fun init(context: Context) {
        if (initialized) return
        val dir = File(context.filesDir, "diagnostics")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create diagnostics directory" }
        val file = File(dir, "gluform-" + System.currentTimeMillis() + "-" + Process.myPid() + ".jsonl")
        main = Handler(Looper.getMainLooper())
        directory = dir
        sessionFile = file
        initialized = true
        io.execute {
            try {
                writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8), 65536)
            } catch (t: Throwable) {
                Log.e("GLUformDiagnostics", "Could not open diagnostics writer", t)
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val line = JSONObject()
                    .put("event", "crash.uncaught")
                    .put("wall_ms", System.currentTimeMillis())
                    .put("thread", thread.name)
                    .put("message", Log.getStackTraceString(error))
                    .toString() + "\n"
                FileOutputStream(file, true).use { output ->
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
        event("session.start", "manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL
            + " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE
            + " pid=" + Process.myPid() + " app=" + context.packageName)
    }

    fun event(type: String, message: String) {
        if (!initialized) return
        val line = JSONObject()
            .put("seq", eventIds.incrementAndGet())
            .put("wall_ms", System.currentTimeMillis())
            .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("thread", Thread.currentThread().name)
            .put("event", type)
            .put("message", message)
            .toString()
        io.execute {
            try {
                writer?.apply {
                    write(line)
                    newLine()
                    // Always flush each event in diagnostic editions. The process may be
                    // force-stopped immediately after a lifecycle callback, before a batch
                    // of 32 events has accumulated. Low-volume JSONL append cost is
                    // preferable to losing the crash-adjacent tail of a field test.
                    flush()
                    unflushed = 0
                }
            } catch (t: Throwable) {
                Log.e("GLUformDiagnostics", "Write failed", t)
            }
        }
    }

    fun flush() {
        if (initialized) io.execute { runCatching { writer?.flush(); unflushed = 0 } }
    }

    /** Serializes after prior events; exports existing logs plus the current session. */
    fun export(resolver: ContentResolver, uri: Uri, done: (Boolean, String) -> Unit) {
        event("diagnostics.export.request", "user selected a local destination")
        io.execute {
            val result = runCatching {
                writer?.flush()
                val files = directory?.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("gluform-") && it.name.endsWith(".jsonl") }
                    ?.sortedBy { it.name }.orEmpty()
                var bytes = 0L
                val stream = resolver.openOutputStream(uri, "wt")
                    ?: error("Document provider returned no output stream")
                stream.use { out ->
                    for (file in files) {
                        out.write(("\n=== GLUform session " + file.name + " ===\n").toByteArray(StandardCharsets.UTF_8))
                        file.inputStream().buffered().use { input -> bytes += input.copyTo(out, 65536) }
                    }
                }
                "Exported " + files.size + " session(s), " + bytes + " diagnostic bytes"
            }
            main?.post {
                if (result.isSuccess) done(true, result.getOrThrow())
                else done(false, "Diagnostic export failed: " + (result.exceptionOrNull()?.message ?: "unknown"))
            }
        }
    }

    /** Keep file names out of shared logs; do not print documents or authentication secrets. */
    fun safeParams(params: JSONObject): String {
        fun scrub(key: String, value: Any?): Any {
            val name = key.lowercase()
            if (listOf("token", "secret", "password", "credential", "auth", "api_key").any { it in name })
                return "[REDACTED]"
            if (name == "path" || name == "uri") return "[REDACTED_PATH]"
            return when (value) {
                is JSONObject -> {
                    val clean = JSONObject()
                    val iter = value.keys()
                    while (iter.hasNext()) {
                        val child = iter.next()
                        clean.put(child, scrub(child, value.opt(child)))
                    }
                    clean
                }
                is JSONArray -> {
                    val clean = JSONArray()
                    for (i in 0 until value.length()) clean.put(scrub(key, value.opt(i)))
                    clean
                }
                else -> value ?: JSONObject.NULL
            }
        }
        return scrub("params", params).toString()
    }
}

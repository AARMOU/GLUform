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
import java.io.ByteArrayOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only, intentionally verbose diagnostics for field-test builds.
 *
 * One append-only UTF-8 JSON-lines file per process, persisted in internal storage.
 * Events are processed off the UI/GL threads and kept local. No network transport or deletion.
 * A bounded event backlog and disk-space reserve prevent logging from exhausting resources.
 * Export streams historical sessions; stop logging before storage limits are reached.
 */
object Diagnostics {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "glu-diagnostics") }
    private var main: Handler? = null
    private val eventIds = AtomicLong(0)
    @Volatile private var sessionFile: File? = null
    @Volatile private var directory: File? = null
    @Volatile private var initialized = false
    private var writer: BufferedWriter? = null
    private val pendingEvents = AtomicInteger()
    private val droppedEvents = AtomicLong()
    private const val MAX_PENDING_EVENTS = 2048
    private const val MAX_SESSION_BYTES = 512L * 1024 * 1024
    private const val MIN_FREE_BYTES = 256L * 1024 * 1024
    private var sessionBytes = 0L
    @Volatile private var storagePaused = false

    private val bearerPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9+._~/-]+""")
    private val secretPattern = Regex("""(?i)\b(?:api[_-]?key|access[_-]?token|token|secret|password|authorization|auth|credential)\b\s*[:=]\s*(?:[^\s,;]+)""")
    private val uriPattern = Regex("""(?i)\b(?:content|file|https?)://[^\s"'<>]+""")
    private val windowsPathPattern = Regex("""(?i)\b[A-Z]:\\(?:[^\\\s]+\\)*[^\\\s]+""")
    private val unixPathPattern = Regex("""(?<![A-Za-z0-9_])/(?:[^/\s"'<>]+/)*[^/\s"'<>]+""")

    /** Sanitizes free-text diagnostics, including native error strings and exception messages. */
    internal fun safeMessage(message: String): String =
        message.take(4096)
            .replace(bearerPattern, "Bearer [REDACTED]")
            .replace(secretPattern, "[REDACTED_SECRET]")
            .replace(uriPattern, "[REDACTED_URI]")
            .replace(windowsPathPattern, "[REDACTED_PATH]")
            .replace(unixPathPattern, "[REDACTED_PATH]")

    /** Preserve crash type, code location and line, never raw throwable messages. */
    internal fun crashSummary(error: Throwable): String =
        safeMessage(generateSequence(error) { it.cause }.take(3).joinToString(" caused_by ") { cause ->
            val frames = cause.stackTrace.take(24).joinToString(" <- ") {
                it.className + "." + it.methodName + ":" + it.lineNumber
            }
            cause.javaClass.name + " [" + frames + "]"
        })

    internal fun mayRecord(nextSessionBytes: Long, availableBytes: Long): Boolean =
        nextSessionBytes <= MAX_SESSION_BYTES && availableBytes >= MIN_FREE_BYTES

    /** Emergency crash writes must honor the same storage cap as normal event writes. */
    internal fun mayRecordCrash(
        existingBytes: Long,
        crashBytes: Long,
        availableBytes: Long,
        paused: Boolean
    ): Boolean = !paused && existingBytes >= 0L && crashBytes > 0L &&
        crashBytes <= MAX_SESSION_BYTES && existingBytes <= MAX_SESSION_BYTES - crashBytes &&
        mayRecord(existingBytes + crashBytes, availableBytes)

    /** Runs only on the diagnostics executor. */
    private fun writeLine(line: String) {
        val file = sessionFile ?: return
        val output = writer ?: return
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (!mayRecord(sessionBytes + bytes.size + 1L, file.parentFile?.usableSpace ?: 0L)) {
            if (!storagePaused) Log.w("GLUformDiagnostics", "Logging paused at disk/session safety limit")
            storagePaused = true
            return
        }
        output.write(line)
        output.newLine()
        // Preserve crash-adjacent records for abrupt stops; I/O stays off render/UI threads.
        output.flush()
        sessionBytes += bytes.size + 1L
    }

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
                sessionBytes = file.length()
                writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8), 65536)
            } catch (t: Throwable) {
                storagePaused = true
                Log.e("GLUformDiagnostics", "Could not open diagnostics writer; logging paused")
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val line = JSONObject()
                    .put("event", "crash.uncaught")
                    .put("wall_ms", System.currentTimeMillis())
                    .put("thread", thread.name)
                    .put("message", crashSummary(error))
                    .toString() + "\n"
                val bytes = line.toByteArray(StandardCharsets.UTF_8)
                // Crash records bypass the normal executor but not the storage safety gate.
                // Use the on-disk length; normal event writes are flushed after each event.
                if (mayRecordCrash(
                        file.length(), bytes.size.toLong(),
                        file.parentFile?.usableSpace ?: 0L, storagePaused
                    )
                ) {
                    FileOutputStream(file, true).use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                }
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
        event("session.start", "manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL
            + " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE
            + " pid=" + Process.myPid() + " app=" + context.packageName)
    }

    fun event(type: String, message: String) {
        if (!initialized || storagePaused) return
        // Reserve a slot before queueing. GL frame/touch spam must never grow an
        // unbounded backlog if storage is slow or the app is backgrounding.
        if (pendingEvents.incrementAndGet() > MAX_PENDING_EVENTS) {
            pendingEvents.decrementAndGet()
            droppedEvents.incrementAndGet()
            return
        }
        val seq = eventIds.incrementAndGet()
        val wallMs = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val thread = Thread.currentThread().name
        io.execute {
            try {
                val dropped = droppedEvents.getAndSet(0L)
                if (dropped > 0L) {
                    writeLine(JSONObject().put("event", "diagnostics.dropped")
                        .put("wall_ms", wallMs).put("count", dropped).toString())
                }
                // Redaction and JSON assembly happen off the UI/GL threads.
                writeLine(JSONObject()
                    .put("seq", seq)
                    .put("wall_ms", wallMs)
                    .put("elapsed_ns", elapsedNs)
                    .put("thread", thread)
                    .put("event", type)
                    .put("message", safeMessage(message))
                    .toString())
            } catch (t: Throwable) {
                storagePaused = true
                Log.e("GLUformDiagnostics", "Write failed; logging paused")
            } finally {
                pendingEvents.decrementAndGet()
            }
        }
    }

    fun flush() {
        if (initialized) io.execute { runCatching { writer?.flush() } }
    }

    /**
     * Historical sessions may predate writer-side redaction. Export only sanitized JSON
     * records, never raw bytes. Invalid or oversized records become fixed placeholders.
     * The byte limit also bounds memory when an old session has no newlines.
     */
    internal fun sanitizedHistoricalRecord(raw: String): String =
        try {
            safeParams(JSONObject(raw))
        } catch (_: Exception) {
            JSONObject().put("event", "diagnostics.export.omitted_invalid_record").toString()
        }

    internal fun copySanitizedHistory(input: java.io.InputStream, out: java.io.OutputStream): Long {
        val maxLineBytes = 65536
        val line = ByteArrayOutputStream()
        var oversized = false
        var exported = 0L

        fun emit() {
            val record = if (oversized) {
                JSONObject().put("event", "diagnostics.export.omitted_oversized_record").toString()
            } else {
                sanitizedHistoricalRecord(line.toString(StandardCharsets.UTF_8.name()))
            }
            val bytes = (record + "\\n").toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            exported += bytes.size.toLong()
            line.reset()
            oversized = false
        }

        input.buffered(65536).use { source ->
            while (true) {
                val next = source.read()
                if (next == -1) break
                if (next == 10) {
                    emit()
                } else if (!oversized) {
                    if (line.size() < maxLineBytes) line.write(next)
                    else oversized = true
                }
            }
            if (oversized || line.size() > 0) emit()
        }
        return exported
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
                        file.inputStream().use { input -> bytes += copySanitizedHistory(input, out) }
                    }
                }
                "Exported " + files.size + " session(s), " + bytes + " diagnostic bytes" +
                    if (storagePaused) " (logging paused at storage safety limit)" else ""
            }
            main?.post {
                if (result.isSuccess) done(true, result.getOrThrow())
                else done(false, exportFailureMessage(result.exceptionOrNull()))
            }
        }
    }

    /** Document providers may include user-selected paths or URIs in exception messages. */
    internal fun exportFailureMessage(error: Throwable?): String =
        "Diagnostic export failed: " + safeMessage(error?.message ?: "unknown")

    /** Keep file names out of shared logs; do not print documents or authentication secrets. */
    fun safeParams(params: JSONObject): String {
        fun scrub(key: String, value: Any?): Any {
            val name = key.lowercase()
            if (listOf("token", "secret", "password", "credential", "auth", "api_key").any { it in name })
                return "[REDACTED]"
            if (name == "path" || name == "uri" || name.endsWith("_path") || name.endsWith("_uri")) return "[REDACTED_PATH]"
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
                is String -> safeMessage(value)
                else -> value ?: JSONObject.NULL
            }
        }
        return scrub("params", params).toString()
    }
}

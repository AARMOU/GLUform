package com.gluform.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.gluform.app.ui.App
import com.gluform.app.ui.FileOps
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity(), FileOps {
    private var pending: ((Uri) -> Unit)? = null
    private var saveUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("gluform", MODE_PRIVATE) }

    private val opener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) pending?.invoke(u); pending = null }
    private val creator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) pending?.invoke(u); pending = null }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b == null && !started) {
            started = true
            Cad.start()
            val incoming = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
            if (incoming != null) openUri(incoming, insert = false)
            else restoreAutosave()
        }
        setContent { App(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { openUri(it, insert = false) }
    }

    private var autosaveRecovery: AutosaveRecoverySequence? = null
    private val manualOpenGate = ManualOpenAutosaveGate()

    /** Try only app-managed snapshots, one native open at a time; never delete failed candidates. */
    private fun restoreAutosave() {
        val store = AutosaveSnapshotStore(File(filesDir, "autosave")) { path ->
            prefs.edit().putString("autosave", path).commit()
        }
        val candidates = store.recoveryCandidates(prefs.getString("autosave", null))
        if (candidates.isEmpty()) return
        val recovery = AutosaveRecoverySequence(
            candidates = candidates,
            open = { file, finished ->
                Cad.exec("doc.open", JSONObject().put("path", file.absolutePath), quiet = true) { r ->
                    finished(r != null)
                }
            },
            onRecovered = { file ->
                autosaveRecovery = null
                Cad.fit()
                saveUri = prefs.getString("saveUri", null)?.let(Uri::parse)
                // A successful native reopen, not merely nonzero bytes, selects the backup.
                if (prefs.getString("autosave", null) != file.absolutePath &&
                    !prefs.edit().putString("autosave", file.absolutePath).commit()
                ) {
                    Cad.message = "Previous design recovered, but its recovery pointer was not saved"
                }
            },
            onUnavailable = {
                autosaveRecovery = null
                Cad.message = "Autosave recovery failed; original snapshots were preserved"
            }
        )
        autosaveRecovery = recovery
        recovery.start()
    }

    private val autosaveGeneration = AutosaveGeneration()

    override fun onStop() {
        super.onStop()
        // Never autosave a partly restored/failed document over its recovery history.
        if (autosaveRecovery != null || !manualOpenGate.mayAutosave()) return
        // Do not erase an existing recovery snapshot if the engine has not finished loading.
        // A valid, empty timeline is still a document worth saving.
        if (Cad.doc.optJSONArray("timeline") == null) return
        val store = AutosaveSnapshotStore(File(filesDir, "autosave")) { path ->
            // commit() reports whether the new recovery pointer was actually persisted.
            prefs.edit().putString("autosave", path).commit()
        }
        val candidate = runCatching { store.stage(prefs.getString("autosave", null)) }.getOrElse {
            Cad.message = "Autosave unavailable: ${it.message ?: "cannot prepare file"}"
            return
        }
        val request = autosaveGeneration.next()
        Cad.exec("file.save_as", JSONObject().put("path", candidate.target.absolutePath).put("overwrite", true), quiet = true) { result ->
            if (!autosaveGeneration.isLatest(request)) {
                candidate.target.delete() // superseded by a more recent snapshot
                return@exec
            }
            val saved = runCatching { store.finish(candidate, result != null) }.getOrDefault(false)
            if (!saved) Cad.message = "Autosave failed; your previous recovery snapshot was preserved"
        }
    }

    private fun name(u: Uri): String {
        var n: String? = null
        runCatching { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) n = c.getString(0) } }
        return (n ?: u.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
    }

    private fun openUri(u: Uri, insert: Boolean) {
        // A user-selected document takes precedence over pending background recovery callbacks.
        val openRequest = if (!insert) manualOpenGate.begin() else null
        if (!insert) {
            // A failed manual open must not autosave an unknown native document state.
            autosaveGeneration.next() // invalidate any pending background save completion
            autosaveRecovery?.cancel()
            autosaveRecovery = null
        }
        val n = name(u); val f = IncomingDocumentFile.allocate(File(cacheDir, "in"), n)
        val ext = n.substringAfterLast('.', "").lowercase()
        Cad.onEngine({
            contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return@onEngine "Could not read $n"
            null
        }) { err ->
            if (err != null) { Cad.message = err; return@onEngine }
            // A newer user Open can supersede this request while its bytes are being copied.
            // Do not enqueue a stale native doc.open after that newer request.
            if (!insert && !manualOpenGate.isPending(openRequest!!)) return@onEngine
            val cmd = if (!insert) "doc.open" else if (ext in setOf("step", "stp", "igs", "iges", "fcstd", "3dm")) "file.insert_step" else "file.insert_mesh"
            Cad.form = null; Cad.selection.clear()
            Cad.exec(cmd, JSONObject().put("path", f.absolutePath)) { r ->
                if (r != null) {
                    if (!insert) {
                        if (!manualOpenGate.succeeded(openRequest!!)) return@exec
                    }
                    Cad.fit()
                    if (!insert) {
                        Cad.markSaved()
                        saveUri = if (ext == "sc" || ext == "solidcraft") u.also { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } } else null
                        prefs.edit().putString("saveUri", saveUri?.toString()).apply()
                    }
                }
            }
        }
    }

    private fun writeOut(cmd: String, params: JSONObject, file: File, to: Uri, done: String, saved: Boolean) {
        file.parentFile?.mkdirs()
        Cad.exec(cmd, params.put("path", file.absolutePath)) { r ->
            if (r == null) return@exec
            Cad.onEngine({
                contentResolver.openOutputStream(to, "wt")?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return@onEngine "Could not write to that location"
                null
            }) { err ->
                Cad.message = err ?: "$done (${(file.length() + 1023) / 1024} KB)"
                if (err == null && saved) Cad.markSaved()
            }
        }
    }

    private fun base() = Cad.doc.optString("name", "design").ifBlank { "design" }.replace(Regex("[^A-Za-z0-9._ -]"), "_")

    override fun open() { pending = { openUri(it, insert = false) }; opener.launch(arrayOf("*/*")) }
    override fun insert() { pending = { openUri(it, insert = true) }; opener.launch(arrayOf("*/*")) }

    override fun save() {
        val u = saveUri
        if (u == null) saveAs() else writeOut("file.save_as", JSONObject().put("overwrite", true), File(filesDir, "work/${name(u).removeSuffix(".sc")}.sc"), u, "Saved", true)
    }

    override fun saveAs() {
        pending = { u ->
            runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            saveUri = u; prefs.edit().putString("saveUri", u.toString()).apply()
            writeOut("file.save_as", JSONObject().put("overwrite", true), File(filesDir, "work/${name(u).removeSuffix(".sc")}.sc"), u, "Saved", true)
        }
        creator.launch(base() + ".sc")
    }

    override fun export(format: String, quality: String) {
        if (Cad.scene.bodies.isEmpty()) { Cad.message = "There is nothing to export yet: the design has no bodies."; return }
        pending = { u ->
            val f = File(cacheDir, "out/${base()}.$format")
            if (format == "stl") writeOut("file.export_stl", JSONObject().put("quality", quality), f, u, "Exported STL", false)
            else writeOut("file.export", JSONObject().put("format", format), f, u, "Exported ${format.uppercase()}", false)
        }
        creator.launch(base() + "." + format)
    }

    companion object { private var started = false }
}

package com.gluform.app

import java.io.File
import java.util.UUID

/**
 * Save to a distinct candidate. Never discard the last known-good document before the engine
 * has produced a non-empty file and the new path has been durably recorded.
 * A native success callback does not prove that a non-empty file can be reopened; keep prior
 * recovery snapshots until a separate, safe validation/retention policy exists.
 *
 * This class deliberately does not interact with Android lifecycle or Native: it can be tested
 * with ordinary JVM tests and used by the native save completion callback.
 */
internal class AutosaveSnapshotStore(
    private val directory: File,
    private val setCommittedPath: (String) -> Boolean
) {
    data class Candidate(val target: File, val previous: File?)

    fun stage(previousPath: String?): Candidate {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create autosave directory" }
        return Candidate(
            target = File(directory, "autosave-" + UUID.randomUUID() + ".sc"),
            previous = previousPath?.let(::File)
        )
    }

    /**
     * Cold-start recovery order: preferred snapshot first, then newest managed snapshots.
     * Never inspect arbitrary paths from preferences or follow symlinks outside our directory.
     * File size is only a cheap candidate filter; the engine must still reopen each candidate.
     */
    fun recoveryCandidates(preferredPath: String?): List<File> {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return emptyList()
        val snapshots = directory.listFiles().orEmpty().filter { file ->
            file.name.startsWith("autosave-") && file.name.endsWith(".sc") &&
                file.isFile && file.length() > 0L &&
                runCatching { file.canonicalFile.parentFile == root }.getOrDefault(false)
        }.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        val preferred = preferredPath?.let(::File)?.takeIf { file ->
            file.isFile && file.length() > 0L &&
                runCatching { file.canonicalFile.parentFile == root }.getOrDefault(false)
        }
        return (listOfNotNull(preferred) + snapshots).distinctBy { it.canonicalPath }
    }

    /** An unsuccessful native save or preference commit cannot destroy the previous document. */
    fun finish(candidate: Candidate, nativeSaveSucceeded: Boolean): Boolean {
        if (!nativeSaveSucceeded || !candidate.target.isFile || candidate.target.length() <= 0L) {
            candidate.target.delete()
            return false
        }
        // Preference storage may throw; keep the previous committed pointer intact.
        val committed = runCatching { setCommittedPath(candidate.target.absolutePath) }
            .getOrDefault(false)
        if (!committed) {
            candidate.target.delete()
            return false
        }
        // Do not prune the previous snapshot here. A successful native callback and nonzero
        // length do not prove the replacement can be parsed or recovered after process death.
        // Retention cleanup needs a separately verified document reader and a bounded policy.
        return true
    }
}

/** Ignore stale save callbacks if backgrounding scheduled a newer snapshot. */
internal class AutosaveGeneration {
    private var serial = 0L
    fun next(): Long = ++serial
    fun isLatest(value: Long): Boolean = value == serial
}

/** Callback-driven cold-start recovery; attempts are sequential and stale callbacks are ignored.
 * Native doc.open success is the only acceptance signal, not file existence or length.
 */
internal class AutosaveRecoverySequence(
    private val candidates: List<File>,
    private val open: (File, (Boolean) -> Unit) -> Unit,
    private val onRecovered: (File) -> Unit,
    private val onUnavailable: () -> Unit
) {
    private var index = 0
    private var active = true
    private var started = false

    fun start() {
        check(!started) { "Recovery can be started only once" }
        started = true
        attemptNext()
    }

    fun cancel() { active = false }

    private fun attemptNext() {
        if (!active) return
        val file = candidates.getOrNull(index++) ?: run {
            active = false
            onUnavailable()
            return
        }
        var completed = false
        val finish: (Boolean) -> Unit = { success ->
            // Ignore duplicated or late completions after cancellation/user document open.
            if (active && !completed) {
                completed = true
                if (success) {
                    active = false
                    onRecovered(file)
                } else attemptNext()
            }
        }
        // A synchronous failure to submit a native open must not strand recovery.
        // The completion guard also handles an implementation that calls back then throws.
        try {
            open(file, finish)
        } catch (_: Exception) {
            finish(false)
        }
    }
}

/** A failed user document open must never turn unknown native state into a new autosave. */
internal class ManualOpenAutosaveGate {
    private var safe = true
    private var generation = 0L
    private var awaitingSuccess = false

    fun begin(): Long {
        safe = false
        awaitingSuccess = true
        return ++generation
    }

    /** Accept each matching native open completion once, never a duplicate or stale callback. */
    fun succeeded(request: Long): Boolean {
        if (request != generation || !awaitingSuccess) return false
        awaitingSuccess = false
        safe = true
        return true
    }

    /** A superseded request must not enqueue another native doc.open after file preparation. */
    fun isPending(request: Long): Boolean = request == generation && awaitingSuccess

    fun mayAutosave(): Boolean = safe
}

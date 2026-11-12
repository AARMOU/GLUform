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

    /** An unsuccessful native save or preference commit cannot destroy the previous document. */
    fun finish(candidate: Candidate, nativeSaveSucceeded: Boolean): Boolean {
        if (!nativeSaveSucceeded || !candidate.target.isFile || candidate.target.length() <= 0L) {
            candidate.target.delete()
            return false
        }
        if (!setCommittedPath(candidate.target.absolutePath)) {
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

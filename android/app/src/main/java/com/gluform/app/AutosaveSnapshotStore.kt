package com.gluform.app

import java.io.File
import java.util.UUID

/**
 * Save to a distinct candidate. Never discard the last known-good document before the engine
 * has produced a non-empty file and the new path has been durably recorded.
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
        // Deletion is deliberately AFTER the new snapshot is verified and committed.
        val old = candidate.previous
        if (old != null && old.absolutePath != candidate.target.absolutePath &&
            old.parentFile?.canonicalFile == directory.canonicalFile
        ) old.delete()
        return true
    }
}

/** Ignore stale save callbacks if backgrounding scheduled a newer snapshot. */
internal class AutosaveGeneration {
    private var serial = 0L
    fun next(): Long = ++serial
    fun isLatest(value: Long): Boolean = value == serial
}

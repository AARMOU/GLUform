package com.gluform.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A preference backend may persist a pointer but still report commit failure. */
class AutosavePointerFalseCommitTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun falseCommitAfterPointerWriteRetainsBothRecoveryCandidates() {
        val dir = tmp.newFolder("ambiguous-false-commit")
        val prior = File(dir, "autosave-prior.sc").apply { writeText("last known good") }
        var persistedPointer = prior.absolutePath
        val store = AutosaveSnapshotStore(dir) { next ->
            persistedPointer = next // simulate durable write preceding a false return
            false
        }
        val candidate = store.stage(prior.absolutePath)
        candidate.target.writeText("new candidate")

        assertFalse(store.finish(candidate, nativeSaveSucceeded = true))
        assertEquals(candidate.target.absolutePath, persistedPointer)
        assertTrue("Persisted pointer must not dangle", candidate.target.isFile)
        assertEquals("new candidate", candidate.target.readText())
        assertEquals("last known good", prior.readText())
        val recovery = store.recoveryCandidates(persistedPointer)
        assertEquals(candidate.target.canonicalFile, recovery.first().canonicalFile)
        assertTrue(recovery.any { it.canonicalFile == prior.canonicalFile })
    }
}

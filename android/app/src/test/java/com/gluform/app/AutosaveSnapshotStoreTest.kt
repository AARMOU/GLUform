package com.gluform.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutosaveSnapshotStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun previousSnapshotSurvivesWhileNewSaveIsInFlight() {
        val dir = tmp.newFolder("snapshots")
        val previous = File(dir, "old.sc").apply { writeText("previous valid design") }
        var path: String? = previous.absolutePath
        val store = AutosaveSnapshotStore(dir) { path = it; true }

        val candidate = store.stage(previous.absolutePath)
        assertTrue(previous.exists())
        assertEquals(previous.absolutePath, path)
        assertFalse(candidate.target.exists())
        assertNotEquals(previous.absolutePath, candidate.target.absolutePath)
    }

    @Test fun nativeSaveFailureDoesNotDiscardLastWorkingDocument() {
        val dir = tmp.newFolder("native-failure")
        val previous = File(dir, "old.sc").apply { writeText("known-good") }
        var path: String? = previous.absolutePath
        val store = AutosaveSnapshotStore(dir) { path = it; true }
        val candidate = store.stage(previous.absolutePath)
        candidate.target.writeText("partial failed write")

        assertFalse(store.finish(candidate, nativeSaveSucceeded = false))
        assertTrue(previous.exists())
        assertEquals("known-good", previous.readText())
        assertEquals(previous.absolutePath, path)
        assertFalse(candidate.target.exists())
    }

    @Test fun emptyNativeResultCannotReplaceValidSnapshot() {
        val dir = tmp.newFolder("zero-bytes")
        val old = File(dir, "old.sc").apply { writeText("recovery") }
        var path: String? = old.absolutePath
        val store = AutosaveSnapshotStore(dir) { path = it; true }
        val candidate = store.stage(old.absolutePath)
        candidate.target.createNewFile()

        assertFalse(store.finish(candidate, nativeSaveSucceeded = true))
        assertEquals(old.absolutePath, path)
        assertTrue(old.exists())
    }

    @Test fun successfulSaveCommitsPointerBeforePruningPriorFile() {
        val dir = tmp.newFolder("success")
        val prior = File(dir, "old.sc").apply { writeText("prior") }
        var path: String? = prior.absolutePath
        var oldWasPresentWhenPreferenceChanged = false
        val store = AutosaveSnapshotStore(dir) {
            oldWasPresentWhenPreferenceChanged = prior.exists()
            path = it
            true
        }
        val candidate = store.stage(prior.absolutePath)
        candidate.target.writeText("next snapshot")

        assertTrue(store.finish(candidate, nativeSaveSucceeded = true))
        assertTrue(oldWasPresentWhenPreferenceChanged)
        assertEquals(candidate.target.absolutePath, path)
        assertTrue(candidate.target.exists())
        assertFalse(prior.exists())
    }

    @Test fun failedPreferenceCommitKeepsKnownGoodAndDeletesCandidate() {
        val dir = tmp.newFolder("commit-failed")
        val prior = File(dir, "old.sc").apply { writeText("known-good") }
        val store = AutosaveSnapshotStore(dir) { false }
        val candidate = store.stage(prior.absolutePath)
        candidate.target.writeText("new-but-not-committed")

        assertFalse(store.finish(candidate, nativeSaveSucceeded = true))
        assertTrue(prior.exists())
        assertEquals("known-good", prior.readText())
        assertFalse(candidate.target.exists())
    }

    @Test fun onlySnapshotsInsideAutosaveDirectoryAreEverPruned() {
        val dir = tmp.newFolder("owned")
        val external = tmp.newFile("external.sc").apply { writeText("must not delete") }
        val store = AutosaveSnapshotStore(dir) { true }
        val candidate = store.stage(external.absolutePath)
        candidate.target.writeText("saved")

        assertTrue(store.finish(candidate, nativeSaveSucceeded = true))
        assertTrue(external.exists())
    }

    @Test fun rapidBackgroundSavesHaveUniqueCandidatesAndLatestToken() {
        val dir = tmp.newFolder("multiple")
        val store = AutosaveSnapshotStore(dir) { true }
        val tracker = AutosaveGeneration()
        val first = store.stage(null)
        val id1 = tracker.next()
        val second = store.stage(null)
        val id2 = tracker.next()

        assertNotEquals(first.target.absolutePath, second.target.absolutePath)
        assertFalse(tracker.isLatest(id1))
        assertTrue(tracker.isLatest(id2))
    }
}

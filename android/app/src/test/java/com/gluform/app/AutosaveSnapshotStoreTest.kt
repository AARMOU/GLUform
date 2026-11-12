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

    @Test fun successfulSaveCommitsPointerAndRetainsPriorRecoveryFile() {
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
        assertTrue(prior.exists())
        assertEquals("prior", prior.readText())
    }

    @Test fun nativeSuccessWithNonemptyCorruptOutputDoesNotDestroyPriorRecovery() {
        val dir = tmp.newFolder("corrupt-output")
        val prior = File(dir, "old.sc").apply { writeText("known-good") }
        var committedPath: String? = prior.absolutePath
        val store = AutosaveSnapshotStore(dir) { committedPath = it; true }
        val candidate = store.stage(prior.absolutePath)
        // Simulate a native callback that reports success despite a nonempty truncated file.
        candidate.target.writeText("not a recoverable CAD document")

        assertTrue(store.finish(candidate, nativeSaveSucceeded = true))
        assertEquals(candidate.target.absolutePath, committedPath)
        assertTrue(prior.exists())
        assertEquals("known-good", prior.readText())
        // New path may not be recoverable; prior file must remain available for a future
        // explicit recovery/fallback implementation.
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
    @Test fun recoveryPrefersCommittedCandidateThenFallsBackToOlderSnapshot() {
        val dir = tmp.newFolder("recovery-order")
        val previous = File(dir, "autosave-previous.sc").apply { writeText("good"); setLastModified(1000L) }
        val latest = File(dir, "autosave-latest.sc").apply { writeText("corrupt"); setLastModified(2000L) }
        val store = AutosaveSnapshotStore(dir) { true }
        assertEquals(listOf(latest, previous), store.recoveryCandidates(latest.absolutePath))
        // A failed native doc.open would advance to the next candidate; ranking does not
        // mistake nonempty content for a validated CAD document.
        assertEquals(previous, store.recoveryCandidates(latest.absolutePath)[1])
        assertTrue(previous.exists())
    }

    @Test fun recoveryNeverReadsExternalOrEmptySnapshots() {
        val dir = tmp.newFolder("recovery-contained")
        val external = tmp.newFile("private.sc").apply { writeText("outside") }
        File(dir, "autosave-empty.sc").createNewFile()
        val valid = File(dir, "autosave-good.sc").apply { writeText("recoverable") }
        File(dir, "unrelated.sc").writeText("not managed")
        val store = AutosaveSnapshotStore(dir) { true }
        assertEquals(listOf(valid), store.recoveryCandidates(external.absolutePath))
        assertTrue(external.exists())
    }

    @Test fun repeatedRecoveryEnumerationDoesNotPruneFinalSnapshot() {
        val dir = tmp.newFolder("recovery-retained")
        val only = File(dir, "autosave-only.sc").apply { writeText("last good") }
        val store = AutosaveSnapshotStore(dir) { true }
        repeat(20) { assertEquals(listOf(only), store.recoveryCandidates(only.absolutePath)) }
        assertEquals("last good", only.readText())
    }


    @Test fun failedNativeOpenFallsBackOneSnapshotAtATime() {
        val dir = tmp.newFolder("async-recovery")
        val old = File(dir, "autosave-old.sc").apply { writeText("valid"); setLastModified(1000) }
        val newest = File(dir, "autosave-latest.sc").apply { writeText("corrupt"); setLastModified(2000) }
        val attempts = mutableListOf<File>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        var recovered: File? = null
        var exhausted = false
        val store = AutosaveSnapshotStore(dir) { true }
        val recovery = AutosaveRecoverySequence(
            store.recoveryCandidates(newest.absolutePath),
            open = { f, finish -> attempts.add(f); completions.add(finish) },
            onRecovered = { recovered = it },
            onUnavailable = { exhausted = true }
        )
        recovery.start()
        assertEquals(listOf(newest), attempts)
        completions[0](false)
        assertEquals(listOf(newest, old), attempts)
        assertNull(recovered)
        completions[1](true)
        assertEquals(old, recovered)
        assertFalse(exhausted)
        assertTrue(newest.exists())
        assertTrue(old.exists())
    }

    @Test fun cancelledRecoveryIgnoresLateNativeFailureAndSuccess() {
        val files = listOf(File("new.sc"), File("old.sc"))
        val attempts = mutableListOf<File>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        var recovered = false
        var unavailable = false
        val recovery = AutosaveRecoverySequence(
            files,
            open = { f, done -> attempts.add(f); completions.add(done) },
            onRecovered = { recovered = true },
            onUnavailable = { unavailable = true }
        )
        recovery.start()
        recovery.cancel() // User explicitly opens a different document.
        completions.single()(false)
        completions.single()(true)
        assertEquals(listOf(files.first()), attempts)
        assertFalse(recovered)
        assertFalse(unavailable)
    }

    @Test fun duplicatedCallbacksDoNotReopenOrOverwriteAnotherDocument() {
        val files = listOf(File("broken.sc"), File("good.sc"))
        val attempts = mutableListOf<File>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        var successes = 0
        var failures = 0
        val recovery = AutosaveRecoverySequence(
            files,
            open = { f, done -> attempts.add(f); completions.add(done) },
            onRecovered = { successes++ },
            onUnavailable = { failures++ }
        )
        recovery.start()
        completions[0](false)
        completions[0](false)
        assertEquals(files, attempts)
        completions[1](true)
        completions[1](false)
        assertEquals(1, successes)
        assertEquals(0, failures)
        assertEquals(files, attempts)
    }

    @Test fun manualOpenCancelsRecoveryAfterFirstFallbackFailure() {
        val latest = File("autosave-corrupt.sc")
        val backup = File("autosave-good.sc")
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val attempts = mutableListOf<File>()
        var recovered: File? = null
        var exhausted = false
        val sequence = AutosaveRecoverySequence(
            listOf(latest, backup),
            open = { file, done -> attempts.add(file); callbacks.add(done) },
            onRecovered = { recovered = it },
            onUnavailable = { exhausted = true }
        )
        sequence.start()
        callbacks[0](false) // A fallback open is now in flight.
        assertEquals(listOf(latest, backup), attempts)
        sequence.cancel() // Explicit user Open must win over a late native callback.
        callbacks[1](true)
        callbacks[1](false)
        assertNull(recovered)
        assertFalse(exhausted)
        assertEquals(2, attempts.size)
    }

    @Test fun exhaustedRecoveryPreservesSnapshotsAndReportsOneFailure() {
        val dir = tmp.newFolder("all-corrupt")
        val a = File(dir, "autosave-a.sc").apply { writeText("invalid") }
        val b = File(dir, "autosave-b.sc").apply { writeText("invalid") }
        val attempted = mutableListOf<File>()
        var failureCount = 0
        val plan = AutosaveRecoverySequence(
            listOf(a, b),
            open = { f, done -> attempted.add(f); done(false) },
            onRecovered = { fail("Native never succeeded") },
            onUnavailable = { failureCount++ }
        )
        plan.start()
        assertEquals(listOf(a, b), attempted)
        assertEquals(1, failureCount)
        assertTrue(a.exists() && b.exists())
    }

}

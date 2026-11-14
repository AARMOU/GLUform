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

    @Test fun repeatedBackgroundTransitionsKeepFinalRecoverableSnapshot() {
        val dir = tmp.newFolder("repeated-background")
        val original = File(dir, "autosave-original.sc").apply { writeText("last verified design") }
        var committedPath = original.absolutePath
        val store = AutosaveSnapshotStore(dir) { committedPath = it; true }
        val generations = AutosaveGeneration()

        val first = store.stage(committedPath)
        val firstToken = generations.next()
        first.target.writeText("partial first save")
        val second = store.stage(committedPath)
        val secondToken = generations.next()
        second.target.writeText("second save failed")

        // An obsolete completion must not replace the committed recovery pointer.
        assertFalse(generations.isLatest(firstToken))
        first.target.delete()
        assertTrue(generations.isLatest(secondToken))
        assertFalse(store.finish(second, nativeSaveSucceeded = false))
        assertEquals(original.absolutePath, committedPath)
        assertEquals("last verified design", original.readText())

        val third = store.stage(committedPath)
        generations.next()
        third.target.writeText("new candidate; not independently reopen-verified")
        assertTrue(store.finish(third, nativeSaveSucceeded = true))
        assertEquals(third.target.absolutePath, committedPath)
        assertTrue(original.exists())
        assertEquals(listOf(third.target, original), store.recoveryCandidates(committedPath))
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

    @Test fun nativeRecoveryAlreadyQueuedRunsBeforeManualFilePreparationAndLateCallbackIsIgnored() {
        // Mirrors Cad.exec / Cad.onEngine: both enqueue native work on one FIFO engine executor,
        // while completion callbacks are posted separately to the Android main queue.
        val engine = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val nativeOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
            val mainCallbacks = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
            val recovery = AutosaveRecoverySequence(
                listOf(File("autosave-old.sc")),
                open = { _, done ->
                    engine.execute {
                        nativeOrder.add("native recovery open")
                        mainCallbacks.put { done(true) }
                    }
                },
                onRecovered = { fail("Cancelled recovery must not claim the document") },
                onUnavailable = { fail("Cancelled recovery must not report exhaustion") }
            )
            recovery.start()
            recovery.cancel() // Manual Open selected while native recovery is queued.
            val manual = engine.submit {
                nativeOrder.add("manual file preparation")
                mainCallbacks.put { nativeOrder.add("manual open callback") }
            }
            manual.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf("native recovery open", "manual file preparation"), nativeOrder.toList())
            // Deliver recovery completion late, after the manual file operation has run.
            mainCallbacks.take().invoke()
            mainCallbacks.take().invoke()
            assertEquals(
                listOf("native recovery open", "manual file preparation", "manual open callback"),
                nativeOrder.toList()
            )
        } finally {
            engine.shutdownNow()
        }
    }

    @Test fun failedManualOpenNeverEnablesAutosaveOfUnknownNativeState() {
        val gate = ManualOpenAutosaveGate()
        assertTrue(gate.mayAutosave())
        gate.begin()
        assertFalse(gate.mayAutosave())
        // File read error and native doc.open failure must leave this gate closed.
        assertFalse(gate.mayAutosave())
        val latest = gate.begin() // A second attempt cannot accidentally reopen the gate.
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(latest))
        assertTrue(gate.mayAutosave())
        gate.begin()
        assertFalse(gate.mayAutosave())
    }


    @Test fun failedManualOpenAcrossBackgroundPreservesCommittedRecoveryPointer() {
        val dir = tmp.newFolder("failed-manual-open-background")
        val original = File(dir, "autosave-original.sc").apply { writeText("known good design") }
        var preferred = original.absolutePath
        var commits = 0
        val store = AutosaveSnapshotStore(dir) { path ->
            preferred = path
            commits++
            true
        }
        val gate = ManualOpenAutosaveGate()
        val generations = AutosaveGeneration()

        // A background save was queued before the user selected a different document.
        val queued = store.stage(preferred)
        val staleToken = generations.next()
        queued.target.writeText("candidate saved before manual open")

        gate.begin()
        generations.next() // MainActivity.openUri invalidates queued save completions.
        // Native doc.open fails; do not call gate.succeeded().
        assertFalse(gate.mayAutosave())
        assertFalse(generations.isLatest(staleToken))
        queued.target.delete() // stale completion cleanup, never a pointer commit

        // onStop must not enqueue another save while native document identity is unknown.
        if (gate.mayAutosave()) {
            val candidate = store.stage(preferred)
            candidate.target.writeText("unsafe snapshot")
            store.finish(candidate, nativeSaveSucceeded = true)
        }
        assertEquals(0, commits)
        assertEquals(original.absolutePath, preferred)
        assertEquals("known good design", original.readText())
        assertEquals(listOf(original), store.recoveryCandidates(preferred))
    }

    @Test fun supersededManualOpenSuccessCannotUnlockAutosave() {
        val gate = ManualOpenAutosaveGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.succeeded(first))
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(second))
        assertTrue(gate.mayAutosave())
        val third = gate.begin()
        assertFalse(gate.succeeded(second))
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(third))
    }

    @Test fun lateManualOpenCallbackAfterFailedNewerRequestKeepsRecoverySafe() {
        val gate = ManualOpenAutosaveGate()
        val first = gate.begin()
        gate.begin() // Newer doc.open fails: no success callback is issued.
        assertFalse(gate.succeeded(first))
        assertFalse(gate.mayAutosave())
    }

    @Test fun repeatedNativeSuccessCallbackIsAcceptedExactlyOnce() {
        val gate = ManualOpenAutosaveGate()
        val request = gate.begin()
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(request))
        assertTrue(gate.mayAutosave())
        // A duplicate callback must not mark the same document saved twice.
        assertFalse(gate.succeeded(request))
        // Opening another document closes the gate until that request succeeds.
        val newer = gate.begin()
        assertFalse(gate.succeeded(request))
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(newer))
        assertFalse(gate.succeeded(newer))
        assertTrue(gate.mayAutosave())
    }

    @Test fun supersededFilePreparationCannotQueueNativeOpen() {
        val gate = ManualOpenAutosaveGate()
        val first = gate.begin()
        assertTrue(gate.isPending(first))
        val second = gate.begin()
        // Simulate the first document finishing its async file copy after a second Open.
        val nativeOpens = mutableListOf<Long>()
        if (gate.isPending(first)) nativeOpens.add(first)
        assertTrue(nativeOpens.isEmpty())
        assertTrue(gate.isPending(second))
        if (gate.isPending(second)) nativeOpens.add(second)
        assertEquals(listOf(second), nativeOpens)
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(second))
        assertFalse(gate.isPending(second)) // Duplicate completion cannot requeue.
    }

    @Test fun queuedSupersededNativeSuccessAfterNewerFailedOpenCannotEnableAutosave() {
        // Model the FIFO native queue separately from the posted main-thread callbacks.
        // The older native open may succeed even after the newer request has been selected.
        val engine = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val gate = ManualOpenAutosaveGate()
            val callbacks = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
            val nativeOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
            val first = gate.begin()
            val firstNative = engine.submit {
                nativeOrder.add("first native open succeeds")
                callbacks.put { assertFalse(gate.succeeded(first)) }
            }
            val second = gate.begin()
            val secondNative = engine.submit {
                nativeOrder.add("second native open fails")
                callbacks.put { /* A failed native open does not call gate.succeeded(). */ }
            }
            firstNative.get(5, java.util.concurrent.TimeUnit.SECONDS)
            secondNative.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf("first native open succeeds", "second native open fails"), nativeOrder.toList())
            // The older success is delivered after both native commands have finished.
            callbacks.take().invoke()
            callbacks.take().invoke()
            assertFalse(gate.mayAutosave())
            // Only a subsequent, genuinely successful open can restore save eligibility.
            val third = gate.begin()
            assertTrue(gate.succeeded(third))
            assertTrue(gate.mayAutosave())
        } finally {
            engine.shutdownNow()
        }
    }

    @Test fun supersededStagingFailureCannotReplaceNewerOpenStatus() {
        val gate = ManualOpenAutosaveGate()
        val older = gate.begin()
        val newer = gate.begin()
        var message = "Opening newer document"
        val nativeOpens = mutableListOf<Long>()
        // Mirrors MainActivity's completion ordering: generation check precedes error UI.
        fun completeStaging(request: Long, error: String?) {
            if (!gate.isPending(request)) return
            if (error != null) {
                message = error
                return
            }
            nativeOpens.add(request)
        }
        completeStaging(older, "Could not read older document")
        assertEquals("Opening newer document", message)
        assertTrue(nativeOpens.isEmpty())
        completeStaging(newer, null)
        assertEquals(listOf(newer), nativeOpens)
        assertFalse(gate.mayAutosave())
    }


    @Test fun nativeOpenSubmissionExceptionFallsBackWithoutDiscardingSnapshots() {
        val dir = tmp.newFolder("submission-failure")
        val first = File(dir, "autosave-first.sc").apply { writeText("first") }
        val backup = File(dir, "autosave-backup.sc").apply { writeText("backup") }
        val attempts = mutableListOf<File>()
        var recovered: File? = null
        var unavailable = 0
        AutosaveRecoverySequence(
            listOf(first, backup),
            open = { file, done ->
                attempts.add(file)
                if (file == first) throw IllegalStateException("native submission unavailable")
                done(true)
            },
            onRecovered = { recovered = it },
            onUnavailable = { unavailable++ }
        ).start()
        assertEquals(listOf(first, backup), attempts)
        assertEquals(backup, recovered)
        assertEquals(0, unavailable)
        assertTrue(first.exists() && backup.exists())
    }

    @Test fun callbackThenSubmissionExceptionCannotAdvanceRecoveryTwice() {
        val first = File("autosave-first.sc")
        val backup = File("autosave-backup.sc")
        val attempts = mutableListOf<File>()
        var unavailable = 0
        AutosaveRecoverySequence(
            listOf(first, backup),
            open = { file, done ->
                attempts.add(file)
                if (file == first) {
                    done(false)
                    throw IllegalStateException("callback already delivered")
                }
                done(true)
            },
            onRecovered = { assertEquals(backup, it) },
            onUnavailable = { unavailable++ }
        ).start()
        assertEquals(listOf(first, backup), attempts)
        assertEquals(0, unavailable)
    }
    @Test fun throwingPreferenceCommitPreservesLastValidSnapshot() {
        val dir = tmp.newFolder("commit-throws")
        val prior = File(dir, "autosave-prior.sc").apply { writeText("known-good") }
        var committedPath = prior.absolutePath
        val store = AutosaveSnapshotStore(dir) {
            throw IllegalStateException("preferences storage unavailable")
        }
        val candidate = store.stage(committedPath)
        candidate.target.writeText("uncommitted candidate")

        assertFalse(store.finish(candidate, nativeSaveSucceeded = true))
        assertEquals(prior.absolutePath, committedPath)
        assertEquals("known-good", prior.readText())
        assertFalse(candidate.target.exists())
        assertEquals(listOf(prior), store.recoveryCandidates(committedPath))
    }
    @Test fun preferredRecoveryPointerCannotOpenUnmanagedFileInsideSnapshotDirectory() {
        val dir = tmp.newFolder("unmanaged-preference")
        val unrelated = File(dir, "imported-design.sc").apply { writeText("unrelated user file") }
        val managed = File(dir, "autosave-known.sc").apply { writeText("preserved snapshot") }
        val store = AutosaveSnapshotStore(dir) { true }

        assertEquals(listOf(managed), store.recoveryCandidates(unrelated.absolutePath))
        assertTrue(unrelated.exists())
        assertEquals("unrelated user file", unrelated.readText())
    }

    @Test fun exhaustedRecoveryInhibitsAutosaveUntilNewValidManualOpen() {
        val gate = ManualOpenAutosaveGate()
        val attempts = mutableListOf<String>()
        AutosaveRecoverySequence(
            candidates = listOf(tmp.newFile("failed-recovery.sc")),
            open = { file, done -> attempts.add(file.name); done(false) },
            onRecovered = { fail("failed recovery cannot report success") },
            onUnavailable = { gate.begin() }
        ).start()
        assertEquals(listOf("failed-recovery.sc"), attempts)
        assertFalse(gate.mayAutosave())
        val request = gate.begin()
        assertFalse(gate.succeeded(request - 1))
        assertFalse(gate.mayAutosave())
        assertTrue(gate.succeeded(request))
        assertTrue(gate.mayAutosave())
    }

}

package com.gluform.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression for onStop's native-save completion versus manual document-open admission. */
class AutosavePublicationGateTest {
    @Test fun manualOpenCannotSlipBetweenAutosaveEligibilityAndPointerCommit() {
        val root = Files.createTempDirectory("autosave-publication").toFile()
        val gate = ManualOpenAutosaveGate()
        val generation = AutosaveGeneration()
        val token = generation.next()
        val openGeneration = gate.currentGeneration()
        val enteredCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val openAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var pointer: String? = null
        val store = AutosaveSnapshotStore(root) { path ->
            enteredCommit.countDown()
            check(releaseCommit.await(5, TimeUnit.SECONDS))
            pointer = path
            true
        }
        try {
            val candidate = store.stage(null)
            candidate.target.writeText("valid native output")
            val saving = executor.submit<Boolean> {
                var eligible = false
                var saved = false
                gate.publishIfCurrent(openGeneration) {
                    eligible = gate.mayAutosave() && generation.isLatest(token)
                    if (eligible) saved = store.finish(candidate, true)
                }
                if (!eligible) candidate.target.delete()
                saved
            }
            assertTrue(enteredCommit.await(5, TimeUnit.SECONDS))
            val opening = executor.submit<Long> {
                openAttempted.countDown()
                gate.begin()
            }
            assertTrue(openAttempted.await(5, TimeUnit.SECONDS))
            // Once pointer publication has been admitted, manual Open must wait for commit.
            releaseCommit.countDown()
            assertTrue(saving.get(5, TimeUnit.SECONDS))
            val newerOpen = opening.get(5, TimeUnit.SECONDS)
            assertEquals(candidate.target.absolutePath, pointer)
            assertTrue("Committed candidate must survive a subsequent Open", candidate.target.isFile)
            assertTrue(gate.isPending(newerOpen))
            assertFalse(gate.mayAutosave())
        } finally {
            releaseCommit.countDown()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun supersededAutosaveCannotPublishPointerAfterManualOpenBegins() {
        val root = Files.createTempDirectory("autosave-superseded").toFile()
        val gate = ManualOpenAutosaveGate()
        val generation = AutosaveGeneration()
        val token = generation.next()
        val openGeneration = gate.currentGeneration()
        var pointer: String? = null
        val store = AutosaveSnapshotStore(root) { path -> pointer = path; true }
        try {
            val candidate = store.stage(null)
            candidate.target.writeText("stale output")
            gate.begin()
            generation.next()
            var eligible = false
            val published = gate.publishIfCurrent(openGeneration) {
                eligible = gate.mayAutosave() && generation.isLatest(token)
                if (eligible) store.finish(candidate, true)
            }
            if (!eligible) candidate.target.delete()
            assertFalse(published)
            assertFalse(eligible)
            assertNull(pointer)
            assertFalse(candidate.target.exists())
            assertFalse(gate.mayAutosave())
        } finally {
            root.deleteRecursively()
        }
    }
}

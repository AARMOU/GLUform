package com.gluform.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingDocumentFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun overlappingSameNameOpensNeverShareStagingFile() {
        val dir = tmp.newFolder("incoming")
        val first = IncomingDocumentFile.allocate(dir, "design.sc")
        first.writeText("first document")
        val second = IncomingDocumentFile.allocate(dir, "design.sc")
        second.writeText("second document")
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertEquals("first document", first.readText())
        assertEquals("second document", second.readText())
    }

    @Test fun pathsStayInsideStagingDirectory() {
        val dir = tmp.newFolder("incoming-safe")
        val file = IncomingDocumentFile.allocate(dir, "../../design.sc")
        assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
    }
}

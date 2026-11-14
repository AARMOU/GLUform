package com.gluform.app

import java.io.File
import java.util.UUID

/** Keep each incoming document's bytes isolated until its native command consumes them. */
internal object IncomingDocumentFile {
    fun allocate(directory: File, originalName: String): File {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create import cache" }
        val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
        require(safeName.isNotBlank() && safeName != "." && safeName != "..")
        return File(directory, UUID.randomUUID().toString() + "-" + safeName)
    }
}

package com.gluform.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun parameterRedactionKeepsSketchDimensionsButNotSecrets() {
        val nested = JSONObject().put("length", 20).put("api_token", "shhh")
        val input = JSONObject().put("points", nested).put("path", "/private/design.sc")
            .put("password", "private")
            .put("sketch", 4)
        val log = Diagnostics.safeParams(input)
        assertTrue(log.contains("\"length\":20"))
        assertTrue(log.contains("\"sketch\":4"))
        assertFalse(log.contains("shhh"))
        assertFalse(log.contains("private"))
        assertFalse(log.contains("design.sc"))
        assertTrue(log.contains("[REDACTED]"))
    }

    @Test fun freeTextRedactsAbsolutePathsUrisAndCredentials() {
        val text = "read /storage/emulated/0/secret-design.sc " +
            "content://com.example.provider/doc/3?token=abc123 " +
            "Authorization: Bearer sensitive123 api_key=key456 " +
            "C:\\Users\\Maker\\plans\\private.sc geometry=4"
        val safe = Diagnostics.safeMessage(text)
        for (secret in listOf("secret-design.sc", "abc123", "sensitive123",
            "key456", "Maker", "private.sc")) {
            assertFalse("Leaked $secret: $safe", safe.contains(secret))
        }
        assertTrue(safe.contains("geometry=4"))
        assertTrue(safe.contains("[REDACTED_URI]"))
        assertTrue(safe.contains("[REDACTED_PATH]"))
        assertTrue(safe.contains("[REDACTED_SECRET]"))
    }

    @Test fun crashSummaryKeepsStackFrameButNeverExceptionText() {
        val error = IllegalStateException("password=do-not-share /private/sketch.sc")
        error.stackTrace = arrayOf(StackTraceElement("com.gluform.app.Cad", "execute", "Cad.kt", 123))
        val safe = Diagnostics.crashSummary(error)
        assertTrue(safe.contains("IllegalStateException"))
        assertTrue(safe.contains("com.gluform.app.Cad.execute:123"))
        assertFalse(safe.contains("do-not-share"))
        assertFalse(safe.contains("sketch.sc"))
    }

    @Test fun loggingBudgetProtectsSessionAndDeviceReserve() {
        val megabyte = 1024L * 1024L
        assertTrue(Diagnostics.mayRecord(512L * megabyte, 256L * megabyte))
        assertFalse(Diagnostics.mayRecord(512L * megabyte + 1L, 256L * megabyte))
        assertFalse(Diagnostics.mayRecord(1L, 256L * megabyte - 1L))
    }

    @Test fun parameterRedactionProtectsNestedStringPaths() {
        val input = JSONObject().put("nested", JSONObject()
            .put("file_path", "/home/maker/draft.sc")
            .put("detail", "open failed at /private/secret.sc")
            .put("diameter", 12.5))
        val safe = Diagnostics.safeParams(input)
        assertFalse(safe.contains("draft.sc"))
        assertFalse(safe.contains("secret.sc"))
        assertTrue(safe.contains("12.5"))
    }

    @Test fun coordinatesForGeometryRemainVisibleForDebugging() {
        val input = JSONObject().put("point", JSONObject().put("x", 1.25).put("y", -2.5))
        val log = Diagnostics.safeParams(input)
        assertTrue(log.contains("1.25"))
        assertTrue(log.contains("-2.5"))
    }
    @Test fun uncaughtCrashWritesObeySessionDiskAndPauseLimits() {
        val mib = 1024L * 1024L
        val full = 512L * mib
        val reserve = 256L * mib
        assertTrue(Diagnostics.mayRecordCrash(full - 12L, 12L, reserve, paused = false))
        assertFalse(Diagnostics.mayRecordCrash(full - 12L, 13L, reserve, paused = false))
        assertFalse(Diagnostics.mayRecordCrash(1L, 12L, reserve - 1L, paused = false))
        assertFalse(Diagnostics.mayRecordCrash(1L, 12L, reserve, paused = true))
        assertFalse(Diagnostics.mayRecordCrash(Long.MAX_VALUE, 12L, reserve, paused = false))
        assertFalse(Diagnostics.mayRecordCrash(0L, 0L, reserve, paused = false))
    }

}

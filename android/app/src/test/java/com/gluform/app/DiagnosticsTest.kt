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

    @Test fun coordinatesForGeometryRemainVisibleForDebugging() {
        val input = JSONObject().put("point", JSONObject().put("x", 1.25).put("y", -2.5))
        val log = Diagnostics.safeParams(input)
        assertTrue(log.contains("1.25"))
        assertTrue(log.contains("-2.5"))
    }
}

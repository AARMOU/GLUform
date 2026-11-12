package com.gluform.app

import com.gluform.app.gl.Camera
import org.junit.Assert.*
import org.junit.Test

/** Model-independent regressions for the touch-first command and view controls. */
class TouchUiTest {
    @Test fun everyModelingToolIsReachableFromItsRibbonGroup() {
        assertTrue(Tools.groups.containsAll(listOf("Create", "Modify", "Construct")))
        assertTrue(Tools.all.isNotEmpty())
        for (tool in Tools.all) {
            assertTrue("Missing toolbar group for ${tool.id}", tool.group in Tools.groups)
            assertTrue("Missing label for ${tool.id}", tool.label.isNotBlank())
        }
        assertEquals(Tools.all.size, Tools.all.map { it.id }.distinct().size)
    }

    @Test fun viewPadTargetsAreBackedByCameraViews() {
        val supported = Camera.VIEWS.map { it.first }.toSet()
        assertTrue(supported.containsAll(listOf("Top", "Front", "Right", "Home")))
    }
}

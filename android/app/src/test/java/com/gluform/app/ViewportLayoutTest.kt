package com.gluform.app

import com.gluform.app.ui.ViewportLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportLayoutTest {
    @Test fun rotatingFoldDoesNotDockBothPanelsAndCrushViewport() {
        for (width in listOf(360f, 600f, 720f, 800f, 1000f, 1109f)) {
            assertTrue("Expected overlay panels at ${width}dp", ViewportLayout.useCompactLayout(width))
        }
    }

    @Test fun wideScreensKeepAtLeastMinimumCanvasWidth() {
        val threshold = ViewportLayout.MIN_DOCKED_LAYOUT_WIDTH_DP.toFloat()
        assertFalse(ViewportLayout.useCompactLayout(threshold))
        assertFalse(ViewportLayout.useCompactLayout(1280f))
        assertTrue(
            threshold - ViewportLayout.LEFT_PANEL_WIDTH_DP - ViewportLayout.RIGHT_PANEL_WIDTH_DP >=
                ViewportLayout.MIN_VIEWPORT_WIDTH_DP
        )
    }
}

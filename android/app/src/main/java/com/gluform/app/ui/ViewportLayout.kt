package com.gluform.app.ui

/** Keep CAD tools usable on foldables: dock panels only when the canvas has room. */
internal object ViewportLayout {
    const val LEFT_PANEL_WIDTH_DP = 250
    const val RIGHT_PANEL_WIDTH_DP = 300
    const val MIN_VIEWPORT_WIDTH_DP = 560

    const val MIN_DOCKED_LAYOUT_WIDTH_DP =
        LEFT_PANEL_WIDTH_DP + RIGHT_PANEL_WIDTH_DP + MIN_VIEWPORT_WIDTH_DP

    /** Below this threshold panels overlay the canvas instead of squeezing it. */
    fun useCompactLayout(availableWidthDp: Float): Boolean =
        availableWidthDp < MIN_DOCKED_LAYOUT_WIDTH_DP
}

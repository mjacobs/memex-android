package com.memex.android.ui.adaptive

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowSizeClassesTest {

    @Test
    fun `phone widths are compact`() {
        assertEquals(WindowWidthSizeClass.Compact, widthSizeClassFor(360.dp))
        assertEquals(WindowWidthSizeClass.Compact, widthSizeClassFor(599.dp))
    }

    @Test
    fun `small tablet and unfolded phone widths are medium`() {
        assertEquals(WindowWidthSizeClass.Medium, widthSizeClassFor(600.dp))
        assertEquals(WindowWidthSizeClass.Medium, widthSizeClassFor(839.dp))
    }

    @Test
    fun `tablet widths are expanded`() {
        assertEquals(WindowWidthSizeClass.Expanded, widthSizeClassFor(840.dp))
        assertEquals(WindowWidthSizeClass.Expanded, widthSizeClassFor(1280.dp))
    }

    @Test
    fun `only compact keeps the bottom bar`() {
        assertFalse(WindowWidthSizeClass.Compact.usesNavigationRail)
        assertTrue(WindowWidthSizeClass.Medium.usesNavigationRail)
        assertTrue(WindowWidthSizeClass.Expanded.usesNavigationRail)
    }

    @Test
    fun `only expanded gets two panes`() {
        assertFalse(WindowWidthSizeClass.Compact.supportsTwoPane)
        assertFalse(WindowWidthSizeClass.Medium.supportsTwoPane)
        assertTrue(WindowWidthSizeClass.Expanded.supportsTwoPane)
    }

    @Test
    fun `pane split lands on a fold near the middle`() {
        val fraction = listPaneFractionForFold(
            containerWidth = 1000,
            foldLeft = 480,
            fallback = 0.4f
        )
        assertEquals(0.48f, fraction, 0.001f)
    }

    @Test
    fun `pane split falls back without a fold`() {
        assertEquals(
            0.4f,
            listPaneFractionForFold(containerWidth = 1000, foldLeft = null, fallback = 0.4f)
        )
    }

    @Test
    fun `pane split falls back when the fold is too close to an edge`() {
        assertEquals(
            0.4f,
            listPaneFractionForFold(containerWidth = 1000, foldLeft = 50, fallback = 0.4f)
        )
        assertEquals(
            0.4f,
            listPaneFractionForFold(containerWidth = 1000, foldLeft = 950, fallback = 0.4f)
        )
    }

    @Test
    fun `pane split falls back for a zero or negative container width`() {
        assertEquals(
            0.4f,
            listPaneFractionForFold(containerWidth = 0, foldLeft = 100, fallback = 0.4f)
        )
    }
}

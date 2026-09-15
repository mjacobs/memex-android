package com.memex.android.ui.adaptive

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `pane split lands on the fold in row-local coordinates`() {
        // A 1000px row that starts 200px into the window, with a 20px hinge at 680.
        val split = foldAlignedPaneSplit(
            rowLeft = 200,
            rowWidth = 1000,
            foldLeft = 680,
            foldRight = 700
        )
        assertEquals(480, split.listWidthPx)
        assertEquals(20, split.hingeWidthPx)
    }

    @Test
    fun `pane split reserves the full hinge width`() {
        val split = foldAlignedPaneSplit(
            rowLeft = 0,
            rowWidth = 1000,
            foldLeft = 480,
            foldRight = 520
        )
        assertEquals(480, split.listWidthPx)
        assertEquals(40, split.hingeWidthPx)
    }

    @Test
    fun `pane split falls back without a fold`() {
        assertNull(
            foldAlignedPaneSplit(
                rowLeft = 0,
                rowWidth = 1000,
                foldLeft = null,
                foldRight = null
            ).listWidthPx
        )
    }

    @Test
    fun `pane split falls back when the fold is too close to an edge`() {
        assertNull(
            foldAlignedPaneSplit(
                rowLeft = 0,
                rowWidth = 1000,
                foldLeft = 50,
                foldRight = 70
            ).listWidthPx
        )
        assertNull(
            foldAlignedPaneSplit(
                rowLeft = 0,
                rowWidth = 1000,
                foldLeft = 930,
                foldRight = 950
            ).listWidthPx
        )
    }

    @Test
    fun `pane split falls back when the fold sits outside the row`() {
        // The hinge is left of the pane row, behind the navigation rail.
        assertNull(
            foldAlignedPaneSplit(
                rowLeft = 200,
                rowWidth = 1000,
                foldLeft = 100,
                foldRight = 120
            ).listWidthPx
        )
        // The hinge is right of the pane row.
        assertNull(
            foldAlignedPaneSplit(
                rowLeft = 200,
                rowWidth = 1000,
                foldLeft = 1300,
                foldRight = 1320
            ).listWidthPx
        )
    }

    @Test
    fun `pane split falls back before the row has been measured`() {
        assertNull(
            foldAlignedPaneSplit(
                rowLeft = 0,
                rowWidth = 0,
                foldLeft = 100,
                foldRight = 120
            ).listWidthPx
        )
    }
}

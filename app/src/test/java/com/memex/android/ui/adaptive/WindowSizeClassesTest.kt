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
}

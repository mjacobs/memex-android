package com.memex.android.ui.adaptive

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.collectFoldingFeaturesAsState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

/**
 * Material 3 width breakpoints. Under [MEDIUM_WIDTH_BREAKPOINT] is a phone held
 * upright or a narrow split-screen slot; under [EXPANDED_WIDTH_BREAKPOINT] is a large
 * phone in landscape, a small tablet, or a partly opened foldable; wider than that is a
 * tablet, an unfolded foldable, or a desktop window.
 */
val MEDIUM_WIDTH_BREAKPOINT: Dp = 600.dp
val EXPANDED_WIDTH_BREAKPOINT: Dp = 840.dp

/** Maps a window width to its Material 3 size class. Pure, so it is unit testable. */
fun widthSizeClassFor(width: Dp): WindowWidthSizeClass = when {
    width < MEDIUM_WIDTH_BREAKPOINT -> WindowWidthSizeClass.Compact
    width < EXPANDED_WIDTH_BREAKPOINT -> WindowWidthSizeClass.Medium
    else -> WindowWidthSizeClass.Expanded
}

/**
 * The width size class of the window the app is drawing into.
 *
 * This measures the *window*, not the display, so split-screen, freeform windows and
 * foldables that hand the app only part of the screen are classified by the space the
 * app actually has.
 */
@Composable
fun currentWindowWidthSizeClass(): WindowWidthSizeClass {
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    val widthDp = with(density) { containerWidthPx.toDp() }
    return remember(widthDp) { widthSizeClassFor(widthDp) }
}

/**
 * Whether navigation belongs at the side of the window rather than the bottom. A bottom
 * bar is a thumb-reach affordance for a phone held in one hand; once the window is
 * wider than that, the reachable edge is the side.
 */
val WindowWidthSizeClass.usesNavigationRail: Boolean
    get() = this != WindowWidthSizeClass.Compact

/** Whether the width alone is enough to put a list and its detail side by side. */
val WindowWidthSizeClass.supportsTwoPane: Boolean
    get() = this == WindowWidthSizeClass.Expanded

/**
 * Whether this window should show a list and its detail side by side.
 *
 * Expanded widths always can. A half-opened foldable is the extra case: its window can
 * measure only Medium, but the hinge already splits the screen into two panels, so one
 * pane per panel reads better than a single view straddling the fold.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun currentWindowSupportsTwoPane(
    widthSizeClass: WindowWidthSizeClass = currentWindowWidthSizeClass()
): Boolean {
    val foldingFeatures by collectFoldingFeaturesAsState()
    val isSplitByVerticalFold = foldingFeatures.any {
        it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL
    }
    return widthSizeClass.supportsTwoPane ||
        (widthSizeClass == WindowWidthSizeClass.Medium && isSplitByVerticalFold)
}

package com.memex.android.ui.adaptive

import android.graphics.Rect
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
 * The horizontal bounds, in window pixels, of the separating vertical fold — the
 * hinge of a half-opened foldable — or `null` when there is no such fold. A window can
 * report several folding features; only a separating vertical one actually splits the
 * screen into two panels worth giving one pane each.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun currentVerticalFoldBounds(): Rect? {
    val foldingFeatures by collectFoldingFeaturesAsState()
    return foldingFeatures.firstOrNull {
        it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL
    }?.bounds
}

/**
 * Whether this window should show a list and its detail side by side.
 *
 * Expanded widths always can. A half-opened foldable is the extra case: its window can
 * measure only Medium, but the hinge already splits the screen into two panels, so one
 * pane per panel reads better than a single view straddling the fold.
 */
@Composable
fun currentWindowSupportsTwoPane(
    widthSizeClass: WindowWidthSizeClass = currentWindowWidthSizeClass()
): Boolean {
    val isSplitByVerticalFold = currentVerticalFoldBounds() != null
    return widthSizeClass.supportsTwoPane ||
        (widthSizeClass == WindowWidthSizeClass.Medium && isSplitByVerticalFold)
}

/**
 * The fraction of [containerWidth] the list pane should occupy so its edge lands on
 * the fold instead of a fixed split that may cross it. [foldLeft] is the hinge's left
 * edge, in the same unit as [containerWidth] (both px, or both dp).
 *
 * Falls back to [fallback] when there's no fold to align to, or the fold sits so close
 * to an edge that giving it the pane boundary would leave one pane too small to be
 * useful. Pure, so it is unit testable without a window or a Compose runtime.
 */
fun listPaneFractionForFold(
    containerWidth: Int,
    foldLeft: Int?,
    fallback: Float
): Float {
    if (containerWidth <= 0 || foldLeft == null) return fallback
    val fraction = foldLeft.toFloat() / containerWidth
    return if (fraction in MIN_FOLD_PANE_FRACTION..MAX_FOLD_PANE_FRACTION) {
        fraction
    } else {
        fallback
    }
}

/** Bounds outside which a fold-aligned split would leave a pane too cramped to use. */
private const val MIN_FOLD_PANE_FRACTION = 0.2f
private const val MAX_FOLD_PANE_FRACTION = 0.8f

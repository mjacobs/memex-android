package com.memex.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Real-time animated soundwave visualizer driven by audio amplitude.
 */
@Composable
fun AudioWaveformMeter(
    amplitude: Float, // Normalized 0.0f .. 1.0f
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 28,
    barWidth: Dp = 4.dp,
    maxBarHeight: Dp = 48.dp,
    minBarHeight: Dp = 4.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isRecording) amplitude.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "amplitude_animation"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(maxBarHeight + 8.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f

        val barWidthPx = barWidth.toPx()
        val minHeightPx = minBarHeight.toPx()
        val maxHeightPx = maxBarHeight.toPx()

        val totalBarWidth = barCount * barWidthPx
        val availableSpacing = (canvasWidth - totalBarWidth) / (barCount + 1).coerceAtLeast(1)
        val spacing = availableSpacing.coerceAtLeast(2f)

        val startX = (canvasWidth - (barCount * barWidthPx + (barCount - 1) * spacing)) / 2f

        for (i in 0 until barCount) {
            val normalizedIndex = i.toFloat() / (barCount - 1).coerceAtLeast(1)
            // Bell-curve shape across bars (center highest)
            val bell = sin(normalizedIndex * Math.PI).toFloat().coerceIn(0.2f, 1f)

            // Variational wave modulation based on bar index
            val waveMod = (0.7f + 0.3f * sin(i * 0.8 + animatedAmplitude * 5.0)).toFloat()

            val currentHeight = if (isRecording) {
                (minHeightPx + (maxHeightPx - minHeightPx) * animatedAmplitude * bell * waveMod)
                    .coerceIn(minHeightPx, maxHeightPx)
            } else {
                minHeightPx
            }

            val x = startX + i * (barWidthPx + spacing)
            val y = centerY - currentHeight / 2f

            drawRoundRect(
                color = if (isRecording) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidthPx, currentHeight),
                cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)
            )
        }
    }
}

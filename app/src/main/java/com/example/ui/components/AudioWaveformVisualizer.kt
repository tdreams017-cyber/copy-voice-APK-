package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun AudioWaveformVisualizer(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        val barCount = 28
        val totalWidth = size.width
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.52f).coerceAtLeast(4f)
        val centerY = size.height / 2f
        val maxHalfHeight = size.height * 0.44f

        for (i in 0 until barCount) {
            val fraction = i.toFloat() / (barCount - 1).coerceAtLeast(1)
            val envelope = sin(fraction * Math.PI).toFloat().coerceAtLeast(0.22f)
            val dynamicWave = if (isRecording) {
                val ripple = ((sin(phase + i * 0.55f) + 1f) * 0.5f)
                (0.18f + amplitude * 0.82f * (0.45f + 0.55f * ripple)) * envelope
            } else {
                0.14f * envelope
            }
            val barHalfHeight = (dynamicWave * maxHalfHeight).coerceAtLeast(4f)
            val left = i * slotWidth + (slotWidth - barWidth) / 2f

            val brush = if (isRecording) {
                Brush.verticalGradient(listOf(secondaryColor, primaryColor))
            } else {
                Brush.verticalGradient(listOf(inactiveColor, inactiveColor))
            }

            drawRoundRect(
                brush = brush,
                topLeft = Offset(left, centerY - barHalfHeight),
                size = Size(barWidth, barHalfHeight * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

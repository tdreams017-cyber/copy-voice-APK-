package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NabraDarkColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = Color(0xFF1A1100),
    primaryContainer = AmberGoldSoft,
    onPrimaryContainer = AmberGoldLight,
    secondary = AcousticTeal,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = AcousticTealSoft,
    onSecondaryContainer = AcousticTeal,
    tertiary = AmberGoldLight,
    background = ObsidianBg,
    onBackground = InkWhite,
    surface = ObsidianSurface,
    onSurface = InkWhite,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = InkMuted,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1F2836),
    error = RecordingCoral,
    onError = Color.White,
    errorContainer = RecordingCoralSoft,
    onErrorContainer = Color(0xFFFFDAD9)
)

private val NabraLightColorScheme = lightColorScheme(
    primary = AmberDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5B8),
    onPrimaryContainer = Color(0xFF331D00),
    secondary = TealDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    background = LightBg,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightInkMuted,
    outline = Color(0xFFD0D7E2),
    error = Color(0xFFD92D20),
    onError = Color.White
)

private val NabraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NabraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NabraDarkColorScheme else NabraLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = NabraTypography,
        shapes = NabraShapes,
        content = content
    )
}

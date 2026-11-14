package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val HardwareLabColorScheme = darkColorScheme(
    primary = LabCyan,
    onPrimary = LabBackground,
    primaryContainer = LabCyanDim,
    onPrimaryContainer = LabTextPrimary,
    secondary = LabGreen,
    onSecondary = LabBackground,
    secondaryContainer = LabGreenDim,
    onSecondaryContainer = LabTextPrimary,
    tertiary = LabBlue,
    onTertiary = LabBackground,
    tertiaryContainer = LabBlueDim,
    onTertiaryContainer = LabTextPrimary,
    background = LabBackground,
    onBackground = LabTextPrimary,
    surface = LabSurface,
    onSurface = LabTextPrimary,
    surfaceVariant = LabSurfaceVariant,
    onSurfaceVariant = LabTextSecondary,
    surfaceContainer = LabSurface,
    surfaceContainerHigh = LabSurfaceHigh,
    surfaceContainerHighest = LabSurfaceHighest,
    outline = LabBorderStrong,
    outlineVariant = LabBorderSubtle,
    error = LabRed,
    onError = LabBackground,
    errorContainer = LabRedDim,
    onErrorContainer = LabTextPrimary
)

val LabShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun HardwareLabTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HardwareLabColorScheme,
        typography = LabTypography,
        shapes = LabShapes,
        content = content
    )
}

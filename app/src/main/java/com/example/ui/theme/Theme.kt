package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.example.core.AuraThemeColor

/**
 * Pure OLED dark Material 3 theme driven by the user's selected AuraThemeColor.
 */
@Composable
fun AuraClockTheme(
  accent: AuraThemeColor = AuraThemeColor.COBALT,
  content: @Composable () -> Unit,
) {
  val colorScheme = darkColorScheme(
    primary = accent.primary,
    onPrimary = if (accent == AuraThemeColor.ICE || accent == AuraThemeColor.AMBER || accent == AuraThemeColor.GOLD) OledBlack else TextPrimary,
    primaryContainer = accent.container,
    onPrimaryContainer = TextPrimary,
    secondary = accent.primary,
    onSecondary = TextPrimary,
    background = OledBlack,
    onBackground = TextPrimary,
    surface = SurfaceObsidian,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceOutline,
    error = DangerRed,
    onError = TextPrimary,
  )

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AuraTypography,
    content = content,
  )
}

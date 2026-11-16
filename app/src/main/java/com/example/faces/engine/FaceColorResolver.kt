package com.example.faces.engine

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Resolves hex literals and Material 3 colour tokens (@primary, @secondary, @onSurface, @surface, @error)
 * without allocating objects on the draw path.
 */
internal object FaceColorResolver {

  /** Resolves [spec] using [colorScheme] and multiplies alpha by [alphaMultiplier]. */
  fun resolve(
    spec: ColorSpec,
    colorScheme: ColorScheme,
    alphaMultiplier: Float = 1f,
  ): Color {
    val base = when (spec) {
      is ColorSpec.Hex -> Color(spec.argb)
      is ColorSpec.Token -> when (spec.name) {
        "primary" -> colorScheme.primary
        "secondary" -> colorScheme.secondary
        "onSurface" -> colorScheme.onSurface
        "surface" -> colorScheme.surface
        "error" -> colorScheme.error
        else -> colorScheme.onSurface
      }
    }
    val clampedAlpha = alphaMultiplier.coerceIn(0f, 1f)
    return if (clampedAlpha >= 0.999f) base else base.copy(alpha = base.alpha * clampedAlpha)
  }
}

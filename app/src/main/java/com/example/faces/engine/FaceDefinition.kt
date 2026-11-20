package com.example.faces.engine

import androidx.compose.ui.graphics.StrokeCap
import com.example.core.FaceDataNeed
import com.example.core.FaceRenderMode
import com.example.core.FaceUpdatePolicy

/** Outer dial silhouette used for thumbnail framing and layout metadata. */
internal enum class CanvasShape {
  CIRCLE,
  ROUNDED_RECT,
}

/** Controls which render modes a layer participates in. */
internal enum class LayerMode {
  NORMAL,
  AMBIENT,
  BOTH,
}

/** Represents a colour resolved either from an ARGB hex literal or a Material 3 theme token. */
internal sealed interface ColorSpec {
  data class Hex(val argb: Int) : ColorSpec
  data class Token(val name: String) : ColorSpec
}

/** Runtime condition controlling whether a layer is drawn for the current snapshot. */
internal sealed interface ShowCondition {
  data object TimerRunning : ShowCondition
  data object HasNextEvent : ShowCondition
  data object Charging : ShowCondition
  data class BatteryBelow(val thresholdPercent: Int) : ShowCondition
  data object IsAmbient : ShowCondition
}

internal enum class ShapeKind {
  CIRCLE,
  RING,
  RECT,
  ROUNDED_RECT,
}

internal enum class HandSource {
  HOUR,
  MINUTE,
  MINUTE_STEP,
  SECOND,
  BATTERY,
  TIMER_PROGRESS,
  STOPWATCH_SECONDS,
  STOPWATCH_MINUTES,
}

internal enum class GaugeSource {
  BATTERY,
  TIMER_PROGRESS,
  SECONDS_PROGRESS,
  MINUTES_PROGRESS,
}

internal enum class TextWeight {
  NORMAL,
  MEDIUM,
  BOLD,
}

internal enum class TextAlign {
  LEFT,
  CENTER,
  RIGHT,
}

internal enum class TextFont {
  DEFAULT,
  SANS,
  MONO,
}

internal data class HandCounterweight(
  val radiusDp: Float,
  val ringColor: ColorSpec,
  val centerColor: ColorSpec,
  val positionRatio: Float? = null,
  val tailOffsetDp: Float? = null,
)

/** Common properties shared across all watch face definition layers. */
internal sealed interface FaceLayer {
  val id: String
  val alpha: Float
  val mode: LayerMode
  val showWhen: ShowCondition?
}

internal data class ImageLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val drawableName: String,
) : FaceLayer

internal data class ShapeLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val kind: ShapeKind,
  val x: Float = 0f,
  val y: Float = 0f,
  val width: Float? = null,
  val height: Float? = null,
  val radius: Float? = null,
  val radiusDp: Float? = null,
  val cornerRadius: Float = 0.08f,
  val fillColor: ColorSpec? = null,
  val strokeColor: ColorSpec? = null,
  val strokeWidthDp: Float = 0f,
) : FaceLayer

internal data class TicksLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val count: Int,
  val innerRadius: Float,
  val outerRadius: Float,
  val widthDp: Float,
  val color: ColorSpec,
  val cap: StrokeCap = StrokeCap.Round,
  val majorEvery: Int = 0,
  val majorInnerRadius: Float? = null,
  val majorOuterRadius: Float? = null,
  val majorWidthDp: Float? = null,
  val majorColor: ColorSpec? = null,
) : FaceLayer

internal data class HandLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val source: HandSource,
  val pivotX: Float = 0f,
  val pivotY: Float = 0f,
  val length: Float,
  val tail: Float? = null,
  val tailDp: Float? = null,
  val widthDp: Float,
  val cap: StrokeCap = StrokeCap.Round,
  val bodyColor: ColorSpec,
  val stripeColor: ColorSpec? = null,
  val stripeWidthDp: Float = 0f,
  val stripeTipOnly: Boolean = false,
  val shadow: Boolean = true,
  val shadowAlpha: Float = 0.45f,
  val smooth: Boolean = false,
  val minValue: Float = 0f,
  val maxValue: Float = 100f,
  val startAngle: Float = 0f,
  val endAngle: Float = 360f,
  val counterweight: HandCounterweight? = null,
  val centerDotRadiusDp: Float = 0f,
) : FaceLayer

internal data class TextLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val template: String,
  val x: Float = 0f,
  val y: Float = 0f,
  val size: Float,
  val weight: TextWeight = TextWeight.NORMAL,
  val align: TextAlign = TextAlign.CENTER,
  val color: ColorSpec,
  val letterSpacing: Float = 0f,
  val font: TextFont = TextFont.DEFAULT,
  val customFontRef: String? = null,
) : FaceLayer

internal data class GaugeLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val source: GaugeSource,
  val x: Float = 0f,
  val y: Float = 0f,
  val radius: Float,
  val startAngle: Float = -90f,
  val sweepAngle: Float = 360f,
  val thicknessDp: Float = 6f,
  val cap: StrokeCap = StrokeCap.Round,
  val trackColor: ColorSpec? = null,
  val fillColor: ColorSpec,
) : FaceLayer

internal data class SlotLayer(
  override val id: String,
  override val alpha: Float = 1f,
  override val mode: LayerMode = LayerMode.BOTH,
  override val showWhen: ShowCondition? = null,
  val template: String,
  val x: Float = 0f,
  val y: Float = 0f,
  val width: Float,
  val height: Float,
  val cornerRadius: Float = 0.04f,
  val fillColor: ColorSpec? = null,
  val strokeColor: ColorSpec? = null,
  val strokeWidthDp: Float = 1f,
  val textSize: Float,
  val textColor: ColorSpec,
  val weight: TextWeight = TextWeight.BOLD,
  val align: TextAlign = TextAlign.CENTER,
  val font: TextFont = TextFont.MONO,
  val customFontRef: String? = null,
  val letterSpacing: Float = 0f,
) : FaceLayer

/**
 * Immutable data-driven watch face model parsed once from JSON.
 * Derives exact [dataNeeds] and per-mode [updatePolicy] so unused sensors and tickers remain idle.
 */
internal data class FaceDefinition(
  val id: String,
  val name: String,
  val canvas: CanvasShape,
  val layers: List<FaceLayer>,
) {
  /** Only the core data sources referenced by at least one layer in this face. */
  val dataNeeds: Set<FaceDataNeed> by lazy(LazyThreadSafetyMode.NONE) { computeDataNeeds(layers) }

  /** Returns true when this face has a layer eligible for [mode]. */
  fun isLayerActiveInMode(layer: FaceLayer, mode: FaceRenderMode): Boolean {
    val modeMatches = when (layer.mode) {
      LayerMode.BOTH -> true
      LayerMode.NORMAL -> mode == FaceRenderMode.NORMAL
      LayerMode.AMBIENT -> mode == FaceRenderMode.AMBIENT
    }
    if (!modeMatches) return false
    // Ambient mode never draws seconds hands.
    if (mode == FaceRenderMode.AMBIENT && layer is HandLayer && layer.source == HandSource.SECOND) {
      return false
    }
    return true
  }

  /**
   * Computes the tightest power-saving update cadence required for [mode].
   * Faces without any visible seconds element fall back to [FaceUpdatePolicy.EVERY_MINUTE].
   */
  fun updatePolicy(mode: FaceRenderMode): FaceUpdatePolicy {
    var needsSecondTick = false
    for (layer in layers) {
      if (!isLayerActiveInMode(layer, mode)) continue
      when (layer) {
        is HandLayer -> {
          when (layer.source) {
            HandSource.SECOND, HandSource.STOPWATCH_SECONDS -> {
              if (layer.smooth && mode == FaceRenderMode.NORMAL) {
                return FaceUpdatePolicy.SMOOTH_FRAME
              }
              needsSecondTick = true
            }
            else -> Unit
          }
        }
        is GaugeLayer -> {
          if (layer.source == GaugeSource.SECONDS_PROGRESS || layer.source == GaugeSource.TIMER_PROGRESS) {
            needsSecondTick = true
          }
        }
        is TextLayer -> {
          if (layer.template.contains("{second}") || layer.template.contains("{timerRemaining}")) {
            needsSecondTick = true
          }
        }
        is SlotLayer -> {
          if (layer.template.contains("{second}") || layer.template.contains("{timerRemaining}")) {
            needsSecondTick = true
          }
        }
        is ImageLayer, is ShapeLayer, is TicksLayer -> Unit
      }
    }
    return if (needsSecondTick) FaceUpdatePolicy.EVERY_SECOND else FaceUpdatePolicy.EVERY_MINUTE
  }

  private fun computeDataNeeds(layers: List<FaceLayer>): Set<FaceDataNeed> {
    val needs = mutableSetOf<FaceDataNeed>()
    for (layer in layers) {
      when (layer.showWhen) {
        ShowCondition.TimerRunning -> needs += FaceDataNeed.TIMER
        ShowCondition.HasNextEvent -> needs += FaceDataNeed.NEXT_EVENT
        ShowCondition.Charging, is ShowCondition.BatteryBelow -> needs += FaceDataNeed.BATTERY
        ShowCondition.IsAmbient, null -> Unit
      }
      when (layer) {
        is HandLayer -> when (layer.source) {
          HandSource.HOUR, HandSource.MINUTE, HandSource.MINUTE_STEP, HandSource.SECOND ->
            needs += FaceDataNeed.TIME
          HandSource.BATTERY -> needs += FaceDataNeed.BATTERY
          HandSource.TIMER_PROGRESS -> needs += FaceDataNeed.TIMER
          HandSource.STOPWATCH_SECONDS, HandSource.STOPWATCH_MINUTES ->
            needs += FaceDataNeed.STOPWATCH
        }
        is GaugeLayer -> when (layer.source) {
          GaugeSource.BATTERY -> needs += FaceDataNeed.BATTERY
          GaugeSource.TIMER_PROGRESS -> needs += FaceDataNeed.TIMER
          GaugeSource.SECONDS_PROGRESS, GaugeSource.MINUTES_PROGRESS ->
            needs += FaceDataNeed.TIME
        }
        is TextLayer -> scanTemplateNeeds(layer.template, needs)
        is SlotLayer -> scanTemplateNeeds(layer.template, needs)
        is ImageLayer, is ShapeLayer, is TicksLayer -> Unit
      }
    }
    if (needs.isEmpty()) {
      needs += FaceDataNeed.TIME
    }
    return needs.toSet()
  }

  private fun scanTemplateNeeds(template: String, needs: MutableSet<FaceDataNeed>) {
    if (template.contains("{hour") || template.contains("{minute}") ||
      template.contains("{second}") || template.contains("{ampm}") ||
      template.contains("{day") || template.contains("{monthName}")
    ) {
      needs += FaceDataNeed.TIME
    }
    if (template.contains("{battery}")) {
      needs += FaceDataNeed.BATTERY
    }
    if (template.contains("{nextEvent")) {
      needs += FaceDataNeed.NEXT_EVENT
    }
    if (template.contains("{timer")) {
      needs += FaceDataNeed.TIMER
    }
  }
}

package com.example.faces.wff

import android.graphics.Paint
import android.graphics.PorterDuff
import kotlin.math.roundToInt

/**
 * Scene graph models representing the expanded Wear OS Watch Face Format (WFF) specification:
 * full expression AST, conditions, variants, animations, user configurations, bitmap fonts,
 * text shadows/outlines, image blend modes, and scored compatibility reports.
 */

enum class WffClipShape {
  NONE,
  CIRCLE,
  RECTANGLE,
}

enum class WffTextAlign {
  START,
  CENTER,
  END,
}

enum class WffElementStatus {
  DRAWN,
  PARTLY_DRAWN,
  SKIPPED,
}

enum class WffBlendMode(val porterDuff: PorterDuff.Mode) {
  SRC_OVER(PorterDuff.Mode.SRC_OVER),
  MULTIPLY(PorterDuff.Mode.MULTIPLY),
  SCREEN(PorterDuff.Mode.SCREEN),
  ADD(PorterDuff.Mode.ADD),
  OVERLAY(PorterDuff.Mode.OVERLAY),
  DARKEN(PorterDuff.Mode.DARKEN),
  LIGHTEN(PorterDuff.Mode.LIGHTEN),
  SRC_IN(PorterDuff.Mode.SRC_IN),
  SRC_ATOP(PorterDuff.Mode.SRC_ATOP),
  XOR(PorterDuff.Mode.XOR),
}

enum class WffInterpolation {
  LINEAR,
  EASE_IN,
  EASE_OUT,
  EASE_IN_OUT,
  OVERSHOOT,
  CUBIC_BEZIER,
}

enum class WffAngleDirection {
  NONE,
  CLOCKWISE,
  COUNTER_CLOCKWISE,
}

/** Represents a compiled WFF expression AST supporting arithmetic, logic, comparisons, and functions. */
sealed class WffExpr {
  /** Constant numeric literal. */
  data class ConstNum(val value: Double) : WffExpr()

  /** Constant string literal. */
  data class ConstStr(val value: String) : WffExpr()

  /** Constant boolean literal. */
  data class ConstBool(val value: Boolean) : WffExpr()

  /** Live data source token inside brackets such as [HOUR_0_11] or [CONFIGURATION.theme]. */
  data class Source(val name: String) : WffExpr()

  /** Unary operator (!, -, +, ~). */
  data class Unary(val op: WffUnaryOp, val operand: WffExpr) : WffExpr()

  /** Binary arithmetic, bitwise, comparison, or logical operator. */
  data class Binary(val op: WffBinaryOp, val left: WffExpr, val right: WffExpr) : WffExpr()

  /** Inline ternary conditional (cond ? ifTrue : ifFalse). */
  data class Ternary(val cond: WffExpr, val ifTrue: WffExpr, val ifFalse: WffExpr) : WffExpr()

  /** Built-in WFF function invocation. */
  data class Call(val name: String, val args: List<WffExpr>) : WffExpr()
}

enum class WffUnaryOp {
  NEG,
  POS,
  NOT,
  BIT_NOT,
}

enum class WffBinaryOp {
  ADD, SUB, MUL, DIV, MOD,
  SHL, SHR, BIT_AND, BIT_XOR, BIT_OR,
  EQ, NEQ, LT, LTE, GT, GTE,
  AND, OR,
}

/** Compiled expression paired with input dependency bitmask and diagnostics on unknown symbols. */
data class CompiledWffExpr(
  val raw: String,
  val ast: WffExpr,
  val inputMask: Int,
  val unknownSources: List<String> = emptyList(),
  val unknownFunctions: List<String> = emptyList(),
)

/** Optional animation curve attached to a WFF Transform. */
data class WffAnimationSpec(
  val durationSec: Float = 0.5f,
  val interpolation: WffInterpolation = WffInterpolation.LINEAR,
  val controls: FloatArray = floatArrayOf(0.42f, 0f, 0.58f, 1f),
  val repeat: Int = 0,
  val angleDirection: WffAngleDirection = WffAngleDirection.NONE,
  val fps: Int = 15,
  val fromValue: Float? = null,
) {
  override fun equals(other: Any?): Boolean =
    this === other || (other is WffAnimationSpec && durationSec == other.durationSec &&
      interpolation == other.interpolation && controls.contentEquals(other.controls) &&
      repeat == other.repeat && angleDirection == other.angleDirection && fps == other.fps &&
      fromValue == other.fromValue)

  override fun hashCode(): Int =
    31 * durationSec.hashCode() + repeat + fps
}

/** Records a dynamic property transformation driven by a compiled WFF expression and optional Animation. */
data class WffTransform(
  val key: String,
  val target: String,
  val valueExpr: CompiledWffExpr,
  val animation: WffAnimationSpec? = null,
)

/** Records a mode-specific attribute override (e.g. AMBIENT) on a WFF element. */
data class WffVariant(
  val mode: String,
  val target: String,
  val value: String,
)

/** Motion policy for an analog watch hand (smooth Sweep or stepped Tick). */
sealed class WffMotionPolicy {
  /** Continuous sweep motion updating at [frequency] Hz. */
  data class Sweep(val frequency: Int = 15) : WffMotionPolicy()

  /** Discrete tick motion stepping every second. */
  data class Tick(val duration: Float = 0.2f, val strength: Float = 0f) : WffMotionPolicy()
}

/** Analog clock hand definition (HourHand, MinuteHand, or SecondHand). */
data class WffHandNode(
  val role: WffHandRole,
  val resource: String,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val pivotX: Float,
  val pivotY: Float,
  val scaleX: Float = 1f,
  val scaleY: Float = 1f,
  val alpha: Int = 255,
  val tintColor: Int? = null,
  val tintColorExpr: CompiledWffExpr? = null,
  val motionPolicy: WffMotionPolicy? = null,
  val transforms: List<WffTransform> = emptyList(),
  val variants: List<WffVariant> = emptyList(),
)

enum class WffHandRole {
  HOUR,
  MINUTE,
  SECOND,
}

/** Drop shadow styling applied to WFF text elements. */
data class WffTextShadow(
  val color: Int,
  val offsetX: Float,
  val offsetY: Float,
  val radius: Float,
)

/** Stroke outline styling applied around WFF text glyphs. */
data class WffTextOutline(
  val color: Int,
  val width: Float,
)

/** Formatted digital time element inside a DigitalClock container. */
data class WffTimeTextNode(
  val format: String,
  val hourFormat: String,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val align: WffTextAlign,
  val alpha: Int,
  val fontFamily: String?,
  val fontSize: Float,
  val color: Int,
  val colorExpr: CompiledWffExpr? = null,
  val weight: String? = null,
  val slant: String? = null,
  val bitmapFontName: String? = null,
  val shadow: WffTextShadow? = null,
  val outline: WffTextOutline? = null,
  val transforms: List<WffTransform> = emptyList(),
  val variants: List<WffVariant> = emptyList(),
)

/** Geometric vector shape inside a PartDraw container. */
sealed class WffDrawShape {
  abstract val fillColor: Int?
  abstract val fillColorExpr: CompiledWffExpr?
  abstract val strokeColor: Int?
  abstract val strokeColorExpr: CompiledWffExpr?
  abstract val strokeWidth: Float
  abstract val cap: Paint.Cap

  /** Ellipse fitted inside bounds ([x], [y], [width], [height]). */
  data class Ellipse(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    override val fillColor: Int?,
    override val fillColorExpr: CompiledWffExpr? = null,
    override val strokeColor: Int?,
    override val strokeColorExpr: CompiledWffExpr? = null,
    override val strokeWidth: Float,
    override val cap: Paint.Cap,
  ) : WffDrawShape()

  /** Axis-aligned rectangle fitted inside bounds ([x], [y], [width], [height]). */
  data class Rectangle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    override val fillColor: Int?,
    override val fillColorExpr: CompiledWffExpr? = null,
    override val strokeColor: Int?,
    override val strokeColorExpr: CompiledWffExpr? = null,
    override val strokeWidth: Float,
    override val cap: Paint.Cap,
  ) : WffDrawShape()

  /** Rounded rectangle fitted inside bounds with corner radii ([cornerRadiusX], [cornerRadiusY]). */
  data class RoundRectangle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cornerRadiusX: Float,
    val cornerRadiusY: Float,
    override val fillColor: Int?,
    override val fillColorExpr: CompiledWffExpr? = null,
    override val strokeColor: Int?,
    override val strokeColorExpr: CompiledWffExpr? = null,
    override val strokeWidth: Float,
    override val cap: Paint.Cap,
  ) : WffDrawShape()

  /** Circular or elliptical arc spanning from [startAngle] by [sweepAngle] degrees. */
  data class Arc(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val startAngle: Float,
    val sweepAngle: Float,
    override val fillColor: Int?,
    override val fillColorExpr: CompiledWffExpr? = null,
    override val strokeColor: Int?,
    override val strokeColorExpr: CompiledWffExpr? = null,
    override val strokeWidth: Float,
    override val cap: Paint.Cap,
    val transforms: List<WffTransform> = emptyList(),
  ) : WffDrawShape()

  /** Straight line segment from ([startX], [startY]) to ([endX], [endY]). */
  data class Line(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    override val fillColor: Int?,
    override val fillColorExpr: CompiledWffExpr? = null,
    override val strokeColor: Int?,
    override val strokeColorExpr: CompiledWffExpr? = null,
    override val strokeWidth: Float,
    override val cap: Paint.Cap,
  ) : WffDrawShape()
}

/** Single conditional Compare branch pairing a predicate expression with child scene nodes. */
data class WffCompareBranch(
  val expression: CompiledWffExpr,
  val children: List<WffSceneNode>,
)

/** Base node inside a Watch Face Format Scene hierarchy. */
sealed class WffSceneNode {
  abstract val x: Float
  abstract val y: Float
  abstract val width: Float
  abstract val height: Float
  abstract val pivotX: Float
  abstract val pivotY: Float
  abstract val angle: Float
  abstract val scaleX: Float
  abstract val scaleY: Float
  abstract val alpha: Int
  abstract val visible: Boolean
  abstract val transforms: List<WffTransform>
  abstract val variants: List<WffVariant>

  /** Container node grouping child elements with shared geometry, transforms, and variants. */
  data class Group(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val pivotX: Float,
    override val pivotY: Float,
    override val angle: Float,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int,
    override val visible: Boolean = true,
    val children: List<WffSceneNode>,
    override val transforms: List<WffTransform>,
    override val variants: List<WffVariant>,
  ) : WffSceneNode()

  /** Conditional container evaluating Compare branches top-down with optional Default fallback. */
  data class Condition(
    val branches: List<WffCompareBranch>,
    val defaultChildren: List<WffSceneNode>,
    override val x: Float = 0f,
    override val y: Float = 0f,
    override val width: Float = 0f,
    override val height: Float = 0f,
    override val pivotX: Float = 0.5f,
    override val pivotY: Float = 0.5f,
    override val angle: Float = 0f,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int = 255,
    override val visible: Boolean = true,
    override val transforms: List<WffTransform> = emptyList(),
    override val variants: List<WffVariant> = emptyList(),
  ) : WffSceneNode()

  /** Analog clock component hosting HourHand, MinuteHand, and SecondHand elements. */
  data class AnalogClock(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val pivotX: Float,
    override val pivotY: Float,
    override val angle: Float,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int,
    override val visible: Boolean = true,
    val hourHands: List<WffHandNode>,
    val minuteHands: List<WffHandNode>,
    val secondHands: List<WffHandNode>,
    override val transforms: List<WffTransform>,
    override val variants: List<WffVariant>,
  ) : WffSceneNode()

  /** Digital clock component hosting TimeText display elements. */
  data class DigitalClock(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val pivotX: Float,
    override val pivotY: Float,
    override val angle: Float,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int,
    override val visible: Boolean = true,
    val timeTexts: List<WffTimeTextNode>,
    override val transforms: List<WffTransform>,
    override val variants: List<WffVariant>,
  ) : WffSceneNode()

  /** Image layer rendering a resource (or frame list) with optional tint expression and blend mode. */
  data class PartImage(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val pivotX: Float,
    override val pivotY: Float,
    override val angle: Float,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int,
    override val visible: Boolean = true,
    val tintColor: Int?,
    val tintColorExpr: CompiledWffExpr? = null,
    val blendMode: WffBlendMode = WffBlendMode.SRC_OVER,
    val resources: List<String>,
    override val transforms: List<WffTransform>,
    override val variants: List<WffVariant>,
  ) : WffSceneNode() {
    val primaryResource: String? get() = resources.firstOrNull()
  }

  /** Text layer rendering static text, formatted Template+Parameter, custom TTF font, or BitmapFont glyphs. */
  data class PartText(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val pivotX: Float,
    override val pivotY: Float,
    override val angle: Float,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int,
    override val visible: Boolean = true,
    val align: WffTextAlign,
    val fontFamily: String?,
    val fontSize: Float,
    val color: Int,
    val colorExpr: CompiledWffExpr? = null,
    val weight: String?,
    val slant: String?,
    val bitmapFontName: String? = null,
    val shadow: WffTextShadow? = null,
    val outline: WffTextOutline? = null,
    val rawText: String?,
    val template: String?,
    val parameters: List<CompiledWffExpr>,
    override val transforms: List<WffTransform>,
    override val variants: List<WffVariant>,
  ) : WffSceneNode()

  /** Vector drawing layer rendering geometric shapes. */
  data class PartDraw(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val pivotX: Float,
    override val pivotY: Float,
    override val angle: Float,
    override val scaleX: Float = 1f,
    override val scaleY: Float = 1f,
    override val alpha: Int,
    override val visible: Boolean = true,
    val shapes: List<WffDrawShape>,
    override val transforms: List<WffTransform>,
    override val variants: List<WffVariant>,
  ) : WffSceneNode()
}

/** Custom image-backed glyph mapping for a named WFF BitmapFont. */
data class WffBitmapGlyph(
  val key: String,
  val resource: String,
  val width: Float,
  val height: Float,
)

/** Named BitmapFont containing character and word image mappings. */
data class WffBitmapFont(
  val name: String,
  val glyphs: Map<String, WffBitmapGlyph>,
)

/** Color swatch option inside a WFF ColorConfiguration. */
data class WffColorOption(
  val id: String,
  val displayName: String,
  val colors: List<Int>,
)

/** Selectable item inside a WFF ListConfiguration. */
data class WffListOption(
  val id: String,
  val displayName: String,
)

/** User-customizable setting declared inside WatchFace UserConfigurations. */
sealed class WffUserConfig {
  abstract val id: String
  abstract val displayName: String
  abstract val defaultValue: String

  /** Color theme option selector (stores active option ID and resolves ARGB swatches). */
  data class ColorConfig(
    override val id: String,
    override val displayName: String,
    override val defaultValue: String,
    val options: List<WffColorOption>,
  ) : WffUserConfig()

  /** Discrete list choice selector. */
  data class ListConfig(
    override val id: String,
    override val displayName: String,
    override val defaultValue: String,
    val options: List<WffListOption>,
  ) : WffUserConfig()

  /** Boolean toggle option. */
  data class BooleanConfig(
    override val id: String,
    override val displayName: String,
    val defaultBool: Boolean,
  ) : WffUserConfig() {
    override val defaultValue: String get() = if (defaultBool) "TRUE" else "FALSE"
  }
}

/** Single element or resource status entry in a WFF compatibility report. */
data class WffReportItem(
  val element: String,
  val status: WffElementStatus,
  val detail: String = "",
)

/** Legacy skipped entry shape kept for diagnostics display helpers. */
data class WffSkippedItem(
  val element: String,
  val reason: String,
)

/** Scored per-face compatibility report summarizing drawn, partly drawn, and skipped elements. */
data class WffCompatibilityReport(
  val items: List<WffReportItem> = emptyList(),
  val missingResources: List<String> = emptyList(),
) {
  val drawnElements: List<String>
    get() = items.filter { it.status == WffElementStatus.DRAWN }.map { it.element }

  val partlyDrawnItems: List<WffSkippedItem>
    get() = items.filter { it.status == WffElementStatus.PARTLY_DRAWN }
      .map { WffSkippedItem(it.element, it.detail) }

  val skippedItems: List<WffSkippedItem>
    get() = items.filter { it.status == WffElementStatus.SKIPPED }
      .map { WffSkippedItem(it.element, it.detail) }

  val drawnCount: Int get() = items.count { it.status == WffElementStatus.DRAWN }
  val partlyDrawnCount: Int get() = items.count { it.status == WffElementStatus.PARTLY_DRAWN }
  val skippedCount: Int get() = items.count { it.status == WffElementStatus.SKIPPED }

  /** Total compatibility score (0..100%): drawn = 100%, partly drawn = 50%, skipped = 0%. */
  val scorePercent: Int
    get() {
      if (items.isEmpty()) return 100
      val points = items.sumOf { item ->
        when (item.status) {
          WffElementStatus.DRAWN -> 1.0
          WffElementStatus.PARTLY_DRAWN -> 0.5
          WffElementStatus.SKIPPED -> 0.0
        }
      }
      return ((points / items.size) * 100.0).roundToInt().coerceIn(0, 100)
    }
}

/** Complete parsed Watch Face Format model ready for rendering and live user customization. */
data class WffWatchFace(
  val id: String,
  val name: String,
  val width: Float,
  val height: Float,
  val clipShape: WffClipShape,
  val cornerRadiusX: Float,
  val cornerRadiusY: Float,
  val backgroundColor: Int,
  val backgroundColorExpr: CompiledWffExpr? = null,
  val sceneTransforms: List<WffTransform> = emptyList(),
  val sceneVariants: List<WffVariant> = emptyList(),
  val nodes: List<WffSceneNode>,
  val userConfigs: List<WffUserConfig> = emptyList(),
  val bitmapFonts: Map<String, WffBitmapFont> = emptyMap(),
  val metadata: Map<String, String> = emptyMap(),
  val referencedResources: Set<String> = emptySet(),
  val referencedFonts: Set<String> = emptySet(),
  val report: WffCompatibilityReport = WffCompatibilityReport(),
)

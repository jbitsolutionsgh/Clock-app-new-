package com.example.faces.wff

import android.graphics.Paint
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/** Shared parser accumulator tracking compatibility statuses, resources, fonts, strings, and metadata. */
internal class WffParseContext(
  val stringResources: Map<String, String> = emptyMap(),
) {
  val metadata = LinkedHashMap<String, String>()
  val referencedResources = LinkedHashSet<String>()
  val referencedFonts = LinkedHashSet<String>()
  val usedSources = LinkedHashSet<String>()
  val userConfigs = mutableListOf<WffUserConfig>()
  var flavors: WffFlavorSet? = null
  var localizationCalendar: String? = null
  var localizationLocales: String? = null
  val bitmapFonts = LinkedHashMap<String, WffBitmapFont>()
  val items = mutableListOf<WffReportItem>()
  var nextAnimKeyId = 1

  fun nextTransformKey(target: String): String = "tr_${nextAnimKeyId++}_$target"

  fun resolveString(raw: String): String = WffStringResources.resolve(raw, stringResources)

  fun resolveStringOrNull(raw: String?): String? = WffStringResources.resolveOrNull(raw, stringResources)

  /** Compiles [rawExpr], records referenced data sources, and logs unknown symbols as PARTLY_DRAWN. */
  fun compileTracked(ownerElement: String, rawExpr: String): CompiledWffExpr {
    val compiled = WffExpression.compile(rawExpr)
    WffExpression.collectAstSources(compiled.ast, usedSources)
    if (compiled.unknownSources.isNotEmpty() || compiled.unknownFunctions.isNotEmpty()) {
      val details = buildList {
        if (compiled.unknownSources.isNotEmpty()) add("unknown sources: ${compiled.unknownSources.joinToString()}")
        if (compiled.unknownFunctions.isNotEmpty()) add("unknown functions: ${compiled.unknownFunctions.joinToString()}")
      }.joinToString("; ")
      recordItem(ownerElement, WffElementStatus.PARTLY_DRAWN, details)
    }
    return compiled
  }

  /** Records Drawn, Partly Drawn, or Skipped status without duplicate noise on identical entries. */
  fun recordItem(element: String, status: WffElementStatus, detail: String = "") {
    if (status == WffElementStatus.DRAWN) {
      if (items.none { it.element == element }) {
        items += WffReportItem(element, WffElementStatus.DRAWN, detail)
      }
      return
    }
    val existingIdx = items.indexOfFirst { it.element == element && it.detail == detail }
    if (existingIdx < 0) {
      items += WffReportItem(element, status, detail)
    }
  }
}

/**
 * Attribute, styling, Transform/Animation, Variant, UserConfiguration, BitmapFont, and Part
 * parsing helpers extracted from WffXmlParser to keep modules compact and well under 500 lines.
 */
internal object WffXmlPartParsers {

  data class CommonAttrs(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val pivotX: Float,
    val pivotY: Float,
    val angle: Float,
    val scaleX: Float,
    val scaleY: Float,
    val alpha: Int,
    val visible: Boolean,
  )

  fun readCommonAttrs(parser: XmlPullParser): CommonAttrs = CommonAttrs(
    x = attrFloat(parser, "x", 0f),
    y = attrFloat(parser, "y", 0f),
    width = attrFloat(parser, "width", 100f),
    height = attrFloat(parser, "height", 100f),
    pivotX = attrFloat(parser, "pivotX", 0.5f),
    pivotY = attrFloat(parser, "pivotY", 0.5f),
    angle = attrFloat(parser, "angle", 0f),
    scaleX = attrFloat(parser, "scaleX", 1f),
    scaleY = attrFloat(parser, "scaleY", 1f),
    alpha = attrInt(parser, "alpha", 255).coerceIn(0, 255),
    visible = !parser.getAttributeValue(null, "visible").equals("false", ignoreCase = true) &&
      !parser.getAttributeValue(null, "visibility").equals("INVISIBLE", ignoreCase = true) &&
      !parser.getAttributeValue(null, "visibility").equals("GONE", ignoreCase = true),
  )

  fun parseTransform(parser: XmlPullParser, ctx: WffParseContext): WffTransform? {
    val target = parser.getAttributeValue(null, "target")?.trim().orEmpty()
    val value = parser.getAttributeValue(null, "value")?.trim().orEmpty()
    var anim: WffAnimationSpec? = null
    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      if (parser.name == "Animation") {
        anim = parseAnimationSpec(parser)
        ctx.recordItem("Animation", WffElementStatus.DRAWN)
        skipSubtree(parser)
      } else {
        ctx.recordItem(parser.name, WffElementStatus.SKIPPED, "Unsupported child inside <Transform>.")
        skipSubtree(parser)
      }
    }
    if (target.isEmpty() || value.isEmpty()) return null
    ctx.recordItem("Transform", WffElementStatus.DRAWN)
    return WffTransform(
      key = ctx.nextTransformKey(target),
      target = target,
      valueExpr = ctx.compileTracked("Transform($target)", value),
      animation = anim,
    )
  }

  private fun parseAnimationSpec(parser: XmlPullParser): WffAnimationSpec {
    val duration = attrFloat(parser, "duration", 0.5f).coerceAtLeast(0.01f)
    val interp = when (parser.getAttributeValue(null, "interpolation")?.trim()?.uppercase(Locale.US)) {
      "EASE_IN" -> WffInterpolation.EASE_IN
      "EASE_OUT" -> WffInterpolation.EASE_OUT
      "EASE_IN_OUT" -> WffInterpolation.EASE_IN_OUT
      "OVERSHOOT" -> WffInterpolation.OVERSHOOT
      "CUBIC_BEZIER" -> WffInterpolation.CUBIC_BEZIER
      else -> WffInterpolation.LINEAR
    }
    val controlsRaw = parser.getAttributeValue(null, "controls")?.trim().orEmpty()
    val controls = controlsRaw.split(" ", ",").mapNotNull { it.trim().toFloatOrNull() }
    val ctrlArray = if (controls.size >= 4) controls.take(4).toFloatArray() else floatArrayOf(0.42f, 0f, 0.58f, 1f)
    val repeat = attrInt(parser, "repeat", 0)
    val angleDir = when (parser.getAttributeValue(null, "angleDirection")?.trim()?.uppercase(Locale.US)) {
      "CLOCKWISE" -> WffAngleDirection.CLOCKWISE
      "COUNTER_CLOCKWISE" -> WffAngleDirection.COUNTER_CLOCKWISE
      else -> WffAngleDirection.NONE
    }
    val fps = attrInt(parser, "fps", 15).coerceIn(1, 60)
    val fromVal = parser.getAttributeValue(null, "from")?.trim()?.toFloatOrNull()
    return WffAnimationSpec(
      durationSec = duration,
      interpolation = interp,
      controls = ctrlArray,
      repeat = repeat,
      angleDirection = angleDir,
      fps = fps,
      fromValue = fromVal,
    )
  }

  fun parseVariant(parser: XmlPullParser, ctx: WffParseContext): WffVariant? {
    val mode = parser.getAttributeValue(null, "mode")?.trim().orEmpty()
    val target = parser.getAttributeValue(null, "target")?.trim().orEmpty()
    val value = parser.getAttributeValue(null, "value")?.trim().orEmpty()
    skipSubtree(parser)
    if (mode.isEmpty() || target.isEmpty()) return null
    ctx.recordItem("Variant", WffElementStatus.DRAWN)
    return WffVariant(mode = mode, target = target, value = value)
  }

  fun parseUserConfigurations(parser: XmlPullParser, ctx: WffParseContext) {
    ctx.recordItem("UserConfigurations", WffElementStatus.DRAWN)
    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      parseSingleUserConfig(parser, ctx)
    }
  }

  fun parseSingleUserConfig(parser: XmlPullParser, ctx: WffParseContext) {
    when (parser.name) {
      "ColorConfiguration" -> {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val rawDisplay = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { id }
        val displayName = ctx.resolveString(rawDisplay)
        val defaultValue = parser.getAttributeValue(null, "defaultValue")?.trim().orEmpty()
        val options = mutableListOf<WffColorOption>()
        while (parser.next() != XmlPullParser.END_TAG) {
          if (parser.eventType != XmlPullParser.START_TAG) continue
          if (parser.name == "ColorOption") {
            val optId = parser.getAttributeValue(null, "id")?.trim().orEmpty()
            val rawOptName = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { optId }
            val optName = ctx.resolveString(rawOptName)
            val rawColors = parser.getAttributeValue(null, "colors")?.trim().orEmpty()
            val colors = rawColors.split(Regex("[\\s,]+")).mapNotNull { parseColorNullable(it) }
            if (optId.isNotEmpty() && colors.isNotEmpty()) {
              options += WffColorOption(optId, optName, colors)
            }
          }
          skipSubtree(parser)
        }
        if (id.isNotEmpty() && options.isNotEmpty()) {
          ctx.userConfigs += WffUserConfig.ColorConfig(id, displayName, defaultValue.ifEmpty { options.first().id }, options)
          ctx.recordItem("ColorConfiguration", WffElementStatus.DRAWN)
        }
      }
      "ListConfiguration" -> {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val rawDisplay = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { id }
        val displayName = ctx.resolveString(rawDisplay)
        val defaultValue = parser.getAttributeValue(null, "defaultValue")?.trim().orEmpty()
        val options = mutableListOf<WffListOption>()
        while (parser.next() != XmlPullParser.END_TAG) {
          if (parser.eventType != XmlPullParser.START_TAG) continue
          if (parser.name == "ListOption") {
            val optId = parser.getAttributeValue(null, "id")?.trim().orEmpty()
            val rawOptName = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { optId }
            val optName = ctx.resolveString(rawOptName)
            if (optId.isNotEmpty()) options += WffListOption(optId, optName)
          }
          skipSubtree(parser)
        }
        if (id.isNotEmpty() && options.isNotEmpty()) {
          ctx.userConfigs += WffUserConfig.ListConfig(id, displayName, defaultValue.ifEmpty { options.first().id }, options)
          ctx.recordItem("ListConfiguration", WffElementStatus.DRAWN)
        }
      }
      "BooleanConfiguration" -> {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val rawDisplay = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { id }
        val displayName = ctx.resolveString(rawDisplay)
        val defRaw = parser.getAttributeValue(null, "defaultValue")?.trim().orEmpty()
        val defBool = defRaw.equals("TRUE", ignoreCase = true) || defRaw == "1"
        skipSubtree(parser)
        if (id.isNotEmpty()) {
          ctx.userConfigs += WffUserConfig.BooleanConfig(id, displayName, defBool)
          ctx.recordItem("BooleanConfiguration", WffElementStatus.DRAWN)
        }
      }
      "PhotosConfiguration" -> WffComplicationAndFlavorParser.parsePhotosConfiguration(parser, ctx)
      "Flavors" -> WffComplicationAndFlavorParser.parseFlavors(parser, ctx)
      else -> {
        ctx.recordItem(parser.name, WffElementStatus.SKIPPED, "Unsupported configuration type.")
        skipSubtree(parser)
      }
    }
  }

  fun parseBitmapFonts(parser: XmlPullParser, ctx: WffParseContext) {
    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      if (parser.name == "BitmapFont") {
        parseSingleBitmapFont(parser, ctx)
      } else {
        skipSubtree(parser)
      }
    }
  }

  fun parseSingleBitmapFont(parser: XmlPullParser, ctx: WffParseContext) {
    val name = parser.getAttributeValue(null, "name")?.trim().orEmpty()
    val glyphs = LinkedHashMap<String, WffBitmapGlyph>()
    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      if (parser.name == "Character" || parser.name == "Word") {
        val key = parser.getAttributeValue(null, "name")?.trim().orEmpty()
        val res = parser.getAttributeValue(null, "resource")?.trim().orEmpty()
        val w = attrFloat(parser, "width", 24f)
        val h = attrFloat(parser, "height", 32f)
        if (key.isNotEmpty() && res.isNotEmpty()) {
          ctx.referencedResources += res
          glyphs[key] = WffBitmapGlyph(key, res, w, h)
        }
      }
      skipSubtree(parser)
    }
    if (name.isNotEmpty() && glyphs.isNotEmpty()) {
      ctx.bitmapFonts[name] = WffBitmapFont(name, glyphs)
      ctx.recordItem("BitmapFont", WffElementStatus.DRAWN)
    }
  }

  fun parseShadow(parser: XmlPullParser, ctx: WffParseContext): WffTextShadow {
    val color = parseColor(parser.getAttributeValue(null, "color"), 0xAA000000.toInt())
    val offsetX = attrFloat(parser, "offsetX", 2f)
    val offsetY = attrFloat(parser, "offsetY", 2f)
    val radius = attrFloat(parser, "radius", 3f).coerceAtLeast(0.5f)
    skipSubtree(parser)
    ctx.recordItem("Shadow", WffElementStatus.DRAWN)
    return WffTextShadow(color, offsetX, offsetY, radius)
  }

  fun parseOutline(parser: XmlPullParser, ctx: WffParseContext): WffTextOutline {
    val color = parseColor(parser.getAttributeValue(null, "color"), 0xFF000000.toInt())
    val width = attrFloat(parser, "width", 2f).coerceAtLeast(0.5f)
    skipSubtree(parser)
    ctx.recordItem("Outline", WffElementStatus.DRAWN)
    return WffTextOutline(color, width)
  }

  fun parseColorAndExpr(
    raw: String?,
    fallback: Int,
    owner: String,
    ctx: WffParseContext,
  ): Pair<Int, CompiledWffExpr?> {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.contains('[') || trimmed.contains('(')) {
      return fallback to ctx.compileTracked(owner, trimmed)
    }
    return parseColor(trimmed, fallback) to null
  }

  fun parseBlendMode(raw: String?): WffBlendMode = when (raw?.trim()?.uppercase(Locale.US)) {
    "MULTIPLY" -> WffBlendMode.MULTIPLY
    "SCREEN" -> WffBlendMode.SCREEN
    "ADD", "PLUS" -> WffBlendMode.ADD
    "OVERLAY" -> WffBlendMode.OVERLAY
    "DARKEN" -> WffBlendMode.DARKEN
    "LIGHTEN" -> WffBlendMode.LIGHTEN
    "SRC_IN" -> WffBlendMode.SRC_IN
    "SRC_ATOP" -> WffBlendMode.SRC_ATOP
    "DST_IN" -> WffBlendMode.DST_IN
    "DST_OUT" -> WffBlendMode.DST_OUT
    "DST_OVER" -> WffBlendMode.DST_OVER
    "XOR" -> WffBlendMode.XOR
    else -> WffBlendMode.SRC_OVER
  }

  fun parseColor(raw: String?, fallback: Int): Int =
    parseColorNullable(raw) ?: fallback

  fun parseColorNullable(raw: String?): Int? {
    val clean = raw?.trim()?.removePrefix("#") ?: return null
    if (clean.length != 6 && clean.length != 8) return null
    val parsed = clean.toLongOrNull(16) ?: return null
    return if (clean.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
  }

  fun attrFloat(parser: XmlPullParser, name: String, default: Float): Float =
    parser.getAttributeValue(null, name)?.trim()?.toFloatOrNull() ?: default

  fun attrInt(parser: XmlPullParser, name: String, default: Int): Int =
    parser.getAttributeValue(null, name)?.trim()?.toIntOrNull()
      ?: parser.getAttributeValue(null, name)?.trim()?.toFloatOrNull()?.toInt()
      ?: default

  fun parseTextAlign(raw: String?): WffTextAlign = when (raw?.trim()?.uppercase(Locale.US)) {
    "START", "LEFT" -> WffTextAlign.START
    "END", "RIGHT" -> WffTextAlign.END
    else -> WffTextAlign.CENTER
  }

  fun parseCap(raw: String?): Paint.Cap = when (raw?.trim()?.uppercase(Locale.US)) {
    "BUTT", "FLAT" -> Paint.Cap.BUTT
    "SQUARE" -> Paint.Cap.SQUARE
    else -> Paint.Cap.ROUND
  }

  fun skipSubtree(parser: XmlPullParser) {
    var depth = 1
    while (depth > 0) {
      when (parser.next()) {
        XmlPullParser.START_TAG -> depth++
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.END_DOCUMENT -> return
      }
    }
  }
}

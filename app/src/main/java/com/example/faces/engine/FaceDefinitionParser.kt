package com.example.faces.engine

import androidx.compose.ui.graphics.StrokeCap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses and validates JSON watch face definitions using Android's built-in org.json.
 * Never throws: malformed JSON or invalid layer values return [ParseResult.Error] with a clear reason.
 */
internal object FaceDefinitionParser {

  sealed interface ParseResult {
    data class Success(val definition: FaceDefinition) : ParseResult
    data class Error(val reason: String) : ParseResult
  }

  private val validTokens = setOf("primary", "secondary", "onSurface", "surface", "error")

  /** Parses raw JSON text into a [FaceDefinition] or returns a descriptive validation error. */
  fun parse(jsonText: String): ParseResult {
    val root = try {
      JSONObject(jsonText)
    } catch (e: Exception) {
      return ParseResult.Error("Invalid JSON: ${e.message ?: "syntax error"}")
    }

    val id = root.optString("id", "").trim()
    if (id.isEmpty()) return ParseResult.Error("Missing or empty 'id'")

    val name = root.optString("name", "").trim()
    if (name.isEmpty()) return ParseResult.Error("Missing or empty 'name'")

    val canvasRaw = root.optString("canvas", "circle").trim()
    val canvas = when (canvasRaw) {
      "circle" -> CanvasShape.CIRCLE
      "roundedRect" -> CanvasShape.ROUNDED_RECT
      else -> return ParseResult.Error("Unsupported canvas '$canvasRaw' (expected circle or roundedRect)")
    }

    val layersArr: JSONArray = root.optJSONArray("layers")
      ?: return ParseResult.Error("Missing 'layers' array")
    if (layersArr.length() == 0) return ParseResult.Error("'layers' array must not be empty")

    val parsedLayers = ArrayList<FaceLayer>(layersArr.length())
    for (i in 0 until layersArr.length()) {
      val layerObj = layersArr.optJSONObject(i)
        ?: return ParseResult.Error("Layer at index $i is not a JSON object")
      when (val res = parseLayer(layerObj, i)) {
        is LayerResult.Ok -> parsedLayers += res.layer
        is LayerResult.Fail -> return ParseResult.Error(res.reason)
      }
    }

    return ParseResult.Success(
      FaceDefinition(
        id = id,
        name = name,
        canvas = canvas,
        layers = parsedLayers,
      ),
    )
  }

  private sealed interface LayerResult {
    data class Ok(val layer: FaceLayer) : LayerResult
    data class Fail(val reason: String) : LayerResult
  }

  private fun parseLayer(obj: JSONObject, index: Int): LayerResult {
    val id = obj.optString("id", "layer_$index").trim().ifEmpty { "layer_$index" }
    val type = obj.optString("type", "").trim()
    if (type.isEmpty()) return LayerResult.Fail("Layer '$id' ($index) missing 'type'")

    val alpha = obj.optDouble("alpha", 1.0).toFloat()
    if (!alpha.isFinite() || alpha < 0f || alpha > 1f) {
      return LayerResult.Fail("Layer '$id' invalid alpha=$alpha")
    }

    val mode = when (val m = obj.optString("mode", "both").trim()) {
      "normal" -> LayerMode.NORMAL
      "ambient" -> LayerMode.AMBIENT
      "both" -> LayerMode.BOTH
      else -> return LayerResult.Fail("Layer '$id' invalid mode '$m'")
    }

    val showWhen = if (obj.has("showWhen") && !obj.isNull("showWhen")) {
      parseCondition(obj.optString("showWhen", "").trim(), id)
        ?: return LayerResult.Fail("Layer '$id' invalid showWhen '${obj.optString("showWhen")}'")
    } else {
      null
    }

    return when (type) {
      "image" -> {
        val drawable = obj.optString("drawable", "").trim()
        if (drawable.isEmpty()) return LayerResult.Fail("Image layer '$id' missing 'drawable'")
        LayerResult.Ok(ImageLayer(id = id, alpha = alpha, mode = mode, showWhen = showWhen, drawableName = drawable))
      }

      "shape" -> parseShapeLayer(obj, id, alpha, mode, showWhen)
      "ticks" -> parseTicksLayer(obj, id, alpha, mode, showWhen)
      "hand" -> parseHandLayer(obj, id, alpha, mode, showWhen)
      "text" -> parseTextLayer(obj, id, alpha, mode, showWhen)
      "gauge" -> parseGaugeLayer(obj, id, alpha, mode, showWhen)
      "slot" -> parseSlotLayer(obj, id, alpha, mode, showWhen)
      else -> LayerResult.Fail("Layer '$id' unknown type '$type'")
    }
  }

  private fun parseShapeLayer(
    obj: JSONObject,
    id: String,
    alpha: Float,
    mode: LayerMode,
    showWhen: ShowCondition?,
  ): LayerResult {
    val kind = when (val k = obj.optString("shape", "").trim()) {
      "circle" -> ShapeKind.CIRCLE
      "ring" -> ShapeKind.RING
      "rect" -> ShapeKind.RECT
      "roundedRect" -> ShapeKind.ROUNDED_RECT
      else -> return LayerResult.Fail("Shape layer '$id' invalid shape '$k'")
    }
    val fillColor = obj.optNullableString("fillColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Shape layer '$id' invalid fillColor '$it'")
    }
    val strokeColor = obj.optNullableString("strokeColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Shape layer '$id' invalid strokeColor '$it'")
    }
    if (fillColor == null && strokeColor == null) {
      return LayerResult.Fail("Shape layer '$id' requires fillColor or strokeColor")
    }
    return LayerResult.Ok(
      ShapeLayer(
        id = id,
        alpha = alpha,
        mode = mode,
        showWhen = showWhen,
        kind = kind,
        x = obj.optDouble("x", 0.0).toFloat(),
        y = obj.optDouble("y", 0.0).toFloat(),
        width = obj.optNullableFloat("width"),
        height = obj.optNullableFloat("height"),
        radius = obj.optNullableFloat("radius"),
        radiusDp = obj.optNullableFloat("radiusDp"),
        cornerRadius = obj.optDouble("cornerRadius", 0.08).toFloat(),
        fillColor = fillColor,
        strokeColor = strokeColor,
        strokeWidthDp = obj.optDouble("strokeWidthDp", if (kind == ShapeKind.RING) 2.0 else 0.0).toFloat(),
      ),
    )
  }

  private fun parseTicksLayer(
    obj: JSONObject,
    id: String,
    alpha: Float,
    mode: LayerMode,
    showWhen: ShowCondition?,
  ): LayerResult {
    val count = obj.optInt("count", 0)
    if (count <= 0 || count > 360) {
      return LayerResult.Fail("Ticks layer '$id' invalid count=$count")
    }
    val colorStr = obj.optString("color", "").trim()
    val color = parseColor(colorStr)
      ?: return LayerResult.Fail("Ticks layer '$id' invalid color '$colorStr'")
    val majorColor = obj.optNullableString("majorColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Ticks layer '$id' invalid majorColor '$it'")
    }
    val cap = parseCap(obj.optString("cap", "round"))
      ?: return LayerResult.Fail("Ticks layer '$id' invalid cap")
    return LayerResult.Ok(
      TicksLayer(
        id = id,
        alpha = alpha,
        mode = mode,
        showWhen = showWhen,
        count = count,
        innerRadius = obj.optDouble("innerRadius", 0.85).toFloat(),
        outerRadius = obj.optDouble("outerRadius", 0.94).toFloat(),
        widthDp = obj.optDouble("widthDp", 1.5).toFloat(),
        color = color,
        cap = cap,
        majorEvery = obj.optInt("majorEvery", 0).coerceAtLeast(0),
        majorInnerRadius = obj.optNullableFloat("majorInnerRadius"),
        majorOuterRadius = obj.optNullableFloat("majorOuterRadius"),
        majorWidthDp = obj.optNullableFloat("majorWidthDp"),
        majorColor = majorColor,
      ),
    )
  }

  private fun parseHandLayer(
    obj: JSONObject,
    id: String,
    alpha: Float,
    mode: LayerMode,
    showWhen: ShowCondition?,
  ): LayerResult {
    val source = when (val s = obj.optString("source", "").trim()) {
      "hour" -> HandSource.HOUR
      "minute" -> HandSource.MINUTE
      "minuteStep" -> HandSource.MINUTE_STEP
      "second" -> HandSource.SECOND
      "battery" -> HandSource.BATTERY
      "timerProgress" -> HandSource.TIMER_PROGRESS
      "stopwatchSeconds" -> HandSource.STOPWATCH_SECONDS
      "stopwatchMinutes" -> HandSource.STOPWATCH_MINUTES
      else -> return LayerResult.Fail("Hand layer '$id' invalid source '$s'")
    }
    if (!obj.has("length")) return LayerResult.Fail("Hand layer '$id' missing 'length'")
    val length = obj.optDouble("length", -1.0).toFloat()
    if (!length.isFinite() || length <= 0f) {
      return LayerResult.Fail("Hand layer '$id' invalid length=$length")
    }
    val bodyStr = obj.optString("bodyColor", "").trim()
    val bodyColor = parseColor(bodyStr)
      ?: return LayerResult.Fail("Hand layer '$id' invalid bodyColor '$bodyStr'")
    val stripeColor = obj.optNullableString("stripeColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Hand layer '$id' invalid stripeColor '$it'")
    }
    val cap = parseCap(obj.optString("cap", "round"))
      ?: return LayerResult.Fail("Hand layer '$id' invalid cap")

    val cwObj = obj.optJSONObject("counterweight")
    val counterweight = if (cwObj != null) {
      val cwRingStr = cwObj.optString("ringColor", "").trim()
      val cwRing = parseColor(cwRingStr)
        ?: return LayerResult.Fail("Hand layer '$id' invalid counterweight ringColor '$cwRingStr'")
      val cwCenterStr = cwObj.optString("centerColor", "").trim()
      val cwCenter = parseColor(cwCenterStr)
        ?: return LayerResult.Fail("Hand layer '$id' invalid counterweight centerColor '$cwCenterStr'")
      val cwRadius = cwObj.optDouble("radiusDp", -1.0).toFloat()
      if (!cwRadius.isFinite() || cwRadius <= 0f) {
        return LayerResult.Fail("Hand layer '$id' invalid counterweight radiusDp")
      }
      HandCounterweight(
        radiusDp = cwRadius,
        ringColor = cwRing,
        centerColor = cwCenter,
        positionRatio = cwObj.optNullableFloat("positionRatio"),
        tailOffsetDp = cwObj.optNullableFloat("tailOffsetDp"),
      )
    } else {
      null
    }

    return LayerResult.Ok(
      HandLayer(
        id = id,
        alpha = alpha,
        mode = mode,
        showWhen = showWhen,
        source = source,
        pivotX = obj.optDouble("pivotX", 0.0).toFloat(),
        pivotY = obj.optDouble("pivotY", 0.0).toFloat(),
        length = length,
        tail = obj.optNullableFloat("tail"),
        tailDp = obj.optNullableFloat("tailDp"),
        widthDp = obj.optDouble("widthDp", 3.0).toFloat(),
        cap = cap,
        bodyColor = bodyColor,
        stripeColor = stripeColor,
        stripeWidthDp = obj.optDouble("stripeWidthDp", 0.0).toFloat(),
        stripeTipOnly = obj.optBoolean("stripeTipOnly", false),
        shadow = obj.optBoolean("shadow", true),
        shadowAlpha = obj.optDouble("shadowAlpha", 0.45).toFloat(),
        smooth = obj.optBoolean("smooth", false),
        minValue = obj.optDouble("minValue", 0.0).toFloat(),
        maxValue = obj.optDouble("maxValue", 100.0).toFloat(),
        startAngle = obj.optDouble("startAngle", 0.0).toFloat(),
        endAngle = obj.optDouble("endAngle", 360.0).toFloat(),
        counterweight = counterweight,
        centerDotRadiusDp = obj.optDouble("centerDotRadiusDp", 0.0).toFloat(),
      ),
    )
  }

  private fun parseTextLayer(
    obj: JSONObject,
    id: String,
    alpha: Float,
    mode: LayerMode,
    showWhen: ShowCondition?,
  ): LayerResult {
    if (!obj.has("template")) return LayerResult.Fail("Text layer '$id' missing 'template'")
    val template = obj.optString("template", "")
    val size = obj.optDouble("size", -1.0).toFloat()
    if (!size.isFinite() || size <= 0f) return LayerResult.Fail("Text layer '$id' invalid size")
    val colorStr = obj.optString("color", "").trim()
    val color = parseColor(colorStr)
      ?: return LayerResult.Fail("Text layer '$id' invalid color '$colorStr'")
    val weight = parseWeight(obj.optString("weight", "normal"))
      ?: return LayerResult.Fail("Text layer '$id' invalid weight")
    val align = parseAlign(obj.optString("align", "center"))
      ?: return LayerResult.Fail("Text layer '$id' invalid align")
    val font = parseFont(obj.optString("font", "default"))
      ?: return LayerResult.Fail("Text layer '$id' invalid font")
    return LayerResult.Ok(
      TextLayer(
        id = id,
        alpha = alpha,
        mode = mode,
        showWhen = showWhen,
        template = template,
        x = obj.optDouble("x", 0.0).toFloat(),
        y = obj.optDouble("y", 0.0).toFloat(),
        size = size,
        weight = weight,
        align = align,
        color = color,
        letterSpacing = obj.optDouble("letterSpacing", 0.0).toFloat(),
        font = font,
      ),
    )
  }

  private fun parseGaugeLayer(
    obj: JSONObject,
    id: String,
    alpha: Float,
    mode: LayerMode,
    showWhen: ShowCondition?,
  ): LayerResult {
    val source = when (val s = obj.optString("source", "").trim()) {
      "battery" -> GaugeSource.BATTERY
      "timerProgress" -> GaugeSource.TIMER_PROGRESS
      "secondsProgress" -> GaugeSource.SECONDS_PROGRESS
      "minutesProgress" -> GaugeSource.MINUTES_PROGRESS
      else -> return LayerResult.Fail("Gauge layer '$id' invalid source '$s'")
    }
    val radius = obj.optDouble("radius", -1.0).toFloat()
    if (!radius.isFinite() || radius <= 0f) {
      return LayerResult.Fail("Gauge layer '$id' invalid radius")
    }
    val fillStr = obj.optString("fillColor", "").trim()
    val fillColor = parseColor(fillStr)
      ?: return LayerResult.Fail("Gauge layer '$id' invalid fillColor '$fillStr'")
    val trackColor = obj.optNullableString("trackColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Gauge layer '$id' invalid trackColor '$it'")
    }
    val cap = parseCap(obj.optString("cap", "round"))
      ?: return LayerResult.Fail("Gauge layer '$id' invalid cap")

    return LayerResult.Ok(
      GaugeLayer(
        id = id,
        alpha = alpha,
        mode = mode,
        showWhen = showWhen,
        source = source,
        x = obj.optDouble("x", 0.0).toFloat(),
        y = obj.optDouble("y", 0.0).toFloat(),
        radius = radius,
        startAngle = obj.optDouble("startAngle", -90.0).toFloat(),
        sweepAngle = obj.optDouble("sweepAngle", 360.0).toFloat(),
        thicknessDp = obj.optDouble("thicknessDp", 6.0).toFloat(),
        cap = cap,
        trackColor = trackColor,
        fillColor = fillColor,
      ),
    )
  }

  private fun parseSlotLayer(
    obj: JSONObject,
    id: String,
    alpha: Float,
    mode: LayerMode,
    showWhen: ShowCondition?,
  ): LayerResult {
    if (!obj.has("template")) return LayerResult.Fail("Slot layer '$id' missing 'template'")
    val template = obj.optString("template", "")
    val width = obj.optDouble("width", -1.0).toFloat()
    val height = obj.optDouble("height", -1.0).toFloat()
    if (!width.isFinite() || width <= 0f || !height.isFinite() || height <= 0f) {
      return LayerResult.Fail("Slot layer '$id' requires positive width and height")
    }
    val textSize = obj.optDouble("textSize", (height * 0.65).coerceAtLeast(0.04)).toFloat()
    val textColorStr = obj.optString("textColor", "@onSurface").trim()
    val textColor = parseColor(textColorStr)
      ?: return LayerResult.Fail("Slot layer '$id' invalid textColor '$textColorStr'")
    val fillColor = obj.optNullableString("fillColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Slot layer '$id' invalid fillColor '$it'")
    }
    val strokeColor = obj.optNullableString("strokeColor")?.let {
      parseColor(it) ?: return LayerResult.Fail("Slot layer '$id' invalid strokeColor '$it'")
    }
    val weight = parseWeight(obj.optString("weight", "bold"))
      ?: return LayerResult.Fail("Slot layer '$id' invalid weight")
    val align = parseAlign(obj.optString("align", "center"))
      ?: return LayerResult.Fail("Slot layer '$id' invalid align")
    val font = parseFont(obj.optString("font", "mono"))
      ?: return LayerResult.Fail("Slot layer '$id' invalid font")

    return LayerResult.Ok(
      SlotLayer(
        id = id,
        alpha = alpha,
        mode = mode,
        showWhen = showWhen,
        template = template,
        x = obj.optDouble("x", 0.0).toFloat(),
        y = obj.optDouble("y", 0.0).toFloat(),
        width = width,
        height = height,
        cornerRadius = obj.optDouble("cornerRadius", 0.04).toFloat(),
        fillColor = fillColor,
        strokeColor = strokeColor,
        strokeWidthDp = obj.optDouble("strokeWidthDp", 1.0).toFloat(),
        textSize = textSize,
        textColor = textColor,
        weight = weight,
        align = align,
        font = font,
        letterSpacing = obj.optDouble("letterSpacing", 0.0).toFloat(),
      ),
    )
  }

  /** Parses a hex colour (#RRGGBB or #AARRGGBB) or a theme token (@primary, etc.). */
  internal fun parseColor(raw: String): ColorSpec? {
    if (raw.isEmpty()) return null
    if (raw.startsWith("@")) {
      val token = raw.substring(1)
      return if (token in validTokens) ColorSpec.Token(token) else null
    }
    if (!raw.startsWith("#")) return null
    val hex = raw.substring(1)
    return try {
      when (hex.length) {
        6 -> ColorSpec.Hex((0xFF000000L or hex.toLong(16)).toInt())
        8 -> ColorSpec.Hex(hex.toLong(16).toInt())
        else -> null
      }
    } catch (_: NumberFormatException) {
      null
    }
  }

  private fun parseCondition(raw: String, layerId: String): ShowCondition? = when {
    raw == "timerRunning" -> ShowCondition.TimerRunning
    raw == "hasNextEvent" -> ShowCondition.HasNextEvent
    raw == "charging" -> ShowCondition.Charging
    raw == "isAmbient" -> ShowCondition.IsAmbient
    raw.startsWith("batteryBelow:") -> {
      val pct = raw.substringAfter("batteryBelow:").toIntOrNull()
      if (pct != null && pct in 1..100) ShowCondition.BatteryBelow(pct) else null
    }
    else -> null
  }

  private fun parseCap(raw: String): StrokeCap? = when (raw.trim().lowercase()) {
    "round" -> StrokeCap.Round
    "square" -> StrokeCap.Square
    "butt" -> StrokeCap.Butt
    else -> null
  }

  private fun parseWeight(raw: String): TextWeight? = when (raw.trim().lowercase()) {
    "normal" -> TextWeight.NORMAL
    "medium" -> TextWeight.MEDIUM
    "bold" -> TextWeight.BOLD
    else -> null
  }

  private fun parseAlign(raw: String): TextAlign? = when (raw.trim().lowercase()) {
    "left" -> TextAlign.LEFT
    "center" -> TextAlign.CENTER
    "right" -> TextAlign.RIGHT
    else -> null
  }

  private fun parseFont(raw: String): TextFont? = when (raw.trim().lowercase()) {
    "default" -> TextFont.DEFAULT
    "sans", "space_grotesk" -> TextFont.SANS
    "mono", "jetbrains_mono" -> TextFont.MONO
    else -> null
  }

  private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().ifEmpty { null }
  }

  private fun JSONObject.optNullableFloat(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN).toFloat()
    return if (value.isFinite()) value else null
  }
}

package com.example.faces.wff

import android.util.LruCache
import com.example.core.FaceData
import com.example.core.FaceDataNeed
import com.example.core.FaceRenderMode
import com.example.core.FaceUpdatePolicy
import java.util.Locale

/**
 * Dependency bitmask flags, expression compiler + bounded LRU cache, dependency-keyed evaluation
 * cache slots, and update cadence analyzer for Watch Face Format expressions.
 */
internal object WffExpression {

  const val DEP_NONE = 0
  const val DEP_MILLIS = 1 shl 0
  const val DEP_SECOND = 1 shl 1
  const val DEP_MINUTE = 1 shl 2
  const val DEP_HOUR = 1 shl 3
  const val DEP_DAY = 1 shl 4
  const val DEP_BATTERY = 1 shl 5
  const val DEP_CONFIG = 1 shl 6
  const val DEP_ALWAYS = 1 shl 7
  const val DEP_SENSORS = 1 shl 8
  const val DEP_COMPLICATION = 1 shl 9

  private val knownSources = setOf(
    "HOUR_0_11", "HOUR_0_11_Z", "HOUR_1_12", "HOUR_1_12_Z",
    "HOUR_0_23", "HOUR_0_23_Z", "HOUR_1_24", "HOUR_1_24_Z", "HOUR",
    "HOUR_0_11_TENS_DIGIT", "HOUR_0_11_UNITS_DIGIT",
    "HOUR_0_23_TENS_DIGIT", "HOUR_0_23_UNITS_DIGIT",
    "HOUR_0_11_MINUTE", "HOUR_0_23_MINUTE",
    "MINUTE", "MINUTE_Z", "MINUTE_SECOND", "MINUTE_TENS_DIGIT", "MINUTE_UNITS_DIGIT",
    "SECOND", "SECOND_Z", "SECOND_MILLISECOND", "MILLISECOND",
    "SECOND_TENS_DIGIT", "SECOND_UNITS_DIGIT",
    "AMPM_STATE", "AMPM_POSITION", "AMPM_STRING",
    "DAY", "DAY_Z", "DAY_OF_YEAR", "DAY_HOUR", "DAYS_IN_MONTH",
    "MONTH", "MONTH_Z", "MONTH_0_11", "MONTH_S", "MONTH_F",
    "YEAR", "YEAR_S",
    "DAY_OF_WEEK", "DAY_OF_WEEK_S", "DAY_OF_WEEK_F", "FIRST_DAY_OF_WEEK",
    "IS_24_HOUR_MODE", "SECONDS_IN_DAY", "UTC_TIMESTAMP",
    "TIMEZONE", "TIMEZONE_ABB", "TIMEZONE_OFFSET", "TIMEZONE_OFFSET_DST", "IS_DAYLIGHT_SAVING_TIME",
    "BATTERY_PERCENT", "BATTERY_CHARGING_STATUS", "IS_BATTERY_CHARGING", "BATTERY_IS_LOW",
    "BATTERY_TEMPERATURE_CELSIUS", "BATTERY_TEMPERATURE_FAHRENHEIT",
    "STEP_COUNT", "STEP_GOAL", "STEP_PERCENT",
    "HEART_RATE", "HEART_RATE_Z", "HEART_RATE_IS_AVAILABLE",
    "LIGHT_LEVEL", "ILLUMINANCE",
    "AMBIENT_PRESSURE", "PRESSURE", "PRESSURE_HPA",
    "ACCELEROMETER_IS_SUPPORTED", "ACCELEROMETER_X", "ACCELEROMETER_Y", "ACCELEROMETER_Z",
    "ACCELEROMETER_ANGLE_X", "ACCELEROMETER_ANGLE_Y", "ACCELEROMETER_ANGLE_Z", "ACCELEROMETER_ANGLE_XY",
    "UNREAD_NOTIFICATION_COUNT",
  )

  private val knownFunctions = setOf(
    "ifelse", "if", "icutext", "icubesttext",
    "round", "floor", "ceil", "fract", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
    "abs", "clamp", "min", "max", "pow", "sqrt", "cbrt", "log", "log2", "log10", "log1p",
    "exp", "expm1", "hypot", "rad", "deg", "mod", "rand", "random", "sign",
    "subtext", "substring", "textlength", "strlen", "strindexof", "indexof",
    "tolowercase", "touppercase", "trim", "concat", "numberformat", "format",
    "toint", "int", "tofloat", "float", "tostring", "str", "toboolean", "bool",
    "rgb", "argb",
  )

  // Bounded LRU cache of compiled expression ASTs keyed by raw expression text.
  private val cache = object : LruCache<String, CompiledWffExpr>(512) {}

  /** Compiles [raw] into a cached [CompiledWffExpr], tracking dependencies and unknown tokens. */
  fun compile(raw: String): CompiledWffExpr {
    val key = raw.trim()
    if (key.isEmpty()) {
      return CompiledWffExpr("", WffExpr.ConstNum(0.0), DEP_NONE)
    }
    synchronized(cache) {
      cache.get(key)?.let { return it }
    }
    val ast = runCatching { Parser(key).parse() }.getOrElse { WffExpr.ConstNum(0.0) }
    val unknownSources = LinkedHashSet<String>()
    val unknownFuncs = LinkedHashSet<String>()
    val mask = inspectAst(ast, unknownSources, unknownFuncs)
    val compiled = CompiledWffExpr(
      raw = key,
      ast = ast,
      inputMask = mask,
      unknownSources = unknownSources.toList(),
      unknownFunctions = unknownFuncs.toList(),
    )
    synchronized(cache) {
      cache.put(key, compiled)
    }
    return compiled
  }

  /** Evaluates [expr] directly using [ctx], returning a [WffValue]. */
  fun evaluate(expr: CompiledWffExpr, ctx: WffEvalContext): WffValue =
    WffExpressionEvaluator.evalAst(expr.ast, ctx)

  /** Evaluates [expr] only when its declared dependencies have changed since [slot] was updated. */
  fun evaluateCached(
    slot: EvalCacheSlot,
    expr: CompiledWffExpr,
    ctx: WffEvalContext,
  ): WffValue {
    if (!slot.validFor(expr, ctx)) {
      slot.value = WffExpressionEvaluator.evalAst(expr.ast, ctx)
      slot.captureVersions(expr, ctx)
    }
    return slot.value
  }

  /** Convenience helper evaluating a compiled expression into a Float without external slot state. */
  fun evaluateFloat(expr: CompiledWffExpr, data: FaceData, configCtx: WffConfigRuntime? = null): Float {
    val ctx = WffEvalContext(data = data, configRuntime = configCtx)
    return WffExpressionEvaluator.evalAst(expr.ast, ctx).asDouble().toFloat()
  }

  /** Evaluates a compiled expression into a boolean predicate. */
  fun evaluateBool(expr: CompiledWffExpr, ctx: WffEvalContext): Boolean =
    WffExpressionEvaluator.evalAst(expr.ast, ctx).asBoolean()

  /** Appends a WFF PartText Template + Parameter list into [out] without unnecessary allocations. */
  fun formatTemplate(
    template: String,
    parameters: List<CompiledWffExpr>,
    ctx: WffEvalContext,
    out: StringBuilder,
  ) = WffExpressionEvaluator.formatTemplate(template, parameters, ctx, out)

  /** Formats a WFF TimeText pattern into [out], stripping seconds in ambient mode. */
  fun formatTimeText(
    format: String,
    hourFormat: String,
    data: FaceData,
    isAmbient: Boolean,
    out: StringBuilder,
  ) = WffExpressionEvaluator.formatTimeText(format, hourFormat, data, isAmbient, out)

  /** Collects the minimal set of [FaceDataNeed]s referenced across [face] and its active complication providers. */
  fun scanDataNeeds(
    face: WffWatchFace,
    complicationProviders: Map<String, AuraComplicationProvider> = emptyMap(),
  ): Set<FaceDataNeed> {
    val needs = HashSet<FaceDataNeed>(4)
    needs += FaceDataNeed.TIME
    var mask = DEP_NONE
    val sourceTokens = HashSet<String>(face.usedSources)

    fun scanExpr(expr: CompiledWffExpr?) {
      if (expr == null) return
      mask = mask or expr.inputMask
      collectAstSources(expr.ast, sourceTokens)
    }

    scanExpr(face.backgroundColorExpr)
    face.sceneTransforms.forEach { scanExpr(it.valueExpr) }

    var slotIdx = 0
    fun scanNode(node: WffSceneNode) {
      node.transforms.forEach { scanExpr(it.valueExpr) }
      when (node) {
        is WffSceneNode.Group -> node.children.forEach(::scanNode)
        is WffSceneNode.Condition -> {
          node.branches.forEach { b ->
            scanExpr(b.expression)
            b.children.forEach(::scanNode)
          }
          node.defaultChildren.forEach(::scanNode)
        }
        is WffSceneNode.AnalogClock -> {
          fun scanHand(h: WffHandNode) {
            scanExpr(h.tintColorExpr)
            h.transforms.forEach { scanExpr(it.valueExpr) }
          }
          node.hourHands.forEach(::scanHand)
          node.minuteHands.forEach(::scanHand)
          node.secondHands.forEach(::scanHand)
        }
        is WffSceneNode.DigitalClock -> node.timeTexts.forEach { tt ->
          scanExpr(tt.colorExpr)
          tt.transforms.forEach { scanExpr(it.valueExpr) }
        }
        is WffSceneNode.PartImage -> {
          scanExpr(node.tintColorExpr)
          scanExpr(node.photos?.sourceExpr)
        }
        is WffSceneNode.ComplicationSlot -> {
          val provider = complicationProviders[node.slotId]
            ?: AuraComplicationProvider.fromPolicy(node.defaultProviderPolicy, node.supportedTypes, slotIdx)
          slotIdx++
          when (provider) {
            AuraComplicationProvider.STEPS -> needs += FaceDataNeed.STEPS
            AuraComplicationProvider.BATTERY -> needs += FaceDataNeed.BATTERY
            AuraComplicationProvider.NEXT_EVENT -> needs += FaceDataNeed.NEXT_EVENT
            AuraComplicationProvider.TIMER -> needs += FaceDataNeed.TIMER
            AuraComplicationProvider.STOPWATCH -> needs += FaceDataNeed.STOPWATCH
            AuraComplicationProvider.DATE,
            AuraComplicationProvider.DAY_OF_WEEK,
            AuraComplicationProvider.EMPTY -> Unit
          }
          node.templates.forEach { tpl -> tpl.children.forEach(::scanNode) }
        }
        is WffSceneNode.PartText -> {
          scanExpr(node.colorExpr)
          node.parameters.forEach(::scanExpr)
        }
        is WffSceneNode.PartDraw -> node.shapes.forEach { shape ->
          scanExpr(shape.fillColorExpr)
          scanExpr(shape.strokeColorExpr)
          if (shape is WffDrawShape.Arc) {
            shape.transforms.forEach { scanExpr(it.valueExpr) }
          }
        }
      }
    }

    face.nodes.forEach(::scanNode)
    if ((mask and DEP_BATTERY) != 0) needs += FaceDataNeed.BATTERY
    for (src in sourceTokens) {
      val up = src.uppercase(Locale.US)
      when {
        up in setOf("STEP_COUNT", "STEP_GOAL", "STEP_PERCENT") -> needs += FaceDataNeed.STEPS
        up.startsWith("HEART_RATE") -> needs += FaceDataNeed.HEART_RATE
        up == "LIGHT_LEVEL" || up == "ILLUMINANCE" -> needs += FaceDataNeed.LIGHT
        up == "AMBIENT_PRESSURE" || up == "PRESSURE" || up == "PRESSURE_HPA" -> needs += FaceDataNeed.PRESSURE
        up.startsWith("ACCELEROMETER_") -> needs += FaceDataNeed.ACCELEROMETER
      }
    }
    return needs
  }

  /**
   * Computes the lowest required [FaceUpdatePolicy] for [face] in [renderMode].
   * Faces with only Hour/Minute hands and static/minute text stay at EVERY_MINUTE.
   * Looping animations or sweeping seconds request SMOOTH_FRAME only when NORMAL & visible.
   */
  fun updatePolicy(
    face: WffWatchFace,
    renderMode: FaceRenderMode,
    hasActiveAnimation: Boolean = false,
    complicationProviders: Map<String, AuraComplicationProvider> = emptyMap(),
  ): FaceUpdatePolicy {
    if (renderMode == FaceRenderMode.AMBIENT) {
      return FaceUpdatePolicy.EVERY_MINUTE
    }
    if (hasActiveAnimation) {
      return FaceUpdatePolicy.SMOOTH_FRAME
    }
    var needsMillis = false
    var needsSecond = false
    var slotIdx = 0

    fun checkMask(mask: Int) {
      if ((mask and (DEP_MILLIS or DEP_ALWAYS)) != 0) needsMillis = true
      if ((mask and DEP_SECOND) != 0) needsSecond = true
    }

    face.backgroundColorExpr?.let { checkMask(it.inputMask) }
    face.sceneTransforms.forEach { tr ->
      checkMask(tr.valueExpr.inputMask)
      if (tr.animation != null && tr.animation.repeat != 0) needsMillis = true
    }

    fun walk(node: WffSceneNode) {
      node.transforms.forEach { tr ->
        checkMask(tr.valueExpr.inputMask)
        if (tr.animation != null && tr.animation.repeat != 0) needsMillis = true
      }
      when (node) {
        is WffSceneNode.Group -> node.children.forEach(::walk)
        is WffSceneNode.Condition -> {
          node.branches.forEach { b ->
            checkMask(b.expression.inputMask)
            b.children.forEach(::walk)
          }
          node.defaultChildren.forEach(::walk)
        }
        is WffSceneNode.AnalogClock -> {
          for (secHand in node.secondHands) {
            if (secHand.motionPolicy is WffMotionPolicy.Sweep) {
              needsMillis = true
            } else {
              needsSecond = true
            }
            secHand.transforms.forEach { tr ->
              checkMask(tr.valueExpr.inputMask)
              if (tr.animation != null && tr.animation.repeat != 0) needsMillis = true
            }
          }
          node.hourHands.forEach { h ->
            h.tintColorExpr?.let { checkMask(it.inputMask) }
            h.transforms.forEach { checkMask(it.valueExpr.inputMask) }
          }
          node.minuteHands.forEach { h ->
            h.tintColorExpr?.let { checkMask(it.inputMask) }
            h.transforms.forEach { checkMask(it.valueExpr.inputMask) }
          }
        }
        is WffSceneNode.DigitalClock -> node.timeTexts.forEach { tt ->
          if (tt.format.contains('s')) needsSecond = true
          tt.colorExpr?.let { checkMask(it.inputMask) }
          tt.transforms.forEach { checkMask(it.valueExpr.inputMask) }
        }
        is WffSceneNode.PartImage -> node.tintColorExpr?.let { checkMask(it.inputMask) }
        is WffSceneNode.ComplicationSlot -> {
          val provider = complicationProviders[node.slotId]
            ?: AuraComplicationProvider.fromPolicy(node.defaultProviderPolicy, node.supportedTypes, slotIdx)
          slotIdx++
          if (provider == AuraComplicationProvider.TIMER || provider == AuraComplicationProvider.STOPWATCH) {
            needsSecond = true
          }
          node.templates.forEach { tpl -> tpl.children.forEach(::walk) }
        }
        is WffSceneNode.PartText -> {
          node.colorExpr?.let { checkMask(it.inputMask) }
          node.parameters.forEach { checkMask(it.inputMask) }
        }
        is WffSceneNode.PartDraw -> node.shapes.forEach { s ->
          s.fillColorExpr?.let { checkMask(it.inputMask) }
          s.strokeColorExpr?.let { checkMask(it.inputMask) }
          if (s is WffDrawShape.Arc) s.transforms.forEach { checkMask(it.valueExpr.inputMask) }
        }
      }
    }

    face.nodes.forEach(::walk)
    return when {
      needsMillis -> FaceUpdatePolicy.SMOOTH_FRAME
      needsSecond -> FaceUpdatePolicy.EVERY_SECOND
      else -> FaceUpdatePolicy.EVERY_MINUTE
    }
  }

  /** Collects every `[SOURCE]` name referenced inside an AST [expr]. */
  fun collectAstSources(expr: WffExpr, out: MutableSet<String>) {
    when (expr) {
      is WffExpr.Source -> out += expr.name
      is WffExpr.Unary -> collectAstSources(expr.operand, out)
      is WffExpr.Binary -> {
        collectAstSources(expr.left, out)
        collectAstSources(expr.right, out)
      }
      is WffExpr.Ternary -> {
        collectAstSources(expr.cond, out)
        collectAstSources(expr.ifTrue, out)
        collectAstSources(expr.ifFalse, out)
      }
      is WffExpr.Call -> expr.args.forEach { collectAstSources(it, out) }
      is WffExpr.ConstNum, is WffExpr.ConstStr, is WffExpr.StringIdent, is WffExpr.ConstBool -> Unit
    }
  }

  private fun inspectAst(
    expr: WffExpr,
    unknownSources: MutableSet<String>,
    unknownFuncs: MutableSet<String>,
  ): Int = when (expr) {
    is WffExpr.ConstNum, is WffExpr.ConstStr, is WffExpr.StringIdent, is WffExpr.ConstBool -> DEP_NONE
    is WffExpr.Source -> {
      val upper = expr.name.uppercase(Locale.US)
      when {
        upper.startsWith("CONFIGURATION.") -> DEP_CONFIG
        upper.startsWith("COMPLICATION.") -> DEP_COMPLICATION
        upper.startsWith("WEATHER.") -> DEP_NONE
        else -> {
          if (upper !in knownSources) {
            unknownSources += expr.name
          }
          when {
            upper in setOf("MILLISECOND", "SECOND_MILLISECOND", "UTC_TIMESTAMP") -> DEP_MILLIS or DEP_SECOND
            upper.contains("SECOND") -> DEP_SECOND
            upper.contains("MINUTE") -> DEP_MINUTE
            upper.contains("HOUR") || upper.contains("AMPM") || upper == "IS_24_HOUR_MODE" -> DEP_HOUR
            upper.contains("DAY") || upper.contains("MONTH") || upper.contains("YEAR") || upper.contains("TIMEZONE") -> DEP_DAY
            upper.contains("BATTERY") -> DEP_BATTERY
            upper in setOf("STEP_COUNT", "STEP_GOAL", "STEP_PERCENT") ||
              upper.startsWith("HEART_RATE") ||
              upper == "LIGHT_LEVEL" || upper == "ILLUMINANCE" ||
              upper == "AMBIENT_PRESSURE" || upper == "PRESSURE" || upper == "PRESSURE_HPA" ||
              upper.startsWith("ACCELEROMETER_") -> DEP_SENSORS
            else -> DEP_NONE
          }
        }
      }
    }
    is WffExpr.Unary -> inspectAst(expr.operand, unknownSources, unknownFuncs)
    is WffExpr.Binary -> inspectAst(expr.left, unknownSources, unknownFuncs) or inspectAst(expr.right, unknownSources, unknownFuncs)
    is WffExpr.Ternary -> inspectAst(expr.cond, unknownSources, unknownFuncs) or
      inspectAst(expr.ifTrue, unknownSources, unknownFuncs) or
      inspectAst(expr.ifFalse, unknownSources, unknownFuncs)
    is WffExpr.Call -> {
      val lower = expr.name.lowercase(Locale.US)
      if (lower !in knownFunctions) unknownFuncs += expr.name
      var m = if (lower == "rand" || lower == "random") DEP_ALWAYS else DEP_NONE
      if (lower == "icutext" || lower == "icubesttext") m = m or DEP_MINUTE or DEP_DAY
      for (arg in expr.args) m = m or inspectAst(arg, unknownSources, unknownFuncs)
      m
    }
  }

  /** Recursive-descent parser covering the full WFF operator precedence table and function syntax. */
  private class Parser(private val input: String) {
    private var pos = 0

    fun parse(): WffExpr = parseTernary()

    private fun skipWs() {
      while (pos < input.length && input[pos].isWhitespace()) pos++
    }

    private fun match(token: String): Boolean {
      skipWs()
      if (input.startsWith(token, pos)) {
        pos += token.length
        return true
      }
      return false
    }

    private fun parseTernary(): WffExpr {
      val cond = parseOr()
      if (match("?")) {
        val t = parseTernary()
        match(":")
        val f = parseTernary()
        return WffExpr.Ternary(cond, t, f)
      }
      return cond
    }

    private fun parseOr(): WffExpr {
      var l = parseAnd()
      while (match("||")) l = WffExpr.Binary(WffBinaryOp.OR, l, parseAnd())
      return l
    }

    private fun parseAnd(): WffExpr {
      var l = parseEquality()
      while (match("&&")) l = WffExpr.Binary(WffBinaryOp.AND, l, parseEquality())
      return l
    }

    private fun parseEquality(): WffExpr {
      var l = parseComparison()
      while (true) {
        l = when {
          match("==") -> WffExpr.Binary(WffBinaryOp.EQ, l, parseComparison())
          match("!=") -> WffExpr.Binary(WffBinaryOp.NEQ, l, parseComparison())
          else -> return l
        }
      }
    }

    private fun parseComparison(): WffExpr {
      var l = parseBitOr()
      while (true) {
        l = when {
          match("<=") -> WffExpr.Binary(WffBinaryOp.LTE, l, parseBitOr())
          match(">=") -> WffExpr.Binary(WffBinaryOp.GTE, l, parseBitOr())
          match("<") -> WffExpr.Binary(WffBinaryOp.LT, l, parseBitOr())
          match(">") -> WffExpr.Binary(WffBinaryOp.GT, l, parseBitOr())
          else -> return l
        }
      }
    }

    private fun parseBitOr(): WffExpr {
      var l = parseBitXor()
      while (true) {
        skipWs()
        if (pos < input.length && input[pos] == '|' && (pos + 1 >= input.length || input[pos + 1] != '|')) {
          pos++
          l = WffExpr.Binary(WffBinaryOp.BIT_OR, l, parseBitXor())
        } else break
      }
      return l
    }

    private fun parseBitXor(): WffExpr {
      var l = parseBitAnd()
      while (match("^")) l = WffExpr.Binary(WffBinaryOp.BIT_XOR, l, parseBitAnd())
      return l
    }

    private fun parseBitAnd(): WffExpr {
      var l = parseShift()
      while (true) {
        skipWs()
        if (pos < input.length && input[pos] == '&' && (pos + 1 >= input.length || input[pos + 1] != '&')) {
          pos++
          l = WffExpr.Binary(WffBinaryOp.BIT_AND, l, parseShift())
        } else break
      }
      return l
    }

    private fun parseShift(): WffExpr {
      var l = parseAddSub()
      while (true) {
        l = when {
          match("<<") -> WffExpr.Binary(WffBinaryOp.SHL, l, parseAddSub())
          match(">>") -> WffExpr.Binary(WffBinaryOp.SHR, l, parseAddSub())
          else -> return l
        }
      }
    }

    private fun parseAddSub(): WffExpr {
      var l = parseMulDiv()
      while (true) {
        l = when {
          match("+") -> WffExpr.Binary(WffBinaryOp.ADD, l, parseMulDiv())
          match("-") -> WffExpr.Binary(WffBinaryOp.SUB, l, parseMulDiv())
          else -> return l
        }
      }
    }

    private fun parseMulDiv(): WffExpr {
      var l = parseUnary()
      while (true) {
        l = when {
          match("*") -> WffExpr.Binary(WffBinaryOp.MUL, l, parseUnary())
          match("/") -> WffExpr.Binary(WffBinaryOp.DIV, l, parseUnary())
          match("%") -> WffExpr.Binary(WffBinaryOp.MOD, l, parseUnary())
          else -> return l
        }
      }
    }

    private fun parseUnary(): WffExpr {
      skipWs()
      if (pos < input.length) {
        when (input[pos]) {
          '-' -> { pos++; return WffExpr.Unary(WffUnaryOp.NEG, parseUnary()) }
          '+' -> { pos++; return WffExpr.Unary(WffUnaryOp.POS, parseUnary()) }
          '~' -> { pos++; return WffExpr.Unary(WffUnaryOp.BIT_NOT, parseUnary()) }
          '!' -> if (pos + 1 >= input.length || input[pos + 1] != '=') {
            pos++; return WffExpr.Unary(WffUnaryOp.NOT, parseUnary())
          }
        }
      }
      return parsePrimary()
    }

    private fun parsePrimary(): WffExpr {
      skipWs()
      if (pos >= input.length) return WffExpr.ConstNum(0.0)
      return when (val ch = input[pos]) {
        '(' -> {
          pos++
          val inner = parseTernary()
          match(")")
          inner
        }
        '[' -> {
          pos++
          val start = pos
          while (pos < input.length && input[pos] != ']') pos++
          val token = input.substring(start, pos).trim()
          if (pos < input.length && input[pos] == ']') pos++
          WffExpr.Source(token)
        }
        '"', '\'' -> {
          pos++
          val sb = StringBuilder()
          while (pos < input.length && input[pos] != ch) {
            if (input[pos] == '\\' && pos + 1 < input.length) {
              pos++
            }
            sb.append(input[pos++])
          }
          if (pos < input.length && input[pos] == ch) pos++
          WffExpr.ConstStr(sb.toString())
        }
        '#' -> {
          pos++
          val start = pos
          while (pos < input.length && input[pos].isLetterOrDigit()) pos++
          val hex = input.substring(start, pos)
          val color = when (hex.length) {
            6 -> (0xFF000000L or (hex.toLongOrNull(16) ?: 0L)).toInt()
            8 -> (hex.toLongOrNull(16) ?: 0xFFFFFFFFL).toInt()
            else -> 0xFFFFFFFF.toInt()
          }
          WffExpr.ConstNum(color.toDouble())
        }
        else -> {
          if (ch.isDigit() || ch == '.') {
            if (ch == '0' && pos + 1 < input.length && (input[pos + 1] == 'x' || input[pos + 1] == 'X')) {
              pos += 2
              val start = pos
              while (pos < input.length && input[pos].isLetterOrDigit()) pos++
              return WffExpr.ConstNum((input.substring(start, pos).toLongOrNull(16) ?: 0L).toDouble())
            }
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            WffExpr.ConstNum(input.substring(start, pos).toDoubleOrNull() ?: 0.0)
          } else {
            val start = pos
            if (input[pos] == '@') pos++
            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_' || input[pos] == '/' || input[pos] == ':')) pos++
            val ident = input.substring(start, pos)
            if (ident.isEmpty()) {
              pos++
              return WffExpr.ConstNum(0.0)
            }
            skipWs()
            if (pos < input.length && input[pos] == '(') {
              pos++
              val args = mutableListOf<WffExpr>()
              skipWs()
              if (pos < input.length && input[pos] != ')') {
                while (true) {
                  args += parseTernary()
                  if (!match(",")) break
                }
              }
              match(")")
              WffExpr.Call(ident, args)
            } else {
              when (ident.lowercase(Locale.US)) {
                "true" -> WffExpr.ConstBool(true)
                "false" -> WffExpr.ConstBool(false)
                else -> WffExpr.StringIdent(ident)
              }
            }
          }
        }
      }
    }
  }
}

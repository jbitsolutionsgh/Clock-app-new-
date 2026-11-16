package com.example.faces.wff

import com.example.core.FaceData
import com.example.core.FaceTimeData
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Typed runtime result of a WFF expression supporting numbers, strings, booleans, and ARGB colors. */
internal data class WffValue(
  val numVal: Double = 0.0,
  val strVal: String? = null,
  val boolVal: Boolean = false,
  val kind: Kind = Kind.NUMBER,
) {
  enum class Kind { NUMBER, STRING, BOOLEAN }

  fun asDouble(): Double = when (kind) {
    Kind.NUMBER -> numVal
    Kind.BOOLEAN -> if (boolVal) 1.0 else 0.0
    Kind.STRING -> strVal?.toDoubleOrNull() ?: parseHexColorOrNull(strVal)?.toDouble() ?: 0.0
  }

  fun asFloat(): Float = asDouble().toFloat()

  fun asBoolean(): Boolean = when (kind) {
    Kind.BOOLEAN -> boolVal
    Kind.NUMBER -> numVal != 0.0 && !numVal.isNaN()
    Kind.STRING -> !strVal.isNullOrEmpty() && !strVal.equals("false", ignoreCase = true) && strVal != "0"
  }

  fun asString(): String = when (kind) {
    Kind.STRING -> strVal.orEmpty()
    Kind.BOOLEAN -> if (boolVal) "true" else "false"
    Kind.NUMBER -> if (numVal % 1.0 == 0.0 && abs(numVal) < 1e12) numVal.toLong().toString() else numVal.toString()
  }

  fun asColorInt(fallback: Int): Int {
    if (kind == Kind.STRING) return parseHexColorOrNull(strVal) ?: fallback
    if (kind == Kind.NUMBER) return numVal.toLong().toInt()
    return fallback
  }

  companion object {
    fun num(v: Double) = WffValue(numVal = if (v.isNaN() || v.isInfinite()) 0.0 else v, kind = Kind.NUMBER)
    fun str(v: String) = WffValue(strVal = v, kind = Kind.STRING)
    fun bool(v: Boolean) = WffValue(boolVal = v, kind = Kind.BOOLEAN)

    private fun parseHexColorOrNull(raw: String?): Int? {
      val clean = raw?.trim()?.removePrefix("#") ?: return null
      if (clean.length != 6 && clean.length != 8) return null
      val parsed = clean.toLongOrNull(16) ?: return null
      return if (clean.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
    }
  }
}

/** Resolves live [CONFIGURATION.<id>] values from saved UserConfigurations for an active face. */
internal class WffConfigRuntime(
  configs: List<WffUserConfig>,
  savedValues: Map<String, String>,
) {
  private val colorConfigs = HashMap<String, List<Int>>()
  private val rawValues = HashMap<String, String>()
  private val numericValues = HashMap<String, Double>()

  init {
    for (cfg in configs) {
      val activeRaw = savedValues[cfg.id] ?: cfg.defaultValue
      when (cfg) {
        is WffUserConfig.ColorConfig -> {
          val option = cfg.options.firstOrNull { it.id == activeRaw }
            ?: activeRaw.toIntOrNull()?.let { cfg.options.getOrNull(it) }
            ?: cfg.options.firstOrNull()
          if (option != null && option.colors.isNotEmpty()) {
            colorConfigs[cfg.id] = option.colors
            rawValues[cfg.id] = option.id
            numericValues[cfg.id] = option.colors.first().toDouble()
          }
        }
        is WffUserConfig.ListConfig -> {
          rawValues[cfg.id] = activeRaw
          val idx = cfg.options.indexOfFirst { it.id == activeRaw }.coerceAtLeast(0)
          numericValues[cfg.id] = activeRaw.toDoubleOrNull() ?: idx.toDouble()
        }
        is WffUserConfig.BooleanConfig -> {
          val isTrue = activeRaw.equals("true", ignoreCase = true) || activeRaw == "1"
          rawValues[cfg.id] = if (isTrue) "TRUE" else "FALSE"
          numericValues[cfg.id] = if (isTrue) 1.0 else 0.0
        }
      }
    }
  }

  /** Evaluates [CONFIGURATION.<id>] or [CONFIGURATION.<id>.<index>] into a [WffValue]. */
  fun resolve(sourceName: String): WffValue {
    val path = sourceName.substringAfter('.')
    val dotIdx = path.indexOf('.')
    if (dotIdx > 0) {
      val cfgId = path.substring(0, dotIdx)
      val subIdx = path.substring(dotIdx + 1).toIntOrNull() ?: 0
      val colors = colorConfigs[cfgId]
      if (colors != null && colors.isNotEmpty()) {
        val c = colors[subIdx.coerceIn(0, colors.lastIndex)]
        return WffValue.num(c.toDouble())
      }
    }
    val colors = colorConfigs[path]
    if (colors != null && colors.isNotEmpty()) {
      return WffValue.num(colors.first().toDouble())
    }
    val num = numericValues[path]
    val str = rawValues[path]
    return when {
      str != null && (str.equals("TRUE", ignoreCase = true) || str.equals("FALSE", ignoreCase = true)) ->
        WffValue.bool(str.equals("TRUE", ignoreCase = true))
      str != null && num != null && str == num.toString() -> WffValue.num(num)
      str != null -> WffValue(numVal = num ?: 0.0, strVal = str, boolVal = (num ?: 0.0) != 0.0, kind = WffValue.Kind.STRING)
      else -> WffValue.num(0.0)
    }
  }
}

/** Shared per-frame version vector allowing EvalCacheSlots to skip re-evaluation when inputs are unchanged. */
internal class WffEvalContext(
  var data: FaceData = FaceData(),
  var configRuntime: WffConfigRuntime? = null,
) {
  var millisVer: Long = 1L
  var secondVer: Long = 1L
  var minuteVer: Long = 1L
  var hourVer: Long = 1L
  var dayVer: Long = 1L
  var batteryVer: Long = 1L
  var configVer: Long = 1L

  private var prevMillis = -1
  private var prevSec = -1
  private var prevMin = -1
  private var prevHour = -1
  private var prevDay = -1
  private var prevMonth = -1
  private var prevBatPct = -1
  private var prevBatChg = false

  /** Updates category change counters only when corresponding snapshot values differ. */
  fun syncFromData(updated: FaceData) {
    data = updated
    val t = updated.time
    val b = updated.battery
    if (t.millis != prevMillis) { prevMillis = t.millis; millisVer++ }
    if (t.second != prevSec) { prevSec = t.second; secondVer++ }
    if (t.minute != prevMin) { prevMin = t.minute; minuteVer++ }
    if (t.hour24 != prevHour) { prevHour = t.hour24; hourVer++ }
    if (t.dayOfMonth != prevDay || t.monthNumber != prevMonth) {
      prevDay = t.dayOfMonth; prevMonth = t.monthNumber; dayVer++
    }
    if (b.levelPercent != prevBatPct || b.isCharging != prevBatChg) {
      prevBatPct = b.levelPercent; prevBatChg = b.isCharging; batteryVer++
    }
  }
}

/** Per-expression memoization slot recording input versions alongside the last computed value. */
internal class EvalCacheSlot {
  var initialized = false
  var sMillis = -1L
  var sSecond = -1L
  var sMinute = -1L
  var sHour = -1L
  var sDay = -1L
  var sBattery = -1L
  var sConfig = -1L
  var value: WffValue = WffValue.num(0.0)

  fun validFor(expr: CompiledWffExpr, ctx: WffEvalContext): Boolean {
    if (!initialized) return false
    val m = expr.inputMask
    if ((m and WffExpression.DEP_ALWAYS) != 0) return false
    if ((m and WffExpression.DEP_MILLIS) != 0 && sMillis != ctx.millisVer) return false
    if ((m and WffExpression.DEP_SECOND) != 0 && sSecond != ctx.secondVer) return false
    if ((m and WffExpression.DEP_MINUTE) != 0 && sMinute != ctx.minuteVer) return false
    if ((m and WffExpression.DEP_HOUR) != 0 && sHour != ctx.hourVer) return false
    if ((m and WffExpression.DEP_DAY) != 0 && sDay != ctx.dayVer) return false
    if ((m and WffExpression.DEP_BATTERY) != 0 && sBattery != ctx.batteryVer) return false
    if ((m and WffExpression.DEP_CONFIG) != 0 && sConfig != ctx.configVer) return false
    return true
  }

  fun captureVersions(expr: CompiledWffExpr, ctx: WffEvalContext) {
    initialized = true
    sMillis = ctx.millisVer
    sSecond = ctx.secondVer
    sMinute = ctx.minuteVer
    sHour = ctx.hourVer
    sDay = ctx.dayVer
    sBattery = ctx.batteryVer
    sConfig = ctx.configVer
  }
}

/**
 * Evaluates AST expressions, built-in WFF math/string/conversion/ICU functions,
 * Template/Parameter formatting, and digital TimeText patterns.
 */
internal object WffExpressionEvaluator {

  private val fullMonthNames = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
  )
  private val shortMonthNames = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
  )
  private val fullDayNames = arrayOf(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
  )
  private val shortDayNames = arrayOf(
    "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat",
  )

  /** Recursively evaluates a [WffExpr] AST node against [ctx]. */
  fun evalAst(expr: WffExpr, ctx: WffEvalContext): WffValue = when (expr) {
    is WffExpr.ConstNum -> WffValue.num(expr.value)
    is WffExpr.ConstStr -> WffValue.str(expr.value)
    is WffExpr.ConstBool -> WffValue.bool(expr.value)
    is WffExpr.Source -> evalSource(expr.name, ctx)
    is WffExpr.Unary -> {
      val v = evalAst(expr.operand, ctx)
      when (expr.op) {
        WffUnaryOp.NEG -> WffValue.num(-v.asDouble())
        WffUnaryOp.POS -> WffValue.num(v.asDouble())
        WffUnaryOp.NOT -> WffValue.bool(!v.asBoolean())
        WffUnaryOp.BIT_NOT -> WffValue.num(v.asDouble().toLong().inv().toDouble())
      }
    }
    is WffExpr.Ternary -> {
      if (evalAst(expr.cond, ctx).asBoolean()) evalAst(expr.ifTrue, ctx) else evalAst(expr.ifFalse, ctx)
    }
    is WffExpr.Binary -> evalBinary(expr.op, expr.left, expr.right, ctx)
    is WffExpr.Call -> evalCall(expr.name, expr.args, ctx)
  }

  private fun evalBinary(op: WffBinaryOp, left: WffExpr, right: WffExpr, ctx: WffEvalContext): WffValue {
    if (op == WffBinaryOp.AND) {
      return WffValue.bool(evalAst(left, ctx).asBoolean() && evalAst(right, ctx).asBoolean())
    }
    if (op == WffBinaryOp.OR) {
      return WffValue.bool(evalAst(left, ctx).asBoolean() || evalAst(right, ctx).asBoolean())
    }
    val l = evalAst(left, ctx)
    val r = evalAst(right, ctx)
    if (op == WffBinaryOp.ADD && (l.kind == WffValue.Kind.STRING || r.kind == WffValue.Kind.STRING)) {
      return WffValue.str(l.asString() + r.asString())
    }
    if ((op == WffBinaryOp.EQ || op == WffBinaryOp.NEQ) &&
      (l.kind == WffValue.Kind.STRING || r.kind == WffValue.Kind.STRING)
    ) {
      val eq = l.asString() == r.asString()
      return WffValue.bool(if (op == WffBinaryOp.EQ) eq else !eq)
    }
    val lv = l.asDouble()
    val rv = r.asDouble()
    return when (op) {
      WffBinaryOp.ADD -> WffValue.num(lv + rv)
      WffBinaryOp.SUB -> WffValue.num(lv - rv)
      WffBinaryOp.MUL -> WffValue.num(lv * rv)
      WffBinaryOp.DIV -> WffValue.num(if (rv != 0.0) lv / rv else 0.0)
      WffBinaryOp.MOD -> WffValue.num(if (rv != 0.0) lv % rv else 0.0)
      WffBinaryOp.SHL -> WffValue.num((lv.toLong() shl rv.toInt()).toDouble())
      WffBinaryOp.SHR -> WffValue.num((lv.toLong() shr rv.toInt()).toDouble())
      WffBinaryOp.BIT_AND -> WffValue.num((lv.toLong() and rv.toLong()).toDouble())
      WffBinaryOp.BIT_XOR -> WffValue.num((lv.toLong() xor rv.toLong()).toDouble())
      WffBinaryOp.BIT_OR -> WffValue.num((lv.toLong() or rv.toLong()).toDouble())
      WffBinaryOp.EQ -> WffValue.bool(lv == rv)
      WffBinaryOp.NEQ -> WffValue.bool(lv != rv)
      WffBinaryOp.LT -> WffValue.bool(lv < rv)
      WffBinaryOp.LTE -> WffValue.bool(lv <= rv)
      WffBinaryOp.GT -> WffValue.bool(lv > rv)
      WffBinaryOp.GTE -> WffValue.bool(lv >= rv)
      else -> WffValue.num(0.0)
    }
  }

  private fun evalCall(name: String, args: List<WffExpr>, ctx: WffEvalContext): WffValue {
    val fn = name.lowercase(Locale.US)
    if (fn == "ifelse" || fn == "if") {
      if (args.size < 3) return WffValue.num(0.0)
      return if (evalAst(args[0], ctx).asBoolean()) evalAst(args[1], ctx) else evalAst(args[2], ctx)
    }
    fun d(i: Int, def: Double = 0.0): Double = args.getOrNull(i)?.let { evalAst(it, ctx).asDouble() } ?: def
    fun s(i: Int): String = args.getOrNull(i)?.let { evalAst(it, ctx).asString() } ?: ""

    return when (fn) {
      "round" -> WffValue.num(round(d(0)))
      "floor" -> WffValue.num(floor(d(0)))
      "ceil" -> WffValue.num(ceil(d(0)))
      "fract" -> {
        val v = d(0)
        WffValue.num(v - floor(v))
      }
      "sin" -> WffValue.num(sin(d(0)))
      "cos" -> WffValue.num(cos(d(0)))
      "tan" -> WffValue.num(tan(d(0)))
      "asin" -> WffValue.num(asin(d(0).coerceIn(-1.0, 1.0)))
      "acos" -> WffValue.num(acos(d(0).coerceIn(-1.0, 1.0)))
      "atan" -> WffValue.num(atan(d(0)))
      "atan2" -> WffValue.num(atan2(d(0), d(1)))
      "abs" -> WffValue.num(abs(d(0)))
      "sign" -> WffValue.num(sign(d(0)))
      "clamp" -> {
        val x = d(0)
        val lo = min(d(1), d(2))
        val hi = max(d(1), d(2))
        WffValue.num(x.coerceIn(lo, hi))
      }
      "min" -> if (args.isEmpty()) WffValue.num(0.0) else WffValue.num(args.minOf { evalAst(it, ctx).asDouble() })
      "max" -> if (args.isEmpty()) WffValue.num(0.0) else WffValue.num(args.maxOf { evalAst(it, ctx).asDouble() })
      "pow" -> WffValue.num(d(0).pow(d(1)))
      "sqrt" -> WffValue.num(sqrt(max(0.0, d(0))))
      "cbrt" -> WffValue.num(cbrt(d(0)))
      "log" -> WffValue.num(if (d(0) > 0.0) ln(d(0)) else 0.0)
      "log2" -> WffValue.num(if (d(0) > 0.0) kotlin.math.log2(d(0)) else 0.0)
      "log10" -> WffValue.num(if (d(0) > 0.0) log10(d(0)) else 0.0)
      "log1p" -> WffValue.num(if (d(0) > -1.0) java.lang.Math.log1p(d(0)) else 0.0)
      "exp" -> WffValue.num(exp(d(0)))
      "expm1" -> WffValue.num(expm1(d(0)))
      "hypot" -> WffValue.num(hypot(d(0), d(1)))
      "rad" -> WffValue.num(d(0) * (PI / 180.0))
      "deg" -> WffValue.num(d(0) * (180.0 / PI))
      "mod" -> WffValue.num(if (d(1) != 0.0) d(0) % d(1) else 0.0)
      "rand", "random" -> {
        if (args.size >= 2) {
          val lo = min(d(0), d(1))
          val hi = max(d(0), d(1))
          WffValue.num(lo + Random.nextDouble() * (hi - lo))
        } else {
          WffValue.num(Random.nextDouble())
        }
      }
      "subtext", "substring" -> {
        val text = s(0)
        val start = d(1).toInt().coerceIn(0, text.length)
        val end = if (args.size >= 3) d(2).toInt().coerceIn(start, text.length) else text.length
        WffValue.str(text.substring(start, end))
      }
      "textlength", "strlen" -> WffValue.num(s(0).length.toDouble())
      "strindexof", "indexof" -> WffValue.num(s(0).indexOf(s(1)).toDouble())
      "tolowercase" -> WffValue.str(s(0).lowercase(Locale.getDefault()))
      "touppercase" -> WffValue.str(s(0).uppercase(Locale.getDefault()))
      "trim" -> WffValue.str(s(0).trim())
      "concat" -> WffValue.str(buildString { args.forEach { append(evalAst(it, ctx).asString()) } })
      "numberformat" -> {
        val pattern = s(0).ifEmpty { "#.##" }
        val formatted = runCatching { DecimalFormat(pattern).format(d(1)) }.getOrElse { d(1).toString() }
        WffValue.str(formatted)
      }
      "format" -> {
        val fmt = s(0)
        val rest = args.drop(1).map { v ->
          val res = evalAst(v, ctx)
          if (res.kind == WffValue.Kind.STRING) res.asString() else res.asDouble()
        }.toTypedArray()
        WffValue.str(runCatching { String.format(Locale.getDefault(), fmt, *rest) }.getOrDefault(fmt))
      }
      "icutext", "icubesttext" -> formatIcuDatePattern(s(0), ctx.data.time)
      "toint", "int" -> WffValue.num(d(0).toInt().toDouble())
      "tofloat", "float" -> WffValue.num(d(0))
      "tostring", "str" -> WffValue.str(args.getOrNull(0)?.let { evalAst(it, ctx).asString() } ?: "")
      "toboolean", "bool" -> WffValue.bool(args.getOrNull(0)?.let { evalAst(it, ctx).asBoolean() } ?: false)
      "rgb" -> {
        val c = (0xFF shl 24) or
          ((d(0).toInt().coerceIn(0, 255)) shl 16) or
          ((d(1).toInt().coerceIn(0, 255)) shl 8) or
          d(2).toInt().coerceIn(0, 255)
        WffValue.num(c.toDouble())
      }
      "argb" -> {
        val c = ((d(0).toInt().coerceIn(0, 255)) shl 24) or
          ((d(1).toInt().coerceIn(0, 255)) shl 16) or
          ((d(2).toInt().coerceIn(0, 255)) shl 8) or
          d(3).toInt().coerceIn(0, 255)
        WffValue.num(c.toDouble())
      }
      else -> WffValue.num(0.0)
    }
  }

  /** Evaluates a bracketed WFF [SOURCE] token into its typed [WffValue]. */
  fun evalSource(source: String, ctx: WffEvalContext): WffValue {
    val upper = source.uppercase(Locale.US)
    if (upper.startsWith("CONFIGURATION.")) {
      return ctx.configRuntime?.resolve(source) ?: WffValue.num(0.0)
    }
    val t = ctx.data.time
    val b = ctx.data.battery
    return when (upper) {
      "HOUR_0_11" -> WffValue.num((t.hour24 % 12).toDouble())
      "HOUR_0_11_Z" -> WffValue.str(twoDigits(t.hour24 % 12))
      "HOUR_1_12" -> WffValue.num(t.hour12.toDouble())
      "HOUR_1_12_Z" -> WffValue.str(twoDigits(t.hour12))
      "HOUR_0_23" -> WffValue.num(t.hour24.toDouble())
      "HOUR_0_23_Z" -> WffValue.str(twoDigits(t.hour24))
      "HOUR_1_24" -> WffValue.num((if (t.hour24 == 0) 24 else t.hour24).toDouble())
      "HOUR_1_24_Z" -> WffValue.str(twoDigits(if (t.hour24 == 0) 24 else t.hour24))
      "HOUR" -> WffValue.num((if (t.is24Hour) t.hour24 else t.hour12).toDouble())
      "HOUR_0_11_TENS_DIGIT" -> WffValue.num(((t.hour24 % 12) / 10).toDouble())
      "HOUR_0_11_UNITS_DIGIT" -> WffValue.num(((t.hour24 % 12) % 10).toDouble())
      "HOUR_0_23_TENS_DIGIT" -> WffValue.num((t.hour24 / 10).toDouble())
      "HOUR_0_23_UNITS_DIGIT" -> WffValue.num((t.hour24 % 10).toDouble())
      "HOUR_0_11_MINUTE" -> WffValue.num((t.hour24 % 12) + t.minute / 60.0)
      "HOUR_0_23_MINUTE" -> WffValue.num(t.hour24 + t.minute / 60.0)
      "MINUTE" -> WffValue.num(t.minute.toDouble())
      "MINUTE_Z" -> WffValue.str(twoDigits(t.minute))
      "MINUTE_TENS_DIGIT" -> WffValue.num((t.minute / 10).toDouble())
      "MINUTE_UNITS_DIGIT" -> WffValue.num((t.minute % 10).toDouble())
      "MINUTE_SECOND" -> WffValue.num(t.minute + t.second / 60.0)
      "SECOND" -> WffValue.num(t.second.toDouble())
      "SECOND_Z" -> WffValue.str(twoDigits(t.second))
      "SECOND_TENS_DIGIT" -> WffValue.num((t.second / 10).toDouble())
      "SECOND_UNITS_DIGIT" -> WffValue.num((t.second % 10).toDouble())
      "SECOND_MILLISECOND" -> WffValue.num(t.second + t.millis / 1000.0)
      "MILLISECOND" -> WffValue.num(t.millis.toDouble())
      "AMPM_STATE" -> WffValue.num(if (t.hour24 < 12) 0.0 else 1.0)
      "AMPM_POSITION" -> WffValue.num(if (t.is24Hour) 0.0 else 1.0)
      "AMPM_STRING" -> WffValue.str(if (t.hour24 < 12) "AM" else "PM")
      "IS_24_HOUR_MODE" -> WffValue.bool(t.is24Hour)
      "DAY" -> WffValue.num(t.dayOfMonth.toDouble())
      "DAY_Z" -> WffValue.str(twoDigits(t.dayOfMonth))
      "DAY_HOUR" -> WffValue.num(t.dayOfMonth + t.hour24 / 24.0)
      "DAYS_IN_MONTH" -> WffValue.num(daysInMonth(t.year, t.monthNumber).toDouble())
      "DAY_OF_YEAR" -> WffValue.num(dayOfYearFor(t).toDouble())
      "MONTH" -> WffValue.num(t.monthNumber.toDouble())
      "MONTH_Z" -> WffValue.str(twoDigits(t.monthNumber))
      "MONTH_0_11" -> WffValue.num((t.monthNumber - 1).coerceAtLeast(0).toDouble())
      "MONTH_S" -> WffValue.str(shortMonthNames[(t.monthNumber - 1).coerceIn(0, 11)])
      "MONTH_F" -> WffValue.str(fullMonthNames[(t.monthNumber - 1).coerceIn(0, 11)])
      "YEAR" -> WffValue.num(t.year.toDouble())
      "YEAR_S" -> WffValue.str(twoDigits(t.year % 100))
      "DAY_OF_WEEK" -> WffValue.num(t.dayOfWeek.toDouble())
      "DAY_OF_WEEK_S" -> WffValue.str(shortDayNames[(t.dayOfWeek - 1).coerceIn(0, 6)])
      "DAY_OF_WEEK_F" -> WffValue.str(fullDayNames[(t.dayOfWeek - 1).coerceIn(0, 6)])
      "FIRST_DAY_OF_WEEK" -> WffValue.num(Calendar.getInstance().firstDayOfWeek.toDouble())
      "SECONDS_IN_DAY" -> WffValue.num((t.hour24 * 3600 + t.minute * 60 + t.second).toDouble())
      "UTC_TIMESTAMP" -> WffValue.num(System.currentTimeMillis().toDouble())
      "TIMEZONE" -> WffValue.str(TimeZone.getDefault().id)
      "TIMEZONE_ABB" -> WffValue.str(TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(Date()), TimeZone.SHORT))
      "TIMEZONE_OFFSET" -> WffValue.num((TimeZone.getDefault().rawOffset / 3600000.0))
      "TIMEZONE_OFFSET_DST" -> WffValue.num((TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000.0))
      "IS_DAYLIGHT_SAVING_TIME" -> WffValue.bool(TimeZone.getDefault().inDaylightTime(Date()))
      "BATTERY_PERCENT" -> WffValue.num(b.levelPercent.toDouble())
      "BATTERY_CHARGING_STATUS" -> WffValue.str(if (b.isCharging) "Charging" else "Not charging")
      "IS_BATTERY_CHARGING" -> WffValue.bool(b.isCharging)
      "BATTERY_IS_LOW" -> WffValue.bool(b.levelPercent <= 20)
      "BATTERY_TEMPERATURE_CELSIUS" -> WffValue.num(25.0)
      "BATTERY_TEMPERATURE_FAHRENHEIT" -> WffValue.num(77.0)
      else -> WffValue.num(0.0)
    }
  }

  /** Formats a PartText Template + Parameter list into [out]. */
  fun formatTemplate(
    template: String,
    parameters: List<CompiledWffExpr>,
    ctx: WffEvalContext,
    out: StringBuilder,
  ) {
    out.setLength(0)
    var paramIdx = 0
    var i = 0
    while (i < template.length) {
      val ch = template[i]
      if (ch == '%' && i + 1 < template.length) {
        if (template[i + 1] == '%') {
          out.append('%')
          i += 2
          continue
        }
        val specStart = i + 1
        var j = specStart
        while (j < template.length && (template[j].isDigit() || template[j] == '.' || template[j] == '-')) j++
        if (j < template.length) {
          val conv = template[j]
          val expr = parameters.getOrNull(paramIdx++)
          if (expr != null) {
            val v = evalAst(expr.ast, ctx)
            val spec = template.substring(specStart, j)
            when (conv) {
              's', 'S' -> out.append(v.asString())
              'd', 'i', 'u' -> {
                val intVal = v.asDouble().toInt()
                if (spec.startsWith("0")) {
                  appendPaddedInt(intVal, spec.substring(1).toIntOrNull() ?: 0, out)
                } else out.append(intVal)
              }
              'f' -> {
                val dotIdx = spec.indexOf('.')
                val dec = if (dotIdx >= 0) spec.substring(dotIdx + 1).toIntOrNull() ?: 2 else 2
                appendFixedFloat(v.asDouble().toFloat(), dec, out)
              }
              else -> out.append(v.asDouble().toInt())
            }
          }
          i = j + 1
          continue
        }
      }
      out.append(ch)
      i++
    }
  }

  /** Formats a TimeText date/time pattern into [out], hiding seconds in ambient mode. */
  fun formatTimeText(
    format: String,
    hourFormat: String,
    data: FaceData,
    isAmbient: Boolean,
    out: StringBuilder,
  ) {
    out.setLength(0)
    val t = data.time
    val use24Hour = when (hourFormat.uppercase(Locale.US)) {
      "24" -> true
      "12" -> false
      else -> if (format.contains('H')) true else if (format.contains('h')) t.is24Hour else t.is24Hour
    }
    var i = 0
    while (i < format.length) {
      val ch = format[i]
      if (ch == '\'') {
        val endQuote = format.indexOf('\'', i + 1)
        if (endQuote > i) {
          out.append(format, i + 1, endQuote)
          i = endQuote + 1
        } else i++
        continue
      }
      if (isAmbient && ch == ':' && i + 1 < format.length && format[i + 1] == 's') {
        i++
        while (i < format.length && format[i] == 's') i++
        continue
      }
      if (ch == 'h' || ch == 'H') {
        var count = 0
        while (i + count < format.length && format[i + count] == ch) count++
        val hv = if (use24Hour) t.hour24 else t.hour12
        if (count >= 2) appendTwoDigits(hv, out) else out.append(hv)
        i += count
        continue
      }
      if (ch == 'm') {
        var count = 0
        while (i + count < format.length && format[i + count] == 'm') count++
        if (count >= 2) appendTwoDigits(t.minute, out) else out.append(t.minute)
        i += count
        continue
      }
      if (ch == 's') {
        var count = 0
        while (i + count < format.length && format[i + count] == 's') count++
        if (!isAmbient) {
          if (count >= 2) appendTwoDigits(t.second, out) else out.append(t.second)
        }
        i += count
        continue
      }
      if (ch == 'a') {
        var count = 0
        while (i + count < format.length && format[i + count] == 'a') count++
        if (!use24Hour) out.append(t.amPm)
        i += count
        continue
      }
      if (ch == 'd') {
        var count = 0
        while (i + count < format.length && format[i + count] == 'd') count++
        if (count >= 2) appendTwoDigits(t.dayOfMonth, out) else out.append(t.dayOfMonth)
        i += count
        continue
      }
      if (ch == 'M') {
        var count = 0
        while (i + count < format.length && format[i + count] == 'M') count++
        when {
          count >= 4 -> out.append(fullMonthNames[(t.monthNumber - 1).coerceIn(0, 11)])
          count == 3 -> out.append(shortMonthNames[(t.monthNumber - 1).coerceIn(0, 11)])
          count == 2 -> appendTwoDigits(t.monthNumber, out)
          else -> out.append(t.monthNumber)
        }
        i += count
        continue
      }
      out.append(ch)
      i++
    }
  }

  private fun formatIcuDatePattern(pattern: String, t: FaceTimeData): WffValue {
    val cal = Calendar.getInstance().apply {
      set(t.year, (t.monthNumber - 1).coerceIn(0, 11), t.dayOfMonth, t.hour24, t.minute, t.second)
    }
    val res = runCatching {
      SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
    }.getOrDefault(pattern)
    return WffValue.str(res)
  }

  private fun twoDigits(v: Int): String = if (v in 0..9) "0$v" else v.toString()

  private fun appendTwoDigits(v: Int, out: StringBuilder) {
    val c = v.coerceIn(0, 99)
    if (c < 10) out.append('0')
    out.append(c)
  }

  private fun appendPaddedInt(value: Int, minWidth: Int, out: StringBuilder) {
    val neg = value < 0
    val digits = (if (neg) -value else value).toString()
    if (neg) out.append('-')
    for (k in 0 until (minWidth - digits.length)) out.append('0')
    out.append(digits)
  }

  private fun appendFixedFloat(value: Float, decimals: Int, out: StringBuilder) {
    if (decimals <= 0) {
      out.append(value.toInt())
      return
    }
    var factor = 1f
    repeat(decimals.coerceAtMost(4)) { factor *= 10f }
    val scaled = kotlin.math.round(value * factor).toLong()
    val intPart = scaled / factor.toLong()
    val fracPart = abs(scaled % factor.toLong())
    out.append(intPart).append('.')
    val fracStr = fracPart.toString()
    for (k in 0 until (decimals.coerceAtMost(4) - fracStr.length)) out.append('0')
    out.append(fracStr)
  }

  private fun daysInMonth(year: Int, month1Based: Int): Int = when (month1Based) {
    2 -> if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
  }

  private fun dayOfYearFor(t: FaceTimeData): Int {
    val daysBefore = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    val mIdx = (t.monthNumber - 1).coerceIn(0, 11)
    val isLeap = (t.year % 4 == 0 && t.year % 100 != 0) || (t.year % 400 == 0)
    return daysBefore[mIdx] + (if (isLeap && mIdx >= 2) 1 else 0) + t.dayOfMonth
  }
}

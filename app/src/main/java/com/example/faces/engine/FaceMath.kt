package com.example.faces.engine

import com.example.core.FaceData
import com.example.core.FaceRenderMode
import com.example.core.FaceTimeData
import com.example.core.TimerPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure math and template-formatting helpers for the data-driven watch face engine.
 * Kept framework-free where possible so angles, gauges, conditions, and text templates can be unit tested directly.
 */
internal object FaceMath {

  /** Computes (hourAngleDeg, minuteAngleDeg, secondAngleDeg) in 0..360 clock degrees (12 o'clock = 0). */
  fun timeHandAngles(time: FaceTimeData, smoothSeconds: Boolean): Triple<Float, Float, Float> {
    val secFloat = if (smoothSeconds) {
      time.second + (time.millis.coerceIn(0, 999) / 1000f)
    } else {
      time.second.toFloat()
    }
    val minFloat = time.minute + (secFloat / 60f)
    val hourFloat = (time.hour12 % 12) + (minFloat / 60f)
    return Triple(
      hourFloat * 30f,
      minFloat * 6f,
      secFloat * 6f,
    )
  }

  /** Maps any [value] in [minValue..maxValue] linearly into [startAngle..endAngle] degrees. */
  fun mapRangeToAngle(
    value: Float,
    minValue: Float,
    maxValue: Float,
    startAngle: Float,
    endAngle: Float,
  ): Float {
    val span = maxValue - minValue
    if (kotlin.math.abs(span) < 1e-6f) return startAngle
    val fraction = ((value - minValue) / span).coerceIn(0f, 1f)
    return startAngle + fraction * (endAngle - startAngle)
  }

  /** Computes the clockwise dial angle (0 deg at 12 o'clock) for a [HandLayer]. */
  fun handAngleDegrees(layer: HandLayer, data: FaceData, mode: FaceRenderMode): Float {
    val useSmooth = layer.smooth && mode == FaceRenderMode.NORMAL
    return when (layer.source) {
      HandSource.HOUR -> timeHandAngles(data.time, smoothSeconds = useSmooth).first
      HandSource.MINUTE -> timeHandAngles(data.time, smoothSeconds = useSmooth).second
      HandSource.MINUTE_STEP -> (data.time.minute % 60) * 6f
      HandSource.SECOND -> timeHandAngles(data.time, smoothSeconds = useSmooth).third
      HandSource.BATTERY -> mapRangeToAngle(
        value = data.battery.levelPercent.toFloat(),
        minValue = layer.minValue,
        maxValue = layer.maxValue,
        startAngle = layer.startAngle,
        endAngle = layer.endAngle,
      )
      HandSource.TIMER_PROGRESS -> mapRangeToAngle(
        value = data.timer.progress * 100f,
        minValue = layer.minValue,
        maxValue = layer.maxValue,
        startAngle = layer.startAngle,
        endAngle = layer.endAngle,
      )
      HandSource.STOPWATCH_SECONDS -> ((data.stopwatch.elapsedMs / 1000f) % 60f) * 6f
      HandSource.STOPWATCH_MINUTES -> ((data.stopwatch.elapsedMs / 60_000f) % 60f) * 6f
    }
  }

  /** Computes the normalized 0..1 progress fraction for a [GaugeLayer]. */
  fun gaugeFraction(source: GaugeSource, data: FaceData): Float = when (source) {
    GaugeSource.BATTERY -> (data.battery.levelPercent / 100f).coerceIn(0f, 1f)
    GaugeSource.TIMER_PROGRESS -> data.timer.progress.coerceIn(0f, 1f)
    GaugeSource.SECONDS_PROGRESS -> (data.time.second / 60f).coerceIn(0f, 1f)
    GaugeSource.MINUTES_PROGRESS -> (data.time.minute / 60f).coerceIn(0f, 1f)
  }

  /** Evaluates a layer's optional [ShowCondition] against [data] and [mode]. */
  fun isConditionMet(
    condition: ShowCondition?,
    data: FaceData,
    mode: FaceRenderMode,
  ): Boolean = when (condition) {
    null -> true
    ShowCondition.TimerRunning ->
      data.timer.phase == TimerPhase.RUNNING || data.timer.phase == TimerPhase.GAP_WAIT
    ShowCondition.HasNextEvent -> data.nextEvent != null
    ShowCondition.Charging -> data.battery.isCharging
    is ShowCondition.BatteryBelow -> data.battery.levelPercent < condition.thresholdPercent
    ShowCondition.IsAmbient -> mode == FaceRenderMode.AMBIENT
  }

  /** Formats a text or slot [template] using live values from [data]. */
  fun formatTemplate(template: String, data: FaceData): String {
    if (!template.contains('{')) return template
    val t = data.time
    val displayHour = if (t.is24Hour) {
      "%02d".format(Locale.US, t.hour24)
    } else {
      "%d".format(Locale.US, t.hour12)
    }
    val nextEventTime = data.nextEvent?.let { evt ->
      if (evt.isAllDay) {
        "All day"
      } else {
        val pattern = if (t.is24Hour) "HH:mm" else "h:mm a"
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(evt.startEpochMs))
      }
    }.orEmpty()
    val timerTotalSec = (data.timer.remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val timerMin = timerTotalSec / 60L
    val timerSec = timerTotalSec % 60L

    var out = template
    if (out.contains("{hour}")) out = out.replace("{hour}", displayHour)
    if (out.contains("{hour12}")) out = out.replace("{hour12}", "%d".format(Locale.US, t.hour12))
    if (out.contains("{hour24}")) out = out.replace("{hour24}", "%02d".format(Locale.US, t.hour24))
    if (out.contains("{minute}")) out = out.replace("{minute}", "%02d".format(Locale.US, t.minute))
    if (out.contains("{second}")) out = out.replace("{second}", "%02d".format(Locale.US, t.second))
    val amPmText = if (t.is24Hour) "" else t.amPm
    if (out.contains("{ampm}")) out = out.replace("{ampm}", amPmText)
    if (out.contains("{day}")) out = out.replace("{day}", "%02d".format(Locale.US, t.dayOfMonth))
    if (out.contains("{dayName}")) out = out.replace("{dayName}", t.dayNameShort)
    if (out.contains("{monthName}")) out = out.replace("{monthName}", t.monthNameShort)
    if (out.contains("{battery}")) out = out.replace("{battery}", "${data.battery.levelPercent}%")
    if (out.contains("{nextEventTime}")) out = out.replace("{nextEventTime}", nextEventTime)
    if (out.contains("{nextEventTitle}")) out = out.replace("{nextEventTitle}", data.nextEvent?.title.orEmpty())
    if (out.contains("{timerRemaining}")) {
      out = out.replace("{timerRemaining}", "%02d:%02d".format(Locale.US, timerMin, timerSec))
    }
    if (out.contains("{timerRound}")) out = out.replace("{timerRound}", "R${data.timer.roundNumber}")
    return out
  }
}

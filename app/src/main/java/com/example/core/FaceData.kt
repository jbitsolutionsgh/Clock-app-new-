package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Core contracts and live offline data snapshot for the data-driven watch face engine.
 * A watch face declares only the [FaceDataNeed]s its layers reference so unused sources stay idle.
 */

enum class FaceDataNeed {
  TIME,
  BATTERY,
  NEXT_EVENT,
  TIMER,
  STOPWATCH,
}

enum class FaceRenderMode {
  NORMAL,
  AMBIENT,
}

enum class FaceUpdatePolicy {
  SMOOTH_FRAME,
  EVERY_SECOND,
  EVERY_MINUTE,
}

data class NextCalendarEvent(
  val title: String,
  val startEpochMs: Long,
  val endEpochMs: Long,
  val isAllDay: Boolean,
)

/** Implemented by standby so watch faces can show the next calendar event without importing standby. */
interface NextEventSource {
  val nextEvent: StateFlow<NextCalendarEvent?>
  fun refresh()
}

data class FaceDefinitionError(
  val fileName: String,
  val reason: String,
)

data class WffSkippedDiagnostic(
  val element: String,
  val reason: String,
)

data class WffCompatibilitySummary(
  val id: String,
  val name: String,
  val scorePercent: Int = 100,
  val drawnCount: Int = 0,
  val partlyDrawnCount: Int = 0,
  val skippedCount: Int = 0,
  val partlyDrawnItems: List<WffSkippedDiagnostic> = emptyList(),
  val skippedItems: List<WffSkippedDiagnostic> = emptyList(),
)

data class FaceEngineDiagnostics(
  val loadedDefinitions: List<Pair<String, String>> = emptyList(),
  val errors: List<FaceDefinitionError> = emptyList(),
  val wffFaces: List<WffCompatibilitySummary> = emptyList(),
)

/** Implemented by faces so DiagnosticsScreen can inspect loaded JSON definitions without importing faces. */
interface FaceDiagnosticsProvider {
  val diagnostics: StateFlow<FaceEngineDiagnostics>
}

data class FaceTimeData(
  var hour12: Int = 12,
  var hour24: Int = 0,
  var minute: Int = 0,
  var second: Int = 0,
  var millis: Int = 0,
  var is24Hour: Boolean = false,
  var amPm: String = "AM",
  var dayOfMonth: Int = 1,
  var dayNameShort: String = "MON",
  var monthNameShort: String = "JAN",
  var year: Int = 2026,
  var monthNumber: Int = 1,
  var dayOfWeek: Int = Calendar.MONDAY,
) {
  /** Copies all fields from [other] in place so frame loops reuse this instance without allocating. */
  fun copyFrom(other: FaceTimeData) {
    hour12 = other.hour12
    hour24 = other.hour24
    minute = other.minute
    second = other.second
    millis = other.millis
    is24Hour = other.is24Hour
    amPm = other.amPm
    dayOfMonth = other.dayOfMonth
    dayNameShort = other.dayNameShort
    monthNameShort = other.monthNameShort
    year = other.year
    monthNumber = other.monthNumber
    dayOfWeek = other.dayOfWeek
  }

  companion object {
    /** Returns uppercase 3-letter weekday abbreviation for [cal] without allocating formatters. */
    fun dayNameShortFor(cal: Calendar): String = when (cal.get(Calendar.DAY_OF_WEEK)) {
      Calendar.SUNDAY -> "SUN"
      Calendar.MONDAY -> "MON"
      Calendar.TUESDAY -> "TUE"
      Calendar.WEDNESDAY -> "WED"
      Calendar.THURSDAY -> "THU"
      Calendar.FRIDAY -> "FRI"
      else -> "SAT"
    }

    /** Returns uppercase 3-letter month abbreviation for [cal] without allocating formatters. */
    fun monthNameShortFor(cal: Calendar): String = when (cal.get(Calendar.MONTH)) {
      Calendar.JANUARY -> "JAN"
      Calendar.FEBRUARY -> "FEB"
      Calendar.MARCH -> "MAR"
      Calendar.APRIL -> "APR"
      Calendar.MAY -> "MAY"
      Calendar.JUNE -> "JUN"
      Calendar.JULY -> "JUL"
      Calendar.AUGUST -> "AUG"
      Calendar.SEPTEMBER -> "SEP"
      Calendar.OCTOBER -> "OCT"
      Calendar.NOVEMBER -> "NOV"
      else -> "DEC"
    }
  }
}

data class FaceBatteryData(
  val levelPercent: Int = 100,
  val isCharging: Boolean = false,
)

data class FaceTimerData(
  val phase: TimerPhase = TimerPhase.IDLE,
  val remainingMs: Long = 0L,
  val totalDurationMs: Long = 0L,
  val progress: Float = 0f,
  val roundNumber: Int = 1,
  val currentSpeaker: Int = 1,
)

data class FaceStopwatchData(
  val isRunning: Boolean = false,
  val elapsedMs: Long = 0L,
)

data class FaceData(
  var time: FaceTimeData = FaceTimeData(),
  var battery: FaceBatteryData = FaceBatteryData(),
  var nextEvent: NextCalendarEvent? = null,
  var timer: FaceTimerData = FaceTimerData(),
  var stopwatch: FaceStopwatchData = FaceStopwatchData(),
) {
  /** Updates this snapshot in place from [other], preserving the existing [time] instance when possible. */
  fun copyFrom(other: FaceData) {
    time.copyFrom(other.time)
    battery = other.battery
    nextEvent = other.nextEvent
    timer = other.timer
    stopwatch = other.stopwatch
  }
}

/** Supplies reactive FaceData snapshots containing only the sources declared by the active face. */
interface FaceDataSource {
  fun observe(needs: Set<FaceDataNeed>, updatePolicy: FaceUpdatePolicy): Flow<FaceData>
  fun currentTime(context: Context): FaceTimeData
}

/**
 * Shared offline FaceDataSource implementation backed by system clock, sticky battery broadcast,
 * and core chiplet controllers.
 */
internal class CoreFaceDataSource(context: Context) : FaceDataSource {

  private val appContext = context.applicationContext

  override fun currentTime(context: Context): FaceTimeData = readTimeSnapshot(context)

  override fun observe(
    needs: Set<FaceDataNeed>,
    updatePolicy: FaceUpdatePolicy,
  ): Flow<FaceData> = callbackFlow {
    var current = FaceData(
      time = readTimeSnapshot(appContext),
      battery = if (FaceDataNeed.BATTERY in needs) readStickyBattery(appContext) else FaceBatteryData(),
      nextEvent = if (FaceDataNeed.NEXT_EVENT in needs) CoreSingletons.nextEventSource?.nextEvent?.value else null,
      timer = if (FaceDataNeed.TIMER in needs) snapshotTimer(CoreSingletons.timerController?.state?.value) else FaceTimerData(),
      stopwatch = if (FaceDataNeed.STOPWATCH in needs) snapshotStopwatch(CoreSingletons.stopwatchController?.state?.value) else FaceStopwatchData(),
    )

    fun publish(updated: FaceData) {
      current = updated
      trySend(updated)
    }

    publish(current)

    var batteryReceiver: BroadcastReceiver? = null
    if (FaceDataNeed.BATTERY in needs) {
      batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          if (intent != null) {
            publish(current.copy(battery = parseBatteryIntent(intent)))
          }
        }
      }
      runCatching {
        appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      }
    }

    if (FaceDataNeed.TIMER in needs) {
      CoreSingletons.timerController?.let { controller ->
        launch {
          controller.state.collect { snap ->
            publish(current.copy(timer = snapshotTimer(snap)))
          }
        }
      }
    }

    if (FaceDataNeed.STOPWATCH in needs) {
      CoreSingletons.stopwatchController?.let { controller ->
        launch {
          controller.state.collect { snap ->
            publish(current.copy(stopwatch = snapshotStopwatch(snap)))
          }
        }
      }
    }

    if (FaceDataNeed.NEXT_EVENT in needs) {
      CoreSingletons.nextEventSource?.let { source ->
        source.refresh()
        launch {
          source.nextEvent.collect { evt ->
            publish(current.copy(nextEvent = evt))
          }
        }
      }
    }

    // Time updates only at the cadence requested by the face (frame-rate smooth sweeps are driven by Compose).
    if (FaceDataNeed.TIME in needs && updatePolicy != FaceUpdatePolicy.SMOOTH_FRAME) {
      launch {
        while (isActive) {
          val nowMs = System.currentTimeMillis()
          val delayMs = when (updatePolicy) {
            FaceUpdatePolicy.EVERY_SECOND -> (1_000L - (nowMs % 1_000L)).coerceAtLeast(16L)
            FaceUpdatePolicy.EVERY_MINUTE -> (60_000L - (nowMs % 60_000L)).coerceAtLeast(50L)
            FaceUpdatePolicy.SMOOTH_FRAME -> 16L
          }
          delay(delayMs)
          publish(current.copy(time = readTimeSnapshot(appContext)))
        }
      }
    }

    awaitClose {
      if (batteryReceiver != null) {
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
      }
    }
  }

  private fun snapshotTimer(snap: TimerSnapshot?): FaceTimerData {
    if (snap == null) return FaceTimerData()
    val progress = if (snap.totalDurationMs > 0L) {
      (snap.remainingMs.toFloat() / snap.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
      0f
    }
    return FaceTimerData(
      phase = snap.phase,
      remainingMs = snap.remainingMs,
      totalDurationMs = snap.totalDurationMs,
      progress = progress,
      roundNumber = snap.roundNumber,
      currentSpeaker = snap.currentSpeaker,
    )
  }

  private fun snapshotStopwatch(snap: StopwatchSnapshot?): FaceStopwatchData {
    if (snap == null) return FaceStopwatchData()
    return FaceStopwatchData(
      isRunning = snap.isRunning,
      elapsedMs = snap.elapsedMs,
    )
  }

  private fun readStickyBattery(context: Context): FaceBatteryData {
    val intent = runCatching {
      context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    return if (intent != null) parseBatteryIntent(intent) else FaceBatteryData()
  }

  private fun parseBatteryIntent(intent: Intent): FaceBatteryData {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
      status == BatteryManager.BATTERY_STATUS_FULL
    return FaceBatteryData(levelPercent = pct, isCharging = charging)
  }

  companion object {
    /** Reads the current wall-clock snapshot while respecting the device's 12/24-hour preference. */
    fun readTimeSnapshot(context: Context): FaceTimeData {
      val cal = Calendar.getInstance()
      val h24 = cal.get(Calendar.HOUR_OF_DAY)
      val h12Raw = cal.get(Calendar.HOUR)
      val h12 = if (h12Raw == 0) 12 else h12Raw
      val min = cal.get(Calendar.MINUTE)
      val sec = cal.get(Calendar.SECOND)
      val ms = cal.get(Calendar.MILLISECOND)
      val dom = cal.get(Calendar.DAY_OF_MONTH)
      val dow = FaceTimeData.dayNameShortFor(cal)
      val month = FaceTimeData.monthNameShortFor(cal)
      val is24 = runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(false)
      return FaceTimeData(
        hour12 = h12,
        hour24 = h24,
        minute = min,
        second = sec,
        millis = ms,
        is24Hour = is24,
        amPm = if (h24 < 12) "AM" else "PM",
        dayOfMonth = dom,
        dayNameShort = dow,
        monthNameShort = month,
        year = cal.get(Calendar.YEAR),
        monthNumber = cal.get(Calendar.MONTH) + 1,
        dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
      )
    }
  }
}

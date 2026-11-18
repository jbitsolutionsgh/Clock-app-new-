package com.example.core

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow

/**
 * Core chiplet contracts and shared models.
 * Holds small interfaces so other chiplets communicate only through core.
 */

enum class AuraThemeColor(
  val id: String,
  val label: String,
  val primary: Color,
  val primaryArgb: Int,
  val container: Color,
) {
  COBALT("cobalt", "Electric Cobalt", Color(0xFF3B82F6), 0xFF3B82F6.toInt(), Color(0xFF0F2547)),
  CRIMSON("crimson", "Motorsport Red", Color(0xFFE00424), 0xFFE00424.toInt(), Color(0xFF420A13)),
  GOLD("gold", "Racing Gold", Color(0xFFD4AF37), 0xFFD4AF37.toInt(), Color(0xFF3B300D)),
  EMERALD("emerald", "Classroom Emerald", Color(0xFF10B981), 0xFF10B981.toInt(), Color(0xFF083325)),
  AMBER("amber", "Warm Amber", Color(0xFFF59E0B), 0xFFF59E0B.toInt(), Color(0xFF3D2704)),
  ICE("ice", "Crisp Ice", Color(0xFFE2E8F0), 0xFFE2E8F0.toInt(), Color(0xFF1E293B));

  companion object {
    /** Finds a theme colour by persisted ID, defaulting to Electric Cobalt. */
    fun fromId(id: String?): AuraThemeColor =
      entries.firstOrNull { it.id == id } ?: COBALT
  }
}

enum class TimerPhase {
  IDLE,
  RUNNING,
  PAUSED,
  GAP_WAIT,
  FINISHED,
}

data class TimerSnapshot(
  val phase: TimerPhase = TimerPhase.IDLE,
  val totalDurationMs: Long = 60_000L,
  val remainingMs: Long = 60_000L,
  val gapRemainingMs: Long = 0L,
  val roundNumber: Int = 1,
  val currentSpeaker: Int = 1,
  val isRinging: Boolean = false,
)

/** Public entry interface for controlling the classroom countdown timer across chiplets. */
interface TimerController {
  val state: StateFlow<TimerSnapshot>
  fun setDurationSeconds(seconds: Int)
  fun startOrPause()
  fun reset()
  fun stop()
  fun silenceBeep()
}

data class StopwatchSnapshot(
  val isRunning: Boolean = false,
  val elapsedMs: Long = 0L,
  val lapsMs: List<Long> = emptyList(),
)

/** Public entry interface for controlling the stopwatch across chiplets. */
interface StopwatchController {
  val state: StateFlow<StopwatchSnapshot>
  fun startOrPause()
  fun reset()
  fun lap()
}

/** Public entry interface for cycling watch faces from external triggers such as earbuds. */
interface FaceNavigator {
  fun nextFace()
  fun getFaceOptions(): List<Pair<String, String>>
}

/** Persisted earbud press pattern (e.g. 3x Play/Pause or 1x Previous track) mapped to an action. */
data class LearnedPressPattern(
  val keyCode: Int,
  val count: Int,
  val actionId: String,
  val keyLabel: String,
)

data class EarbudDiagnostics(
  val serviceRunning: Boolean = false,
  val ttsReady: Boolean = false,
  val ttsStatus: String = "Initializing...",
  val lastKeyCodeLabel: String = "None",
  val lastKeyTimeLabel: String = "Never",
  val lastDetectedPattern: String = "None",
  val lastActionExecuted: String = "None",
  val otherAppPlaying: Boolean = false,
  val reprimeCount: Int = 0,
  val lastReprimeTimeLabel: String = "Never",
  val forwardedKeyCount: Int = 0,
  val forwardFailureCount: Int = 0,
)

/** Public entry interface for starting, stopping, and observing the earbud listener service. */
interface EarbudController {
  val diagnostics: StateFlow<EarbudDiagnostics>
  fun setListenerEnabled(enabled: Boolean)
  fun ensureStarted()
}

/** Public contract allowing the Stand By DreamService to render a clock face via core. */
fun interface StandByClockRenderer {
  @androidx.compose.runtime.Composable
  fun Render(useSelectedFace: Boolean, modifier: androidx.compose.ui.Modifier)
}

/** Supported lookahead windows for Stand By calendar events. */
enum class StandByCalendarWindow(val id: String, val label: String) {
  TODAY_ONLY("today", "Today only"),
  TODAY_AND_TOMORROW("today_tomorrow", "Today and tomorrow"),
  NEXT_7_DAYS("next_7_days", "Next 7 days");

  companion object {
    /** Finds a window by persisted ID, defaulting to Today and tomorrow. */
    fun fromId(id: String?): StandByCalendarWindow =
      entries.firstOrNull { it.id == id } ?: TODAY_AND_TOMORROW
  }
}

data class StandByDiagnostics(
  val calendarPermissionGranted: Boolean = false,
  val calendarsFoundCount: Int = 0,
  val eventsFoundCount: Int = 0,
  val lastRefreshTimeLabel: String = "Never",
  val standByLastStartedLabel: String = "Never",
  val standByLastEventCount: Int = 0,
  val requestedWindowBrightness: Float = 0.545f,
  val softwareDimLevel: Float = 0.65f,
)

/** Public contract in core so DiagnosticsScreen and StandByStage can track Stand By status via core. */
interface StandByDiagnosticsProvider {
  val diagnostics: StateFlow<StandByDiagnostics>
  fun refreshNow()
  fun recordDreamStarted(startedTimeLabel: String)
  fun recordDreamEventsShown(count: Int)
}

/** High-level navigation targets requested when tapping a watch face ComplicationSlot. */
enum class AppTabTarget {
  TIMER,
  CLOCK,
  STOPWATCH,
  SETTINGS,
  SETTINGS_CALENDAR,
  DIAGNOSTICS,
}


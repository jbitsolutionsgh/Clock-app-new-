package com.example.faces

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.core.AuraThemeColor
import java.util.Calendar

/**
 * Snapshot of current wall-clock components used to render any WatchFace without allocating Calendar per draw.
 */
internal data class ClockTimeSnapshot(
  val hour12: Int,
  val hour24: Int,
  val minute: Int,
  val second: Int,
  val millis: Int,
  val dayOfMonth: Int,
  val dayOfWeekShort: String,
  val monthDayLabel: String,
  val isAm: Boolean,
) {
  companion object {
    /** Captures the current local wall-clock time into an immutable snapshot. */
    fun now(): ClockTimeSnapshot {
      val cal = Calendar.getInstance()
      val h24 = cal.get(Calendar.HOUR_OF_DAY)
      val h12 = cal.get(Calendar.HOUR)
      val min = cal.get(Calendar.MINUTE)
      val sec = cal.get(Calendar.SECOND)
      val ms = cal.get(Calendar.MILLISECOND)
      val dom = cal.get(Calendar.DAY_OF_MONTH)
      val dow = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> "SUN"
        Calendar.MONDAY -> "MON"
        Calendar.TUESDAY -> "TUE"
        Calendar.WEDNESDAY -> "WED"
        Calendar.THURSDAY -> "THU"
        Calendar.FRIDAY -> "FRI"
        else -> "SAT"
      }
      val month = when (cal.get(Calendar.MONTH)) {
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
      return ClockTimeSnapshot(
        hour12 = h12,
        hour24 = h24,
        minute = min,
        second = sec,
        millis = ms,
        dayOfMonth = dom,
        dayOfWeekShort = dow,
        monthDayLabel = "$dow, $month $dom",
        isAm = h24 < 12,
      )
    }
  }
}

/**
 * Contract implemented by every watch face in the faces chiplet.
 */
internal interface WatchFace {
  val id: String
  val displayName: String

  @Composable
  fun Render(
    timeProvider: () -> ClockTimeSnapshot,
    accent: AuraThemeColor,
    smoothSeconds: Boolean,
    modifier: Modifier,
  )
}

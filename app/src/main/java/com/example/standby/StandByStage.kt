package com.example.standby

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraPrefs
import com.example.core.CoreSingletons
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack
import com.example.ui.theme.SpaceGroteskFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface StandByEventRow {
  data class DayHeading(val label: String) : StandByEventRow
  data class EventItem(
    val id: Long,
    val colorArgb: Int,
    val timeLabel: String,
    val title: String,
  ) : StandByEventRow
}

/**
 * Shared Stand By stage rendered by both StandByDreamService and the in-app Preview.
 * Applies OLED black background, dimmed brightness, burn-in drift across both clock and events,
 * and automatic refresh on start, every 5 minutes, and on calendar ContentObserver changes.
 */
@Composable
internal fun StandByStage(
  coordinator: StandByCalendarCoordinator,
  prefs: AuraPrefs,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val useSelectedFace by prefs.standByUseSelectedFace.collectAsStateWithLifecycle()
  val dimLevel by prefs.standByDimLevel.collectAsStateWithLifecycle()
  val calendarEnabled by prefs.standByCalendarEnabled.collectAsStateWithLifecycle()
  val showEventTitles by prefs.standByShowEventTitles.collectAsStateWithLifecycle()
  val selectedIds by prefs.selectedCalendarIds.collectAsStateWithLifecycle()
  val maxEvents by prefs.standByMaxEvents.collectAsStateWithLifecycle()
  val window by prefs.standByCalendarWindow.collectAsStateWithLifecycle()

  val calSnapshot by coordinator.snapshot.collectAsStateWithLifecycle()

  // Initial load + 5-minute periodic refresh while Stand By is showing.
  LaunchedEffect(calendarEnabled, selectedIds, maxEvents, window) {
    while (isActive) {
      coordinator.refreshSuspend()
      delay(FIVE_MINUTES_MS)
    }
  }

  // Register Calendar ContentObserver only while Stand By is actively displayed.
  DisposableEffect(context, coordinator) {
    val handler = Handler(Looper.getMainLooper())
    val refreshRunnable = Runnable { coordinator.refreshNow() }
    val observer = object : ContentObserver(handler) {
      override fun onChange(selfChange: Boolean) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 400L)
      }
    }
    val registered = runCatching {
      context.contentResolver.registerContentObserver(
        CalendarContract.CONTENT_URI,
        true,
        observer,
      )
      true
    }.getOrDefault(false)

    onDispose {
      handler.removeCallbacks(refreshRunnable)
      if (registered) {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
      }
    }
  }

  // Burn-in protection: shift clock + calendar together by a few pixels every 60 seconds.
  var driftStep by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    while (isActive) {
      delay(60_000L)
      driftStep = (driftStep + 1) % DRIFT_OFFSETS.size
    }
  }
  val (dx, dy) = DRIFT_OFFSETS[driftStep]

  val showCalendar = calendarEnabled && calSnapshot.permissionGranted && calSnapshot.events.isNotEmpty()
  val rows = remember(calSnapshot.events, calSnapshot.calendars, showEventTitles) {
    if (!showCalendar) emptyList()
    else buildEventRows(context, calSnapshot.events, calSnapshot.calendars, showEventTitles)
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(OledBlack)
      .padding(horizontal = 20.dp, vertical = 16.dp)
      .alpha(dimLevel.coerceIn(0.15f, 1.0f))
      .offset { IntOffset(dx, dy) }
      .testTag("standby_stage_root"),
    contentAlignment = Alignment.Center,
  ) {
    if (!showCalendar) {
      StandByClockPane(
        useSelectedFace = useSelectedFace,
        modifier = Modifier.fillMaxSize(),
      )
    } else if (maxWidth > maxHeight) {
      // Landscape: ~60% clock on left, event list on right.
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        StandByClockPane(
          useSelectedFace = useSelectedFace,
          modifier = Modifier
            .weight(0.6f)
            .fillMaxHeight(),
        )
        StandByEventListPane(
          rows = rows,
          modifier = Modifier
            .weight(0.4f)
            .fillMaxHeight(),
        )
      }
    } else {
      // Portrait: clock on top, event list underneath.
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        StandByClockPane(
          useSelectedFace = useSelectedFace,
          modifier = Modifier
            .weight(0.56f)
            .fillMaxWidth(),
        )
        StandByEventListPane(
          rows = rows,
          modifier = Modifier
            .weight(0.44f)
            .fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun StandByClockPane(
  useSelectedFace: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    val renderer = CoreSingletons.standByClockRenderer
    if (renderer != null) {
      renderer.Render(
        useSelectedFace = useSelectedFace,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Text(
        text = "AuraClock",
        fontSize = 36.sp,
        color = Color.White,
      )
    }
  }
}

@Composable
private fun StandByEventListPane(
  rows: List<StandByEventRow>,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .padding(horizontal = 8.dp)
      .testTag("standby_event_list"),
    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
  ) {
    rows.forEach { row ->
      when (row) {
        is StandByEventRow.DayHeading -> {
          Text(
            text = row.label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
          )
        }
        is StandByEventRow.EventItem -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(row.colorArgb)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = row.timeLabel,
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = row.title,
              fontFamily = SpaceGroteskFamily,
              fontWeight = FontWeight.Medium,
              fontSize = 17.sp,
              color = Color.White.copy(alpha = 0.92f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

private fun buildEventRows(
  context: Context,
  events: List<CalendarEventSummary>,
  calendars: List<DeviceCalendarInfo>,
  showTitles: Boolean,
): List<StandByEventRow> {
  val nowMs = System.currentTimeMillis()
  val todayKey = dayKey(nowMs)
  val tomorrowCal = Calendar.getInstance().apply {
    timeInMillis = nowMs
    add(Calendar.DAY_OF_YEAR, 1)
  }
  val tomorrowKey = dayKey(tomorrowCal.timeInMillis)
  val colorsByCalId = calendars.associate { it.id to it.colorArgb }

  val is24Hour = DateFormat.is24HourFormat(context)
  val timeFormatter = SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
  val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())

  val out = mutableListOf<StandByEventRow>()
  var lastHeading: String? = null

  events.forEach { event ->
    val eventDayKey = dayKey(event.startEpochMs)
    val heading = when {
      eventDayKey <= todayKey -> null
      eventDayKey == tomorrowKey -> "Tomorrow"
      else -> weekdayFormatter.format(Date(event.startEpochMs))
    }

    if (heading != null && heading != lastHeading) {
      out.add(StandByEventRow.DayHeading(heading))
      lastHeading = heading
    }

    val timeLabel = when {
      event.isAllDay -> "All day"
      nowMs >= event.startEpochMs && nowMs < event.endEpochMs -> "Now"
      else -> timeFormatter.format(Date(event.startEpochMs))
    }

    val colorArgb = colorsByCalId[event.calendarId] ?: 0xFF3B82F6.toInt()
    val displayTitle = if (showTitles) event.title else "Event"
    out.add(
      StandByEventRow.EventItem(
        id = event.id,
        colorArgb = colorArgb,
        timeLabel = timeLabel,
        title = displayTitle,
      ),
    )
  }
  return out
}

private fun dayKey(epochMs: Long): Int {
  val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
  return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
}

private const val FIVE_MINUTES_MS = 5 * 60_000L

private val DRIFT_OFFSETS = listOf(
  0 to 0,
  6 to 4,
  12 to -3,
  5 to -9,
  -7 to -6,
  -12 to 3,
  -6 to 9,
  3 to 6,
)

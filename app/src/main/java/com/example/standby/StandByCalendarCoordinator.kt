package com.example.standby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.core.AuraPrefs
import com.example.core.NextCalendarEvent
import com.example.core.NextEventSource
import com.example.core.StandByDiagnostics
import com.example.core.StandByDiagnosticsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class StandByCalendarSnapshot(
  val permissionGranted: Boolean = false,
  val calendars: List<DeviceCalendarInfo> = emptyList(),
  val events: List<CalendarEventSummary> = emptyList(),
  val errorMessage: String? = null,
  val isLoading: Boolean = false,
)

/**
 * Coordinates on-device calendar loading, first-time Google/visible calendar auto-selection,
 * error reporting, and diagnostics for both Stand By screens and Settings/Diagnostics.
 */
internal class StandByCalendarCoordinator(
  context: Context,
  private val prefs: AuraPrefs,
) : StandByDiagnosticsProvider, NextEventSource {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val refreshMutex = Mutex()

  private val provider = DeviceCalendarProvider(
    context = appContext,
    windowProvider = { prefs.standByCalendarWindow.value },
  )

  private val _snapshot = MutableStateFlow(StandByCalendarSnapshot())
  val snapshot: StateFlow<StandByCalendarSnapshot> = _snapshot.asStateFlow()

  private val _diagnostics = MutableStateFlow(
    StandByDimming.compute(prefs.standByDimLevel.value).let { dim ->
      StandByDiagnostics(
        requestedWindowBrightness = dim.windowBrightness,
        softwareDimLevel = dim.softwareDimAlpha,
      )
    },
  )
  override val diagnostics: StateFlow<StandByDiagnostics> = _diagnostics.asStateFlow()

  private val _nextEvent = MutableStateFlow<NextCalendarEvent?>(null)
  override val nextEvent: StateFlow<NextCalendarEvent?> = _nextEvent.asStateFlow()

  init {
    scope.launch {
      prefs.standByDimLevel.collect { level ->
        val dim = StandByDimming.compute(level)
        _diagnostics.value = _diagnostics.value.copy(
          requestedWindowBrightness = dim.windowBrightness,
          softwareDimLevel = dim.softwareDimAlpha,
        )
      }
    }
  }

  /** Returns true if READ_CALENDAR permission is currently granted. */
  fun hasCalendarPermission(): Boolean =
    ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

  /** Triggers an asynchronous refresh on a background dispatcher. */
  override fun refreshNow() {
    scope.launch { refreshSuspend() }
  }

  /** Refreshes upcoming events for core NextEventSource consumers. */
  override fun refresh() {
    refreshNow()
  }

  /** Records the timestamp when StandByDreamService started showing. */
  override fun recordDreamStarted(startedTimeLabel: String) {
    _diagnostics.value = _diagnostics.value.copy(standByLastStartedLabel = startedTimeLabel)
  }

  /** Records the number of calendar events rendered on the StandByDreamService screen. */
  override fun recordDreamEventsShown(count: Int) {
    _diagnostics.value = _diagnostics.value.copy(standByLastEventCount = count)
  }

  /** Refreshes calendars, default Google/visible selection if needed, and upcoming Stand By events. */
  suspend fun refreshSuspend() {
    refreshMutex.withLock {
      val granted = hasCalendarPermission()
      if (!granted) {
        _snapshot.value = StandByCalendarSnapshot(permissionGranted = false)
        _nextEvent.value = null
        _diagnostics.value = _diagnostics.value.copy(
          calendarPermissionGranted = false,
          calendarsFoundCount = 0,
          eventsFoundCount = 0,
        )
        return
      }

      _snapshot.value = _snapshot.value.copy(permissionGranted = true, isLoading = true)
      val calResult = provider.queryCalendarsWithStatus()
      if (calResult.permissionDenied) {
        _snapshot.value = StandByCalendarSnapshot(permissionGranted = false)
        _nextEvent.value = null
        _diagnostics.value = _diagnostics.value.copy(
          calendarPermissionGranted = false,
          calendarsFoundCount = 0,
          eventsFoundCount = 0,
        )
        return
      }

      val calendars = calResult.calendars
      val selectedNow = prefs.selectedCalendarIds.value
      val shouldSelectDefaults = calendars.isNotEmpty() && (
        !prefs.hasInitializedCalendarSelection() ||
          (selectedNow.isEmpty() && !prefs.hasUserEditedCalendarSelection())
        )
      if (shouldSelectDefaults) {
        val defaults = resolveDefaultCalendarSelection(calendars, calResult.googleCalendarIds)
        if (defaults.isNotEmpty()) {
          prefs.setSelectedCalendarIds(defaults, userEdited = false)
        }
      }

      val selectedIds = prefs.selectedCalendarIds.value
      val eventsResult = if (prefs.standByCalendarEnabled.value && selectedIds.isNotEmpty()) {
        provider.queryUpcomingEventsWithStatus(
          selectedCalendarIds = selectedIds,
          limit = prefs.standByMaxEvents.value,
        )
      } else {
        null
      }

      if (eventsResult?.permissionDenied == true) {
        _snapshot.value = StandByCalendarSnapshot(permissionGranted = false)
        _nextEvent.value = null
        _diagnostics.value = _diagnostics.value.copy(
          calendarPermissionGranted = false,
          calendarsFoundCount = calendars.size,
          eventsFoundCount = 0,
        )
        return
      }

      val events = eventsResult?.events ?: emptyList()
      val errorMessage = calResult.errorMessage ?: eventsResult?.errorMessage
      val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

      _snapshot.value = StandByCalendarSnapshot(
        permissionGranted = true,
        calendars = calendars,
        events = events,
        errorMessage = errorMessage,
        isLoading = false,
      )
      val earliest = events.firstOrNull()
      _nextEvent.value = if (earliest != null && prefs.standByCalendarEnabled.value) {
        NextCalendarEvent(
          title = if (prefs.standByShowEventTitles.value) earliest.title else "Event",
          startEpochMs = earliest.startEpochMs,
          endEpochMs = earliest.endEpochMs,
          isAllDay = earliest.isAllDay,
        )
      } else {
        null
      }
      _diagnostics.value = _diagnostics.value.copy(
        calendarPermissionGranted = true,
        calendarsFoundCount = calendars.size,
        eventsFoundCount = events.size,
        lastRefreshTimeLabel = timeLabel,
      )
    }
  }

  companion object {
    /** Selects Google-account calendars when present, otherwise selects all visible calendars. */
    internal fun resolveDefaultCalendarSelection(
      calendars: List<DeviceCalendarInfo>,
      googleCalendarIds: Set<Long>,
    ): Set<Long> =
      googleCalendarIds.ifEmpty { calendars.map { it.id }.toSet() }
  }
}

package com.example.standby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.core.AuraPrefs
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
  val isLoading: Boolean = false,
)

/**
 * Coordinates on-device calendar loading, first-time Google calendar auto-selection,
 * and diagnostics reporting for both Stand By screens and Settings/Diagnostics.
 */
internal class StandByCalendarCoordinator(
  context: Context,
  private val prefs: AuraPrefs,
) : StandByDiagnosticsProvider {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val refreshMutex = Mutex()

  private val provider = DeviceCalendarProvider(
    context = appContext,
    windowProvider = { prefs.standByCalendarWindow.value },
  )

  private val _snapshot = MutableStateFlow(StandByCalendarSnapshot())
  val snapshot: StateFlow<StandByCalendarSnapshot> = _snapshot.asStateFlow()

  private val _diagnostics = MutableStateFlow(StandByDiagnostics())
  override val diagnostics: StateFlow<StandByDiagnostics> = _diagnostics.asStateFlow()

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

  /** Refreshes calendars, default Google selection if needed, and upcoming Stand By events. */
  suspend fun refreshSuspend() {
    refreshMutex.withLock {
      val granted = hasCalendarPermission()
      if (!granted) {
        _snapshot.value = StandByCalendarSnapshot(permissionGranted = false)
        _diagnostics.value = StandByDiagnostics(
          calendarPermissionGranted = false,
          calendarsFoundCount = 0,
          eventsFoundCount = 0,
          lastRefreshTimeLabel = _diagnostics.value.lastRefreshTimeLabel,
        )
        return
      }

      _snapshot.value = _snapshot.value.copy(permissionGranted = true, isLoading = true)
      val calendars = provider.listAvailableCalendars()

      if (calendars.isNotEmpty() && !prefs.hasInitializedCalendarSelection()) {
        val defaultGoogleIds = provider.findDefaultGoogleCalendarIds()
        prefs.setSelectedCalendarIds(defaultGoogleIds)
      }

      val selectedIds = prefs.selectedCalendarIds.value
      val events = if (prefs.standByCalendarEnabled.value && selectedIds.isNotEmpty()) {
        provider.getUpcomingEvents(
          selectedCalendarIds = selectedIds,
          limit = prefs.standByMaxEvents.value,
        )
      } else {
        emptyList()
      }

      val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
      _snapshot.value = StandByCalendarSnapshot(
        permissionGranted = true,
        calendars = calendars,
        events = events,
        isLoading = false,
      )
      _diagnostics.value = StandByDiagnostics(
        calendarPermissionGranted = true,
        calendarsFoundCount = calendars.size,
        eventsFoundCount = events.size,
        lastRefreshTimeLabel = timeLabel,
      )
    }
  }
}

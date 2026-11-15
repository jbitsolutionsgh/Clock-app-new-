package com.example.standby

/**
 * Metadata for a device calendar that can later be selected in Stand By settings.
 */
data class DeviceCalendarInfo(
  val id: Long,
  val displayName: String,
  val accountName: String,
  val colorArgb: Int,
)

/**
 * Lightweight upcoming calendar event summary for future Stand By display.
 */
data class CalendarEventSummary(
  val id: Long,
  val calendarId: Long,
  val title: String,
  val startEpochMs: Long,
  val endEpochMs: Long,
  val isAllDay: Boolean,
)

/**
 * Placeholder contract in the standby chiplet so a device-calendar provider
 * (with a calendar picker for which calendars to show) can be plugged in later.
 */
interface CalendarProvider {
  suspend fun listAvailableCalendars(): List<DeviceCalendarInfo>
  suspend fun getUpcomingEvents(selectedCalendarIds: Set<Long>, limit: Int = 5): List<CalendarEventSummary>
}

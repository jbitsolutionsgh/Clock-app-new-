package com.example.standby

import android.content.Context
import android.provider.CalendarContract
import com.example.core.CrashCatcher
import com.example.core.StandByCalendarWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/**
 * Queries the device's local CalendarContract database off the main thread.
 * Handles repeating instances, UTC-midnight all-day conversion, cancelled/declined filtering,
 * missing-permission SecurityExceptions, and records unexpected errors via CrashCatcher.
 */
internal class DeviceCalendarProvider(
  private val context: Context,
  private val windowProvider: () -> StandByCalendarWindow = { StandByCalendarWindow.TODAY_AND_TOMORROW },
) : CalendarProvider {

  private val appContext = context.applicationContext

  internal data class CalendarListResult(
    val calendars: List<DeviceCalendarInfo> = emptyList(),
    val googleCalendarIds: Set<Long> = emptySet(),
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
  )

  internal data class UpcomingEventsResult(
    val events: List<CalendarEventSummary> = emptyList(),
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
  )

  /** Queries visible device calendars for ID, display name, account name, and colour. */
  override suspend fun listAvailableCalendars(): List<DeviceCalendarInfo> =
    queryCalendarsWithStatus().calendars

  /** Returns IDs of visible calendars belonging to the phone's Google account. */
  suspend fun findDefaultGoogleCalendarIds(): Set<Long> =
    queryCalendarsWithStatus().googleCalendarIds

  /** Queries visible calendars along with Google-account IDs, permission status, and any error. */
  internal suspend fun queryCalendarsWithStatus(): CalendarListResult = withContext(Dispatchers.IO) {
    val projection = arrayOf(
      CalendarContract.Calendars._ID,
      CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
      CalendarContract.Calendars.ACCOUNT_NAME,
      CalendarContract.Calendars.CALENDAR_COLOR,
      CalendarContract.Calendars.ACCOUNT_TYPE,
    )
    val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
    val sortOrder =
      "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"

    val rows = mutableListOf<CalendarRow>()
    try {
      appContext.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder,
      )?.use { cursor ->
        val idxId = cursor.getColumnIndex(CalendarContract.Calendars._ID)
        val idxName = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val idxAccount = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
        val idxColor = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
        val idxType = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)

        while (cursor.moveToNext()) {
          val id = if (idxId >= 0) cursor.getLong(idxId) else continue
          val name = (if (idxName >= 0) cursor.getString(idxName) else null).orEmpty()
            .ifBlank { "Calendar" }
          val account = (if (idxAccount >= 0) cursor.getString(idxAccount) else null).orEmpty()
          val color = if (idxColor >= 0) cursor.getInt(idxColor) else DEFAULT_CALENDAR_COLOR
          val accountType = (if (idxType >= 0) cursor.getString(idxType) else null).orEmpty()

          rows.add(
            CalendarRow(
              info = DeviceCalendarInfo(
                id = id,
                displayName = name,
                accountName = account,
                colorArgb = color,
              ),
              accountType = accountType,
            ),
          )
        }
      }
    } catch (_: SecurityException) {
      return@withContext CalendarListResult(permissionDenied = true)
    } catch (e: Exception) {
      CrashCatcher.logNonFatal(appContext, "StandBy calendar list query failed", e)
      return@withContext CalendarListResult(
        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName,
      )
    }

    val googleIds = rows
      .filter { row ->
        row.accountType.equals(GOOGLE_ACCOUNT_TYPE, ignoreCase = true) ||
          row.info.accountName.endsWith("@gmail.com", ignoreCase = true) ||
          row.info.accountName.endsWith("@googlemail.com", ignoreCase = true)
      }
      .map { it.info.id }
      .toSet()

    CalendarListResult(
      calendars = rows.map { it.info },
      googleCalendarIds = googleIds,
    )
  }

  /** Queries upcoming non-cancelled, non-declined event instances within the configured window. */
  override suspend fun getUpcomingEvents(
    selectedCalendarIds: Set<Long>,
    limit: Int,
  ): List<CalendarEventSummary> =
    queryUpcomingEventsWithStatus(
      selectedCalendarIds = selectedCalendarIds,
      limit = limit,
      window = windowProvider(),
      nowMs = System.currentTimeMillis(),
      timeZone = TimeZone.getDefault(),
    ).events

  /** Internal query returning upcoming events together with permission and error status. */
  internal suspend fun queryUpcomingEventsWithStatus(
    selectedCalendarIds: Set<Long>,
    limit: Int,
    window: StandByCalendarWindow = windowProvider(),
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
  ): UpcomingEventsResult = withContext(Dispatchers.IO) {
    if (selectedCalendarIds.isEmpty() || limit <= 0) {
      return@withContext UpcomingEventsResult()
    }

    val (_, windowEndMs) = computeWindowBounds(nowMs, window, timeZone)
    val queryStartMs = startOfLocalDay(nowMs, timeZone) - ONE_DAY_MS
    val queryEndMs = windowEndMs + ONE_DAY_MS

    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.CALENDAR_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END,
      CalendarContract.Instances.ALL_DAY,
      CalendarContract.Instances.STATUS,
      CalendarContract.Instances.SELF_ATTENDEE_STATUS,
    )

    val rawRows = mutableListOf<RawInstanceRow>()
    try {
      CalendarContract.Instances.query(
        appContext.contentResolver,
        projection,
        queryStartMs,
        queryEndMs,
      )?.use { cursor ->
        val idxEventId = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
        val idxCalId = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
        val idxTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
        val idxBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
        val idxEnd = cursor.getColumnIndex(CalendarContract.Instances.END)
        val idxAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
        val idxStatus = cursor.getColumnIndex(CalendarContract.Instances.STATUS)
        val idxAttendee = cursor.getColumnIndex(CalendarContract.Instances.SELF_ATTENDEE_STATUS)

        while (cursor.moveToNext()) {
          val eventId = if (idxEventId >= 0) cursor.getLong(idxEventId) else 0L
          val calId = if (idxCalId >= 0) cursor.getLong(idxCalId) else -1L
          val title = if (idxTitle >= 0) cursor.getString(idxTitle).orEmpty() else ""
          val begin = if (idxBegin >= 0) cursor.getLong(idxBegin) else 0L
          val end = if (idxEnd >= 0) cursor.getLong(idxEnd) else begin
          val isAllDay = if (idxAllDay >= 0) cursor.getInt(idxAllDay) == 1 else false
          val status = if (idxStatus >= 0) cursor.getInt(idxStatus) else CalendarContract.Events.STATUS_CONFIRMED
          val attendeeStatus = if (idxAttendee >= 0) cursor.getInt(idxAttendee) else CalendarContract.Attendees.ATTENDEE_STATUS_NONE

          rawRows.add(
            RawInstanceRow(
              eventId = eventId,
              calendarId = calId,
              title = title,
              beginMs = begin,
              endMs = end,
              isAllDay = isAllDay,
              isCancelled = status == CalendarContract.Events.STATUS_CANCELED,
              isDeclined = attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
            ),
          )
        }
      }
    } catch (_: SecurityException) {
      return@withContext UpcomingEventsResult(permissionDenied = true)
    } catch (e: Exception) {
      CrashCatcher.logNonFatal(appContext, "StandBy upcoming events query failed", e)
      return@withContext UpcomingEventsResult(
        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName,
      )
    }

    UpcomingEventsResult(
      events = filterAndSortEvents(
        rows = rawRows,
        selectedCalendarIds = selectedCalendarIds,
        limit = limit,
        window = window,
        nowMs = nowMs,
        timeZone = timeZone,
      ),
    )
  }

  private data class CalendarRow(
    val info: DeviceCalendarInfo,
    val accountType: String,
  )

  internal data class RawInstanceRow(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val isAllDay: Boolean,
    val isCancelled: Boolean,
    val isDeclined: Boolean,
  )

  companion object {
    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val DEFAULT_CALENDAR_COLOR = 0xFF3B82F6.toInt()

    /** Computes [todayStartMs, windowEndMs] in local timezone. */
    internal fun computeWindowBounds(
      nowMs: Long,
      window: StandByCalendarWindow,
      timeZone: TimeZone = TimeZone.getDefault(),
    ): Pair<Long, Long> {
      val todayStart = startOfLocalDay(nowMs, timeZone)
      val daysAhead = when (window) {
        StandByCalendarWindow.TODAY_ONLY -> 1
        StandByCalendarWindow.TODAY_AND_TOMORROW -> 2
        StandByCalendarWindow.NEXT_7_DAYS -> 7
      }
      val cal = Calendar.getInstance(timeZone).apply {
        timeInMillis = todayStart
        add(Calendar.DAY_OF_YEAR, daysAhead)
      }
      return todayStart to (cal.timeInMillis - 1L)
    }

    /** Returns local midnight (00:00:00.000) for the given timestamp. */
    internal fun startOfLocalDay(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
      return Calendar.getInstance(timeZone).apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }.timeInMillis
    }

    /**
     * Converts Android's UTC-midnight all-day [beginUtcMs, endUtcMs) into local midnight timestamps
     * so all-day events appear on the correct local calendar date.
     */
    internal fun convertUtcAllDayToLocalRange(
      beginUtcMs: Long,
      endUtcMs: Long,
      localTimeZone: TimeZone = TimeZone.getDefault(),
    ): Pair<Long, Long> {
      val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
      utcCal.timeInMillis = beginUtcMs
      val startYear = utcCal.get(Calendar.YEAR)
      val startMonth = utcCal.get(Calendar.MONTH)
      val startDay = utcCal.get(Calendar.DAY_OF_MONTH)

      val localStart = Calendar.getInstance(localTimeZone).apply {
        clear()
        set(startYear, startMonth, startDay, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
      }.timeInMillis

      utcCal.timeInMillis = endUtcMs
      val endYear = utcCal.get(Calendar.YEAR)
      val endMonth = utcCal.get(Calendar.MONTH)
      val endDay = utcCal.get(Calendar.DAY_OF_MONTH)

      var localEnd = Calendar.getInstance(localTimeZone).apply {
        clear()
        set(endYear, endMonth, endDay, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
      }.timeInMillis

      if (localEnd <= localStart) {
        localEnd = Calendar.getInstance(localTimeZone).apply {
          timeInMillis = localStart
          add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
      }
      return localStart to localEnd
    }

    /**
     * Filters raw CalendarContract instance rows, converts all-day UTC dates to local dates,
     * drops ended/cancelled/declined events, places all-day events first within each day,
     * and returns up to [limit] summaries sorted chronologically.
     */
    internal fun filterAndSortEvents(
      rows: List<RawInstanceRow>,
      selectedCalendarIds: Set<Long>,
      limit: Int,
      window: StandByCalendarWindow,
      nowMs: Long,
      timeZone: TimeZone = TimeZone.getDefault(),
    ): List<CalendarEventSummary> {
      val (todayStartMs, windowEndMs) = computeWindowBounds(nowMs, window, timeZone)

      return rows
        .asSequence()
        .filter { it.calendarId in selectedCalendarIds }
        .filter { !it.isCancelled && !it.isDeclined }
        .map { row ->
          val (startMs, endMs) = if (row.isAllDay) {
            convertUtcAllDayToLocalRange(row.beginMs, row.endMs, timeZone)
          } else {
            row.beginMs to row.endMs
          }
          // For multi-day all-day events already underway today, clamp display day to today so they lead today's list.
          val effectiveDisplayStart = if (row.isAllDay && startMs < todayStartMs && endMs > nowMs) {
            todayStartMs
          } else {
            startMs
          }
          CalendarEventSummary(
            id = row.eventId,
            calendarId = row.calendarId,
            title = row.title.ifBlank { "(No title)" },
            startEpochMs = effectiveDisplayStart,
            endEpochMs = endMs,
            isAllDay = row.isAllDay,
          )
        }
        .filter { event ->
          event.endEpochMs > nowMs && event.startEpochMs <= windowEndMs
        }
        .sortedWith(
          compareBy<CalendarEventSummary>(
            { startOfLocalDay(it.startEpochMs, timeZone) },
            { !it.isAllDay },
            { it.startEpochMs },
            { it.title.lowercase() },
          ),
        )
        .take(limit)
        .toList()
    }
  }
}

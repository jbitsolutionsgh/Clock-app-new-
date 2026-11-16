package com.example

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.AuraPrefs
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.core.StandByCalendarWindow
import com.example.earbud.MediaKeyRouter
import com.example.faces.DialThumbnailCache
import com.example.faces.FacesChiplet
import com.example.faces.WatchFaceRegistry
import com.example.standby.DeviceCalendarInfo
import com.example.standby.DeviceCalendarProvider
import com.example.standby.StandByCalendarCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `app name matches AuraClock`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("AuraClock", context.getString(R.string.app_name))
  }

  @Test
  fun `prefs persist presets rounds and theme color`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    prefs.updateQuickPreset(0, 12)
    prefs.setRoundsMode(true)
    prefs.setRoundGapSeconds(2)
    prefs.setThemeColor(AuraThemeColor.GOLD)

    val reloaded = AuraPrefs(context)
    assertEquals(12, reloaded.quickPresets.value[0])
    assertTrue(reloaded.roundsMode.value)
    assertEquals(2, reloaded.roundGapSeconds.value)
    assertEquals(AuraThemeColor.GOLD, reloaded.themeColor.value)
  }

  @Test
  fun `standby calendar prefs have expected defaults and persist`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    assertTrue(prefs.standByCalendarEnabled.value)
    assertEquals(4, prefs.standByMaxEvents.value)
    assertEquals(StandByCalendarWindow.TODAY_AND_TOMORROW, prefs.standByCalendarWindow.value)
    assertTrue(prefs.standByShowEventTitles.value)

    prefs.setStandByMaxEvents(6)
    prefs.setStandByCalendarWindow(StandByCalendarWindow.NEXT_7_DAYS)
    prefs.setStandByShowEventTitles(false)
    prefs.setSelectedCalendarIds(setOf(10L, 25L))

    val reloaded = AuraPrefs(context)
    assertEquals(6, reloaded.standByMaxEvents.value)
    assertEquals(StandByCalendarWindow.NEXT_7_DAYS, reloaded.standByCalendarWindow.value)
    assertFalse(reloaded.standByShowEventTitles.value)
    assertEquals(setOf(10L, 25L), reloaded.selectedCalendarIds.value)
    assertTrue(reloaded.hasInitializedCalendarSelection())
  }

  @Test
  fun `media key router dispatches play pause immediately when double press disabled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    prefs.setPlayPauseActionId(AuraPrefs.ACTION_SPEAK_TIME)
    prefs.setPlayPauseDoubleActionId(AuraPrefs.ACTION_NONE)

    var recordedKey = ""
    var recordedAction = ""
    val router = MediaKeyRouter(context, prefs) { keyLabel, _, actionLabel ->
      recordedKey = keyLabel
      recordedAction = actionLabel
    }

    val handled = router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    assertTrue(handled)
    assertTrue(recordedKey.contains("KEYCODE_MEDIA_PLAY_PAUSE"))
    assertEquals("Speak current time", recordedAction)
  }

  @Test
  fun `face navigator cycles through all eleven registered faces in exact order`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = CoreSingletons.prefs(context)
    val nav = FacesChiplet.ensureInitialized(context)
    val options = nav.getFaceOptions()
    assertEquals(11, options.size)
    assertEquals("motorsport_m", options.first().first)
    assertEquals("speedmaster_racing", options[1].first)
    assertEquals("face_nike_dots", options[2].first)
    assertEquals("face_wood_art", options.last().first)
    assertEquals("Workshop Wood", options.last().second)

    // StandBy digital face remains resolvable even though it is not in the 11-face picker list.
    assertEquals("standby_digital", WatchFaceRegistry.findById("standby_digital").id)

    prefs.setSelectedFaceId(options.first().first)
    nav.nextFace()
    assertEquals(options[1].first, prefs.selectedFaceId.value)
  }

  @Test
  fun `dial thumbnails decode at 160px or smaller for all eleven nodpi drawables`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resIds = listOf(
      R.drawable.face_motorsport_m,
      R.drawable.face_speedmaster_racing,
      R.drawable.face_nike_dots,
      R.drawable.face_nike_numerals,
      R.drawable.face_yellow_racing,
      R.drawable.face_lacoste_green,
      R.drawable.face_casio_leather,
      R.drawable.face_bell_ross,
      R.drawable.face_seiko_blue,
      R.drawable.face_phantom_black,
      R.drawable.face_wood_art,
    )
    resIds.forEach { resId ->
      val bmp = DialThumbnailCache.decodeDownsampled(context.resources, resId, 160)
      assertTrue("Thumbnail decoded null for $resId", bmp != null)
      assertTrue("Width exceeded 160px: ${bmp!!.width}", bmp.width <= 160)
      assertTrue("Height exceeded 160px: ${bmp.height}", bmp.height <= 160)
      bmp.recycle()
    }
  }

  @Test
  fun `all day UTC midnight converts to local calendar day`() {
    val pst = TimeZone.getTimeZone("America/Los_Angeles")
    val utc = TimeZone.getTimeZone("UTC")
    val beginUtc = Calendar.getInstance(utc).apply {
      clear()
      set(2026, Calendar.NOVEMBER, 15, 0, 0, 0)
    }.timeInMillis
    val endUtc = Calendar.getInstance(utc).apply {
      clear()
      set(2026, Calendar.NOVEMBER, 16, 0, 0, 0)
    }.timeInMillis

    val (localStart, localEnd) = DeviceCalendarProvider.convertUtcAllDayToLocalRange(beginUtc, endUtc, pst)
    val localCal = Calendar.getInstance(pst).apply { timeInMillis = localStart }
    assertEquals(2026, localCal.get(Calendar.YEAR))
    assertEquals(Calendar.NOVEMBER, localCal.get(Calendar.MONTH))
    assertEquals(15, localCal.get(Calendar.DAY_OF_MONTH))
    assertEquals(0, localCal.get(Calendar.HOUR_OF_DAY))

    val localEndCal = Calendar.getInstance(pst).apply { timeInMillis = localEnd }
    assertEquals(16, localEndCal.get(Calendar.DAY_OF_MONTH))
    assertEquals(0, localEndCal.get(Calendar.HOUR_OF_DAY))
  }

  @Test
  fun `filterAndSortEvents puts all day first skips declined cancelled and ended`() {
    val pst = TimeZone.getTimeZone("America/Los_Angeles")
    val utc = TimeZone.getTimeZone("UTC")
    val nowMs = Calendar.getInstance(pst).apply {
      clear()
      set(2026, Calendar.NOVEMBER, 15, 19, 0, 0)
    }.timeInMillis

    val todayAllDayBeginUtc = Calendar.getInstance(utc).apply {
      clear()
      set(2026, Calendar.NOVEMBER, 15, 0, 0, 0)
    }.timeInMillis
    val todayAllDayEndUtc = Calendar.getInstance(utc).apply {
      clear()
      set(2026, Calendar.NOVEMBER, 16, 0, 0, 0)
    }.timeInMillis

    val endedTimedStart = nowMs - 3_600_000L
    val endedTimedEnd = nowMs - 1_800_000L
    val ongoingStart = nowMs - 600_000L
    val ongoingEnd = nowMs + 1_800_000L
    val laterTodayStart = nowMs + 3_600_000L
    val laterTodayEnd = nowMs + 7_200_000L

    val rows = listOf(
      DeviceCalendarProvider.RawInstanceRow(1L, 100L, "Ended", endedTimedStart, endedTimedEnd, false, false, false),
      DeviceCalendarProvider.RawInstanceRow(2L, 100L, "Cancelled", laterTodayStart, laterTodayEnd, false, true, false),
      DeviceCalendarProvider.RawInstanceRow(3L, 100L, "Declined", laterTodayStart, laterTodayEnd, false, false, true),
      DeviceCalendarProvider.RawInstanceRow(4L, 200L, "Unselected Cal", laterTodayStart, laterTodayEnd, false, false, false),
      DeviceCalendarProvider.RawInstanceRow(5L, 100L, "Staff Meeting", laterTodayStart, laterTodayEnd, false, false, false),
      DeviceCalendarProvider.RawInstanceRow(6L, 100L, "Ongoing Class", ongoingStart, ongoingEnd, false, false, false),
      DeviceCalendarProvider.RawInstanceRow(7L, 100L, "Teacher In-Service", todayAllDayBeginUtc, todayAllDayEndUtc, true, false, false),
    )

    val result = DeviceCalendarProvider.filterAndSortEvents(
      rows = rows,
      selectedCalendarIds = setOf(100L),
      limit = 4,
      window = StandByCalendarWindow.TODAY_AND_TOMORROW,
      nowMs = nowMs,
      timeZone = pst,
    )

    assertEquals(listOf("Teacher In-Service", "Ongoing Class", "Staff Meeting"), result.map { it.title })
  }

  @Test
  fun `device calendar provider returns empty safely without permission`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val provider = DeviceCalendarProvider(context)
    val calendars = provider.listAvailableCalendars()
    val events = provider.getUpcomingEvents(setOf(1L), 4)
    assertTrue(calendars.isEmpty())
    assertTrue(events.isEmpty())
  }

  @Test
  fun `default calendar selection falls back to all visible calendars when no google calendars`() {
    val visible = listOf(
      DeviceCalendarInfo(11L, "Local Work", "local@device", 0xFF3B82F6.toInt()),
      DeviceCalendarInfo(22L, "School Timetable", "caldav@school.edu", 0xFF10B981.toInt()),
    )
    val selectedNoGoogle = StandByCalendarCoordinator.resolveDefaultCalendarSelection(
      calendars = visible,
      googleCalendarIds = emptySet(),
    )
    assertEquals(setOf(11L, 22L), selectedNoGoogle)

    val selectedWithGoogle = StandByCalendarCoordinator.resolveDefaultCalendarSelection(
      calendars = visible,
      googleCalendarIds = setOf(22L),
    )
    assertEquals(setOf(22L), selectedWithGoogle)
  }

  @Test
  fun `empty calendar selection is never saved as initialized`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    prefs.setSelectedCalendarIds(emptySet())
    assertFalse(prefs.hasInitializedCalendarSelection())

    prefs.setSelectedCalendarIds(setOf(7L))
    assertTrue(prefs.hasInitializedCalendarSelection())
  }

  @Test
  fun `standby coordinator records dream metrics and crash catcher records non fatal error`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    val coordinator = StandByCalendarCoordinator(context, prefs)

    coordinator.recordDreamStarted("21:15:00")
    coordinator.recordDreamEventsShown(3)
    coordinator.refreshSuspend()

    assertEquals("21:15:00", coordinator.diagnostics.value.standByLastStartedLabel)
    assertEquals(3, coordinator.diagnostics.value.standByLastEventCount)

    CrashCatcher.logNonFatal(context, "Test Calendar Error", IllegalStateException("Provider unavailable"))
    assertTrue(CrashCatcher.hasCrashLog(context))
    assertTrue(CrashCatcher.readLastCrashLog(context).orEmpty().contains("Provider unavailable"))
  }

  @Test
  fun `fullscreen timer preferences default false and persist`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    assertFalse(prefs.timerFullScreenByDefault.value)
    assertFalse(prefs.timerGoFullScreenOnStart.value)

    prefs.setTimerFullScreenByDefault(true)
    prefs.setTimerGoFullScreenOnStart(true)

    val reloaded = AuraPrefs(context)
    assertTrue(reloaded.timerFullScreenByDefault.value)
    assertTrue(reloaded.timerGoFullScreenOnStart.value)
  }
}

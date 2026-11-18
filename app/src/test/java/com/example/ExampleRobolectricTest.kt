package com.example

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.AuraPrefs
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.core.FaceBatteryData
import com.example.core.FaceData
import com.example.core.FaceDataNeed
import com.example.core.FaceRenderMode
import com.example.core.FaceTimeData
import com.example.core.FaceTimerData
import com.example.core.FaceUpdatePolicy
import com.example.core.NextCalendarEvent
import com.example.core.StandByCalendarWindow
import com.example.core.TimerPhase
import com.example.earbud.MediaKeyRouter
import com.example.faces.DialThumbnailCache
import com.example.faces.FacesChiplet
import com.example.faces.WatchFaceRegistry
import com.example.faces.engine.ColorSpec
import com.example.faces.engine.DefinitionFace
import com.example.faces.engine.FaceAssetRepository
import com.example.faces.engine.FaceDefinitionParser
import com.example.faces.engine.FaceEngine
import com.example.faces.engine.FaceMath
import com.example.faces.engine.GaugeSource
import com.example.faces.engine.HandLayer
import com.example.faces.engine.HandSource
import com.example.faces.engine.ShowCondition
import com.example.standby.DeviceCalendarInfo
import com.example.standby.DeviceCalendarProvider
import com.example.standby.StandByCalendarCoordinator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
  fun `face navigator cycles through all thirteen registered faces including ported and new definitions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = CoreSingletons.prefs(context)
    val nav = FacesChiplet.ensureInitialized(context)
    val options = nav.getFaceOptions()
    assertEquals(13, options.size)
    assertEquals("motorsport_m", options.first().first)
    assertEquals("speedmaster_racing", options[1].first)
    assertEquals("face_nike_dots", options[2].first)
    assertEquals("face_wood_art", options[10].first)
    assertEquals("big_digital", options[11].first)
    assertEquals("minimal_plus", options[12].first)

    // Flagship ported faces must be DefinitionFace instances driven by assets/faces/*.json.
    assertTrue(WatchFaceRegistry.findById("motorsport_m") is DefinitionFace)
    assertTrue(WatchFaceRegistry.findById("speedmaster_racing") is DefinitionFace)
    assertTrue(WatchFaceRegistry.findById("big_digital") is DefinitionFace)
    assertTrue(WatchFaceRegistry.findById("minimal_plus") is DefinitionFace)

    // StandBy digital face remains resolvable even though it is not in the picker list.
    assertEquals("standby_digital", WatchFaceRegistry.findById("standby_digital").id)

    prefs.setSelectedFaceId(options.first().first)
    nav.nextFace()
    assertEquals(options[1].first, prefs.selectedFaceId.value)
  }

  @Test
  fun `all bundled json watch face definitions load without errors`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    FaceAssetRepository.ensureInitialized(context)
    val diag = FaceAssetRepository.diagnostics.value
    assertTrue("Expected zero definition errors, got: ${diag.errors}", diag.errors.isEmpty())
    val loadedIds = diag.loadedDefinitions.map { it.first }.toSet()
    assertTrue(loadedIds.containsAll(listOf("motorsport_m", "speedmaster_racing", "big_digital", "minimal_plus")))
  }

  @Test
  fun `face definition parser handles valid and invalid json safely without throwing`() {
    val validJson = """
      {
        "id": "custom_clean",
        "name": "Custom Clean",
        "canvas": "circle",
        "layers": [
          { "id": "bg", "type": "shape", "shape": "ring", "radius": 0.9, "strokeColor": "@primary" },
          { "id": "hr", "type": "hand", "source": "hour", "length": 0.5, "widthDp": 5, "bodyColor": "#FFFFFF" },
          { "id": "min", "type": "hand", "source": "minute", "length": 0.75, "widthDp": 4, "bodyColor": "@onSurface" }
        ]
      }
    """.trimIndent()
    val validResult = FaceDefinitionParser.parse(validJson)
    assertTrue(validResult is FaceDefinitionParser.ParseResult.Success)
    val def = (validResult as FaceDefinitionParser.ParseResult.Success).definition
    assertEquals("custom_clean", def.id)
    assertEquals(3, def.layers.size)
    // No seconds layer -> updatePolicy drops to EVERY_MINUTE!
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, def.updatePolicy(FaceRenderMode.NORMAL))
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, def.updatePolicy(FaceRenderMode.AMBIENT))
    assertEquals(setOf(FaceDataNeed.TIME), def.dataNeeds)

    val malformed = FaceDefinitionParser.parse("{ not valid json")
    assertTrue(malformed is FaceDefinitionParser.ParseResult.Error)

    val missingLayers = FaceDefinitionParser.parse("""{ "id": "x", "name": "X" }""")
    assertTrue(missingLayers is FaceDefinitionParser.ParseResult.Error)

    val badLayerType = FaceDefinitionParser.parse(
      """{ "id": "x", "name": "X", "layers": [ { "type": "unknown" } ] }""",
    )
    assertTrue(badLayerType is FaceDefinitionParser.ParseResult.Error)

    val badColor = FaceDefinitionParser.parse(
      """{ "id": "x", "name": "X", "layers": [ { "type": "hand", "source": "hour", "length": 0.5, "bodyColor": "@nope" } ] }""",
    )
    assertTrue(badColor is FaceDefinitionParser.ParseResult.Error)
  }

  @Test
  fun `big digital and minimal plus downgrade update frequency cleanly in ambient mode`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    FaceAssetRepository.ensureInitialized(context)

    val bigDigital = FaceAssetRepository.getById("big_digital")
    assertNotNull(bigDigital)
    assertEquals(FaceUpdatePolicy.EVERY_SECOND, bigDigital!!.updatePolicy(FaceRenderMode.NORMAL))
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, bigDigital.updatePolicy(FaceRenderMode.AMBIENT))
    assertTrue(bigDigital.dataNeeds.containsAll(setOf(FaceDataNeed.TIME, FaceDataNeed.BATTERY, FaceDataNeed.NEXT_EVENT)))

    val minimalPlus = FaceAssetRepository.getById("minimal_plus")
    assertNotNull(minimalPlus)
    assertEquals(FaceUpdatePolicy.SMOOTH_FRAME, minimalPlus!!.updatePolicy(FaceRenderMode.NORMAL))
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, minimalPlus.updatePolicy(FaceRenderMode.AMBIENT))
  }

  @Test
  fun `face math computes exact hand angles gauges conditions and templates`() {
    val time = FaceTimeData(
      hour12 = 3,
      hour24 = 15,
      minute = 30,
      second = 15,
      millis = 500,
      is24Hour = false,
      amPm = "PM",
      dayOfMonth = 5,
      dayNameShort = "THU",
      monthNameShort = "MAR",
    )
    // 3:30:15 -> hour = (3 + 30.25/60) * 30 = 105.125 deg, minute = 30.25 * 6 = 181.5 deg, second = 15 * 6 = 90 deg
    val (hDiscrete, mDiscrete, sDiscrete) = FaceMath.timeHandAngles(time, smoothSeconds = false)
    assertEquals(105.125f, hDiscrete, 0.01f)
    assertEquals(181.5f, mDiscrete, 0.01f)
    assertEquals(90f, sDiscrete, 0.01f)

    // Smooth seconds adds 0.5s -> 93 deg
    val (_, _, sSmooth) = FaceMath.timeHandAngles(time, smoothSeconds = true)
    assertEquals(93f, sSmooth, 0.01f)

    val data = FaceData(
      time = time,
      battery = FaceBatteryData(levelPercent = 50, isCharging = true),
      nextEvent = NextCalendarEvent("Staff Sync", 0L, 0L, isAllDay = true),
      timer = FaceTimerData(phase = TimerPhase.RUNNING, remainingMs = 65_000L, totalDurationMs = 120_000L, progress = 0.5f, roundNumber = 2),
    )

    val batteryHand = HandLayer(
      id = "bat",
      source = HandSource.BATTERY,
      length = 0.2f,
      widthDp = 2f,
      bodyColor = ColorSpec.Token("primary"),
      minValue = 0f,
      maxValue = 100f,
      startAngle = -120f,
      endAngle = 120f,
    )
    assertEquals(0f, FaceMath.handAngleDegrees(batteryHand, data, FaceRenderMode.NORMAL), 0.01f)
    assertEquals(0.5f, FaceMath.gaugeFraction(GaugeSource.BATTERY, data), 0.001f)

    assertTrue(FaceMath.isConditionMet(ShowCondition.TimerRunning, data, FaceRenderMode.NORMAL))
    assertTrue(FaceMath.isConditionMet(ShowCondition.HasNextEvent, data, FaceRenderMode.NORMAL))
    assertTrue(FaceMath.isConditionMet(ShowCondition.Charging, data, FaceRenderMode.NORMAL))
    assertFalse(FaceMath.isConditionMet(ShowCondition.BatteryBelow(20), data, FaceRenderMode.NORMAL))
    assertTrue(FaceMath.isConditionMet(ShowCondition.IsAmbient, data, FaceRenderMode.AMBIENT))

    val formatted = FaceMath.formatTemplate("{hour}:{minute} {ampm} | {nextEventTime} {nextEventTitle} | {timerRemaining} {timerRound}", data)
    assertEquals("3:30 PM | All day Staff Sync | 01:05 R2", formatted)
  }

  @Test
  fun `formatTemplate returns empty string for ampm when device is in 24 hour mode`() {
    val time12 = FaceTimeData(hour12 = 3, hour24 = 15, minute = 5, is24Hour = false, amPm = "PM")
    assertEquals("3:05 PM", FaceMath.formatTemplate("{hour}:{minute} {ampm}", FaceData(time = time12)))

    val time24 = time12.copy(is24Hour = true)
    assertEquals("15:05 ", FaceMath.formatTemplate("{hour}:{minute} {ampm}", FaceData(time = time24)))
    assertEquals("", FaceMath.formatTemplate("{ampm}", FaceData(time = time24)))
  }

  @Test
  fun `dial bitmaps decode no larger than drawn pixel size and smooth frame refreshes day fields`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bmp = FaceEngine.decodeBitmapForDialSize(context.resources, R.drawable.face_motorsport_m, 320)
    assertNotNull(bmp)
    assertTrue("Width exceeded dial pixel size: ${bmp!!.width}", bmp.width <= 320)
    assertTrue("Height exceeded dial pixel size: ${bmp.height}", bmp.height <= 320)

    FaceAssetRepository.ensureInitialized(context)
    val def = FaceAssetRepository.getById("motorsport_m")!!
    val face = DefinitionFace(def)
    val target = FaceTimeData(dayOfMonth = 15, dayNameShort = "SUN", monthNameShort = "NOV")
    val nextDayCal = Calendar.getInstance().apply {
      set(2026, Calendar.DECEMBER, 1, 0, 0, 5)
    }
    face.updateTimeInPlace(nextDayCal, target, refreshCalendarDay = true)
    assertEquals(1, target.dayOfMonth)
    assertEquals("TUE", target.dayNameShort)
    assertEquals("DEC", target.monthNameShort)
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

  @Test
  fun `wff xml parser loads sample face records skipped elements and rejects binary xml`() {
    val xmlStream = javaClass.classLoader!!.getResourceAsStream("wff_sample_analog_digital.xml")
    assertNotNull("Expected test resource wff_sample_analog_digital.xml", xmlStream)
    val xmlText = xmlStream!!.bufferedReader().use { it.readText() }

    val result = com.example.faces.wff.WffXmlParser.parse(xmlText, "wff_test", "Fallback")
    assertTrue(result is com.example.faces.wff.WffParseResult.Success)
    val face = (result as com.example.faces.wff.WffParseResult.Success).face

    assertEquals("Classroom Hybrid WFF", face.name)
    assertEquals(450f, face.width, 0.01f)
    assertEquals(450f, face.height, 0.01f)
    assertEquals(com.example.faces.wff.WffClipShape.CIRCLE, face.clipShape)
    assertTrue(face.report.drawnElements.containsAll(listOf("WatchFace", "Scene", "AnalogClock", "HourHand", "SecondHand", "DigitalClock", "PartDraw", "PartText", "PartImage", "ComplicationSlot")))
    assertEquals(0, face.report.skippedItems.size)

    val unknownTagXml = """<WatchFace width="450" height="450"><Scene><Launch /></Scene></WatchFace>"""
    val unknownParsed = (com.example.faces.wff.WffXmlParser.parse(unknownTagXml, "u", "U") as com.example.faces.wff.WffParseResult.Success).face
    assertEquals(1, unknownParsed.report.skippedItems.size)
    assertEquals("Launch", unknownParsed.report.skippedItems.first().element)

    assertTrue(com.example.faces.wff.WffXmlParser.isBinaryXml(byteArrayOf(0x03, 0x00, 0x08, 0x00)))
    assertFalse(com.example.faces.wff.WffXmlParser.isBinaryXml(xmlText.toByteArray()))
    assertTrue(com.example.faces.wff.WffXmlParser.parse("<OtherRoot />", "x", "x") is com.example.faces.wff.WffParseResult.Error)
  }

  @Test
  fun `wff expression evaluator handles time battery templates formatting and ambient cadence`() {
    val time = FaceTimeData(
      hour12 = 3,
      hour24 = 15,
      minute = 30,
      second = 45,
      millis = 500,
      is24Hour = true,
      amPm = "PM",
      dayOfMonth = 9,
      dayNameShort = "MON",
      monthNameShort = "NOV",
      year = 2026,
      monthNumber = 11,
      dayOfWeek = Calendar.MONDAY,
    )
    val data = FaceData(
      time = time,
      battery = FaceBatteryData(levelPercent = 65, isCharging = true),
    )

    val angleExpr = com.example.faces.wff.WffExpression.compile("([HOUR_0_11] * 30) + ([MINUTE] * 0.5)")
    assertEquals(105f, com.example.faces.wff.WffExpression.evaluateFloat(angleExpr, data), 0.01f)

    val cmpExpr = com.example.faces.wff.WffExpression.compile("[IS_BATTERY_CHARGING] == 1 && [BATTERY_PERCENT] > 50")
    assertEquals(1f, com.example.faces.wff.WffExpression.evaluateFloat(cmpExpr, data), 0.01f)

    val evalCtx = com.example.faces.wff.WffEvalContext(data = data)
    val sb = StringBuilder()
    com.example.faces.wff.WffExpression.formatTemplate(
      template = "%s %02d - %d%%",
      parameters = listOf(
        com.example.faces.wff.WffExpression.compile("[DAY_OF_WEEK_S]"),
        com.example.faces.wff.WffExpression.compile("[DAY]"),
        com.example.faces.wff.WffExpression.compile("[BATTERY_PERCENT]"),
      ),
      ctx = evalCtx,
      out = sb,
    )
    assertEquals("Mon 09 - 65%", sb.toString())

    com.example.faces.wff.WffExpression.formatTimeText("HH:mm:ss", "24", data, isAmbient = false, out = sb)
    assertEquals("15:30:45", sb.toString())
    com.example.faces.wff.WffExpression.formatTimeText("HH:mm:ss", "24", data, isAmbient = true, out = sb)
    assertEquals("15:30", sb.toString())

    val xmlText = javaClass.classLoader!!.getResourceAsStream("wff_sample_analog_digital.xml")!!.bufferedReader().use { it.readText() }
    val face = (com.example.faces.wff.WffXmlParser.parse(xmlText, "wff_cadence", "WFF") as com.example.faces.wff.WffParseResult.Success).face
    assertEquals(FaceUpdatePolicy.SMOOTH_FRAME, com.example.faces.wff.WffExpression.updatePolicy(face, FaceRenderMode.NORMAL))
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, com.example.faces.wff.WffExpression.updatePolicy(face, FaceRenderMode.AMBIENT))
  }

  @Test
  fun `arsc reader resolves resource names to archive paths`() {
    val arscBytes = buildMinimalTestArsc(
      globalStrings = listOf("res/ab.webp", "res/cd.png"),
      typeName = "drawable",
      entries = listOf("sample_dial_bg" to 0, "sample_second_hand" to 1),
    )
    val resolved = com.example.faces.wff.ArscResourceTableReader.readDrawableResourcePaths(arscBytes)
    assertEquals("res/ab.webp", resolved["sample_dial_bg"])
    assertEquals("res/cd.png", resolved["sample_second_hand"])
  }

  @Test
  fun `wff storage imports archive with arsc fallback registers face and deletes cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = CoreSingletons.prefs(context)
    val xmlText = javaClass.classLoader!!.getResourceAsStream("wff_sample_analog_digital.xml")!!.bufferedReader().use { it.readText() }

    val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
    val pngOut = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, pngOut)
    val pngBytes = pngOut.toByteArray()
    bmp.recycle()

    val arscBytes = buildMinimalTestArsc(
      globalStrings = listOf("res/zz.png"),
      typeName = "drawable",
      entries = listOf("sample_dial_bg" to 0),
    )

    val zipOut = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(zipOut).use { zos ->
      zos.putNextEntry(java.util.zip.ZipEntry("res/raw/watchface.xml"))
      zos.write(xmlText.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // Named entry under res/drawable/
      zos.putNextEntry(java.util.zip.ZipEntry("res/drawable/sample_hour_hand.png"))
      zos.write(pngBytes)
      zos.closeEntry()

      // Renamed entry resolved only via resources.arsc fallback
      zos.putNextEntry(java.util.zip.ZipEntry("res/zz.png"))
      zos.write(pngBytes)
      zos.closeEntry()

      zos.putNextEntry(java.util.zip.ZipEntry("resources.arsc"))
      zos.write(arscBytes)
      zos.closeEntry()
    }

    val importResult = com.example.faces.wff.WffStorageManager.importFromStream(
      context = context,
      sourceFileName = "hybrid_sample.apk",
      inputStream = java.io.ByteArrayInputStream(zipOut.toByteArray()),
    )
    assertTrue(importResult is com.example.faces.wff.WffImportResult.Success)
    val stored = (importResult as com.example.faces.wff.WffImportResult.Success).entry

    // Both named and resources.arsc-mapped images resolved; missing minute/second hands recorded cleanly.
    assertTrue(stored.resourceFileMap.containsKey("sample_hour_hand"))
    assertTrue(stored.resourceFileMap.containsKey("sample_dial_bg"))
    assertTrue(stored.report.missingResources.containsAll(listOf("sample_minute_hand", "sample_second_hand")))

    WatchFaceRegistry.init(context)
    assertTrue(WatchFaceRegistry.faces.any { it.id == stored.id })
    assertTrue(FaceAssetRepository.diagnostics.value.wffFaces.any { it.id == stored.id })

    prefs.setSelectedFaceId(stored.id)
    assertTrue(WatchFaceRegistry.deleteWff(context, stored.id))
    assertFalse(WatchFaceRegistry.faces.any { it.id == stored.id })
    assertFalse(prefs.selectedFaceId.value == stored.id)
  }

  @Test
  fun `wff expression engine covers operators math string conversion caching and unknown fallback`() {
    val time = FaceTimeData(
      hour12 = 9, hour24 = 21, minute = 45, second = 12, millis = 250,
      is24Hour = true, amPm = "PM", dayOfMonth = 7, year = 2026, monthNumber = 11, dayOfWeek = Calendar.SATURDAY,
    )
    val data = FaceData(time = time, battery = FaceBatteryData(levelPercent = 82, isCharging = false))
    val ctx = com.example.faces.wff.WffEvalContext(data = data).apply { syncFromData(data) }

    // Operators: bitwise, shift, ternary, arithmetic, comparison
    val opExpr = com.example.faces.wff.WffExpression.compile("((1 << 3) | 4) & 12 == 12 ? (10 % 3) + 5 : 0")
    assertEquals(6.0, com.example.faces.wff.WffExpression.evaluate(opExpr, ctx).asDouble(), 0.001)
    assertEquals(0.0, com.example.faces.wff.WffExpression.evaluate(com.example.faces.wff.WffExpression.compile("~(0) + 1"), ctx).asDouble(), 0.001)

    // Math, rounding & trig functions
    val mathExpr = com.example.faces.wff.WffExpression.compile("round(fract(3.75) * 10) + clamp(25, 0, 10) + abs(-4) + sqrt(16)")
    assertEquals(26.0, com.example.faces.wff.WffExpression.evaluate(mathExpr, ctx).asDouble(), 0.001)
    val trigExpr = com.example.faces.wff.WffExpression.compile("round(cos(rad(0)) + sin(rad(90)) + deg(0))")
    assertEquals(2.0, com.example.faces.wff.WffExpression.evaluate(trigExpr, ctx).asDouble(), 0.001)

    // String, conditional & color functions
    val strExpr = com.example.faces.wff.WffExpression.compile("ifelse([BATTERY_PERCENT] > 50, concat(toUpperCase(trim(' ok ')), ':', subText('abcdef', 1, 4)), 'low')")
    assertEquals("OK:bcd", com.example.faces.wff.WffExpression.evaluate(strExpr, ctx).asString())
    val argbExpr = com.example.faces.wff.WffExpression.compile("rgb(255, 128, 0)")
    assertEquals(0xFFFF8000.toInt(), com.example.faces.wff.WffExpression.evaluate(argbExpr, ctx).asColorInt(0))

    // LRU compile cache returns identical instance
    val cachedAgain = com.example.faces.wff.WffExpression.compile("round(fract(3.75) * 10) + clamp(25, 0, 10) + abs(-4) + sqrt(16)")
    assertTrue(mathExpr === cachedAgain)

    // Input-dependency EvalCacheSlot skips re-evaluating when unrelated fields change
    val batExpr = com.example.faces.wff.WffExpression.compile("[BATTERY_PERCENT] * 2")
    val slot = com.example.faces.wff.EvalCacheSlot()
    assertEquals(164.0, com.example.faces.wff.WffExpression.evaluateCached(slot, batExpr, ctx).asDouble(), 0.001)
    assertTrue(slot.validFor(batExpr, ctx))
    ctx.secondVer++ // Unrelated time change leaves battery slot valid!
    assertTrue(slot.validFor(batExpr, ctx))
    ctx.batteryVer++ // Battery change invalidates slot cleanly!
    assertFalse(slot.validFor(batExpr, ctx))

    // Unknown source/function safely returns 0.0 and records diagnostics
    val badExpr = com.example.faces.wff.WffExpression.compile("[UNKNOWN_SENSOR_XYZ] + mysteryFn(1)")
    assertEquals(0.0, com.example.faces.wff.WffExpression.evaluate(badExpr, ctx).asDouble(), 0.001)
    assertTrue(badExpr.unknownSources.contains("UNKNOWN_SENSOR_XYZ"))
    assertTrue(badExpr.unknownFunctions.contains("mysteryFn"))
  }

  @Test
  fun `wff animation controller interpolates pauses cleanly when offscreen and respects cadence`() {
    val ctrl = com.example.faces.wff.WffAnimationController()
    val tr = com.example.faces.wff.WffTransform(
      key = "ring_sweep",
      target = "sweepAngle",
      valueExpr = com.example.faces.wff.WffExpression.compile("0"),
      animation = com.example.faces.wff.WffAnimationSpec(
        durationSec = 1.0f,
        interpolation = com.example.faces.wff.WffInterpolation.LINEAR,
        repeat = 0,
        fps = 20,
      ),
    )

    // Initial baseline finishes immediately at start value
    assertEquals(0f, ctrl.sample(tr, 0f, nowMs = 1000L, isAmbient = false), 0.01f)
    assertFalse(ctrl.hasActiveAnimation())

    // Data changes -> 500ms progress = 0.5 -> 50 deg
    ctrl.sample(tr, 100f, nowMs = 1000L, isAmbient = false)
    assertEquals(50f, ctrl.sample(tr, 100f, nowMs = 1500L, isAmbient = false), 0.5f)
    assertTrue(ctrl.hasActiveAnimation())

    // Screen turns off at 1500ms, resumes 5000ms later at 6500ms -> elapsed stays frozen at 500ms!
    ctrl.pause(1500L)
    ctrl.resume(6500L)
    assertEquals(50f, ctrl.sample(tr, 100f, nowMs = 6500L, isAmbient = false), 0.5f)
    // Finishes 500ms later at 7000ms
    assertEquals(100f, ctrl.sample(tr, 100f, nowMs = 7000L, isAmbient = false), 0.01f)
    assertFalse(ctrl.hasActiveAnimation())
  }

  @Test
  fun `wff advanced xml parses user configs conditions animations bitmap fonts effects blend modes and cleans prefs`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    val xmlText = javaClass.classLoader!!.getResourceAsStream("wff_sample_advanced.xml")!!.bufferedReader().use { it.readText() }
    val parsed = com.example.faces.wff.WffXmlParser.parse(xmlText, "wff_adv", "Advanced")
    assertTrue(parsed is com.example.faces.wff.WffParseResult.Success)
    val face = (parsed as com.example.faces.wff.WffParseResult.Success).face

    // UserConfigurations parsed
    assertEquals(3, face.userConfigs.size)
    assertTrue(face.userConfigs[0] is com.example.faces.wff.WffUserConfig.ColorConfig)
    assertTrue(face.userConfigs[1] is com.example.faces.wff.WffUserConfig.ListConfig)
    assertTrue(face.userConfigs[2] is com.example.faces.wff.WffUserConfig.BooleanConfig)

    // BitmapFonts & custom font ref
    assertTrue(face.bitmapFonts.containsKey("lcd_digits"))
    assertEquals(3, face.bitmapFonts["lcd_digits"]!!.glyphs.size)
    assertTrue(face.referencedFonts.contains("custom_sans"))

    // Condition branch + Shadow/Outline + BlendMode
    val condNode = face.nodes.filterIsInstance<com.example.faces.wff.WffSceneNode.Condition>().first()
    assertEquals(1, condNode.branches.size)
    assertEquals(1, condNode.defaultChildren.size)
    val fallbackText = condNode.defaultChildren.first() as com.example.faces.wff.WffSceneNode.PartText
    assertNotNull(fallbackText.shadow)
    assertNotNull(fallbackText.outline)
    val imgNode = face.nodes.filterIsInstance<com.example.faces.wff.WffSceneNode.PartImage>().first()
    assertEquals(com.example.faces.wff.WffBlendMode.MULTIPLY, imgNode.blendMode)

    // Config runtime resolution & SharedPreferences persistence
    prefs.setWffConfigValue("wff_adv", "theme_accent", "cyan")
    prefs.setWffConfigValue("wff_adv", "show_seconds_ring", "FALSE")
    val reloadedPrefs = AuraPrefs(context)
    val saved = reloadedPrefs.getWffFaceConfig("wff_adv")
    assertEquals("cyan", saved["theme_accent"])
    assertEquals("FALSE", saved["show_seconds_ring"])

    val runtime = com.example.faces.wff.WffConfigRuntime(face.userConfigs, saved)
    val evalCtx = com.example.faces.wff.WffEvalContext(FaceData(), runtime)
    val accentColor = com.example.faces.wff.WffExpression.evaluate(
      com.example.faces.wff.WffExpression.compile("[CONFIGURATION.theme_accent.0]"),
      evalCtx,
    ).asColorInt(0)
    assertEquals(0xFF00E5FF.toInt(), accentColor)
    assertFalse(com.example.faces.wff.WffExpression.evaluateBool(condNode.branches.first().expression, evalCtx))

    // Compatibility score (100% when all tags supported)
    assertEquals(100, face.report.scorePercent)

    // Hands + minute-only text face stays at EVERY_MINUTE in both modes
    val handsOnlyXml = """
      <WatchFace width="450" height="450">
        <Scene>
          <AnalogClock x="0" y="0" width="450" height="450">
            <HourHand resource="h" x="0" y="0" width="20" height="100" />
            <MinuteHand resource="m" x="0" y="0" width="20" height="150" />
          </AnalogClock>
          <DigitalClock x="0" y="300" width="450" height="50">
            <TimeText format="HH:mm" />
          </DigitalClock>
        </Scene>
      </WatchFace>
    """.trimIndent()
    val handsFace = (com.example.faces.wff.WffXmlParser.parse(handsOnlyXml, "h", "H") as com.example.faces.wff.WffParseResult.Success).face
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, com.example.faces.wff.WffExpression.updatePolicy(handsFace, FaceRenderMode.NORMAL))
    assertEquals(FaceUpdatePolicy.EVERY_MINUTE, com.example.faces.wff.WffExpression.updatePolicy(handsFace, FaceRenderMode.AMBIENT))

    prefs.deleteWffFaceConfigs("wff_adv")
    assertTrue(prefs.getWffFaceConfig("wff_adv").isEmpty())
  }

  @Test
  fun `wff string resources resolve from strings xml and arsc with locale fallback and no localization penalty`() {
    val arscStringsBytes = buildMinimalTestArsc(
      globalStrings = listOf("Tap Here", "Morning Class"),
      typeName = "string",
      entries = listOf("arsc_label" to 0, "greeting" to 1),
    )
    assertEquals("Tap Here", com.example.faces.wff.ArscResourceTableReader.readStrings(arscStringsBytes)["arsc_label"])

    val zipOut = java.io.ByteArrayOutputStream()
    val wfXml = """
      <WatchFace width="450" height="450" clipShape="CIRCLE">
        <Metadata key="title" value="Localized Face" />
        <UserConfigurations>
          <BooleanConfiguration id="flag" displayName="toggle_label" defaultValue="TRUE" />
        </UserConfigurations>
        <Scene backgroundColor="#FF000000">
          <Localization calendar="GREGORIAN" locales="en_US es_ES" />
          <PartText x="0" y="100" width="450" height="60">
            <Text align="CENTER">
              <Font family="SYNC_TO_DEVICE" size="24" color="#FFFFFFFF">
                <Template>%s</Template>
                <Parameter expression="greeting" />
              </Font>
            </Text>
          </PartText>
        </Scene>
      </WatchFace>
    """.trimIndent()
    java.util.zip.ZipOutputStream(zipOut).use { zos ->
      zos.putNextEntry(java.util.zip.ZipEntry("res/raw/watchface.xml"))
      zos.write(wfXml.toByteArray(Charsets.UTF_8))
      zos.closeEntry()
      zos.putNextEntry(java.util.zip.ZipEntry("res/values/strings.xml"))
      zos.write("""<resources><string name="greeting">Hello Teacher</string><string name="toggle_label">Show Ring</string></resources>""".toByteArray(Charsets.UTF_8))
      zos.closeEntry()
      zos.putNextEntry(java.util.zip.ZipEntry("res/values-es/strings.xml"))
      zos.write("""<resources><string name="greeting">Hola Clase</string></resources>""".toByteArray(Charsets.UTF_8))
      zos.closeEntry()
      zos.putNextEntry(java.util.zip.ZipEntry("resources.arsc"))
      zos.write(arscStringsBytes)
      zos.closeEntry()
    }

    val tmpZip = java.io.File.createTempFile("loc_test", ".zip")
    try {
      tmpZip.writeBytes(zipOut.toByteArray())
      java.util.zip.ZipFile(tmpZip).use { zf ->
        val entries = zf.entries().asSequence().toList()
        val esStrings = com.example.faces.wff.WffStringResources.loadFromArchive(zf, entries, arscStringsBytes, java.util.Locale("es", "ES"))
        assertEquals("Hola Clase", esStrings["greeting"])
        assertEquals("Show Ring", esStrings["toggle_label"])
        assertEquals("Tap Here", esStrings["arsc_label"])

        val frStrings = com.example.faces.wff.WffStringResources.loadFromArchive(zf, entries, arscStringsBytes, java.util.Locale.FRENCH)
        assertEquals("Hello Teacher", frStrings["greeting"])
      }
    } finally {
      tmpZip.delete()
    }

    val parsed = com.example.faces.wff.WffXmlParser.parse(
      wfXml,
      "loc_id",
      "Loc",
      mapOf("greeting" to "Hello Teacher", "toggle_label" to "Show Ring"),
    ) as com.example.faces.wff.WffParseResult.Success
    val face = parsed.face
    assertEquals("Show Ring", face.userConfigs.first().displayName)
    assertEquals(100, face.report.scorePercent)
    assertEquals("GREGORIAN", face.localizationCalendar)
    val expr = com.example.faces.wff.WffExpression.compile("greeting")
    val evalCtx = com.example.faces.wff.WffEvalContext(data = FaceData(), strings = face.stringResources)
    assertEquals("Hello Teacher", com.example.faces.wff.WffExpression.evaluate(expr, evalCtx).asString())
  }

  @Test
  fun `duplicate wff import prompts replace or cancel safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val xml = """<WatchFace width="450" height="450"><Metadata key="title" value="Dup Test Face" /><Scene><DigitalClock x="0" y="0" width="450" height="100"><TimeText format="HH:mm" /></DigitalClock></Scene></WatchFace>"""

    val first = com.example.faces.wff.WffStorageManager.importFromStream(
      context, "dup_face.xml", java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
    )
    assertTrue(first is com.example.faces.wff.WffImportResult.Success)
    val firstEntry = (first as com.example.faces.wff.WffImportResult.Success).entry

    val second = com.example.faces.wff.WffStorageManager.importFromStream(
      context, "dup_face.xml", java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
    )
    assertTrue(second is com.example.faces.wff.WffImportResult.DuplicateFound)
    val dup = second as com.example.faces.wff.WffImportResult.DuplicateFound
    assertEquals(firstEntry.id, dup.existingFace.id)
    assertTrue(dup.stagedFile.exists())

    val replaced = com.example.faces.wff.WffStorageManager.replaceImportedFace(
      context, dup.stagedFile, dup.sourceFileName, dup.existingFace.id,
    )
    assertTrue(replaced is com.example.faces.wff.WffImportResult.Success)
    assertEquals(firstEntry.id, (replaced as com.example.faces.wff.WffImportResult.Success).entry.id)
    assertFalse(dup.stagedFile.exists())

    // Clean up
    assertTrue(com.example.faces.wff.WffStorageManager.deleteFace(context, firstEntry.id))
  }

  @Test
  fun `complication resolver maps offline auraclock data and tap targets`() {
    val slot = com.example.faces.wff.WffSceneNode.ComplicationSlot(
      slotId = "1",
      displayName = "Left",
      x = 0f, y = 0f, width = 150f, height = 150f,
      supportedTypes = listOf("RANGED_VALUE", "SHORT_TEXT", "LONG_TEXT"),
      boundingShape = com.example.faces.wff.WffBoundingShape.Oval(0f, 0f, 150f, 150f),
      defaultProviderPolicy = com.example.faces.wff.WffDefaultProviderPolicy("STEP_COUNT", "RANGED_VALUE"),
    )
    assertEquals(
      com.example.faces.wff.AuraComplicationProvider.STEPS,
      com.example.faces.wff.AuraComplicationProvider.fromPolicy(slot.defaultProviderPolicy, slot.supportedTypes),
    )

    val data = FaceData(
      time = FaceTimeData(dayOfMonth = 18, dayNameShort = "WED", monthNameShort = "NOV"),
      battery = FaceBatteryData(levelPercent = 74, isCharging = true),
      nextEvent = NextCalendarEvent("Science Fair", 0L, 0L, isAllDay = true),
      timer = FaceTimerData(phase = TimerPhase.RUNNING, remainingMs = 90_000L, totalDurationMs = 180_000L, roundNumber = 2),
      sensors = com.example.core.FaceSensorData(stepCount = 4321, stepGoal = 10_000, hasStepPermission = true, stepHardwarePresent = true),
    )

    val stepsRes = com.example.faces.wff.WffComplicationResolver.resolve(slot, com.example.faces.wff.AuraComplicationProvider.STEPS, data)
    assertEquals("4,321", stepsRes.text)
    assertEquals(4321f, stepsRes.rangedValue, 0.1f)
    assertEquals(com.example.core.AppTabTarget.DIAGNOSTICS, stepsRes.provider.tapTarget)

    val timerRes = com.example.faces.wff.WffComplicationResolver.resolve(slot, com.example.faces.wff.AuraComplicationProvider.TIMER, data)
    assertEquals("01:30", timerRes.text)
    assertEquals("R2", timerRes.title)
    assertEquals(com.example.core.AppTabTarget.TIMER, timerRes.provider.tapTarget)

    val calRes = com.example.faces.wff.WffComplicationResolver.resolve(slot, com.example.faces.wff.AuraComplicationProvider.NEXT_EVENT, data, showEventTitles = true)
    assertTrue(calRes.title.contains("Science Fair"))
    assertEquals(com.example.core.AppTabTarget.SETTINGS_CALENDAR, calRes.provider.tapTarget)
  }

  @Test
  fun `step counter rollover reboot math and on demand sensor listener teardown`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    val sensorSource = com.example.core.CoreFaceSensorSource(context, prefs)

    // Same day incremental steps
    val (d1, b1) = com.example.core.CoreFaceSensorSource.computeDailySteps(5000f, todayKey = 2026320, savedDayKey = 2026320, savedBaseline = 4200f)
    assertEquals(800, d1)
    assertEquals(4200f, b1, 0.01f)

    // Day rollover resets baseline to current cumulative
    val (dRoll, bRoll) = com.example.core.CoreFaceSensorSource.computeDailySteps(5500f, todayKey = 2026321, savedDayKey = 2026320, savedBaseline = 4200f)
    assertEquals(0, dRoll)
    assertEquals(5500f, bRoll, 0.01f)

    // Phone reboot makes cumulative < savedBaseline -> resets baseline cleanly
    val (dBoot, bBoot) = com.example.core.CoreFaceSensorSource.computeDailySteps(120f, todayKey = 2026321, savedDayKey = 2026321, savedBaseline = 5500f)
    assertEquals(0, dBoot)
    assertEquals(120f, bBoot, 0.01f)

    // Observing then cancelling ensures 0 active listeners remain
    val job = launch {
      sensorSource.observeSensors(setOf(com.example.core.FaceSensorType.ACCELEROMETER, com.example.core.FaceSensorType.LIGHT)).collect { }
    }
    kotlinx.coroutines.delay(50)
    job.cancel()
    job.join()
    assertEquals(0, sensorSource.diagnostics.value.activeListenersCount)
  }

  @Test
  fun `all seven official google wff sample templates import parse score and provenance cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sampleFiles = listOf(
      "wff_official_simple_digital.xml",
      "wff_official_simple_analog.xml",
      "wff_official_complications.xml",
      "wff_official_flavors.xml",
      "wff_official_weather.xml",
      "wff_official_photos_mask.xml",
      "wff_official_photos_multi.xml",
    )

    val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    val pngOut = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, pngOut)
    val pngBytes = pngOut.toByteArray()
    bmp.recycle()

    val importedIds = mutableListOf<String>()
    try {
      for (fileName in sampleFiles) {
        val xmlBytes = javaClass.classLoader!!.getResourceAsStream(fileName)!!.readBytes()
        val zipOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipOut).use { zos ->
          zos.putNextEntry(java.util.zip.ZipEntry("res/raw/watchface.xml"))
          zos.write(xmlBytes)
          zos.closeEntry()
          for (res in listOf(
            "res/drawable/analog_hour_hand.png",
            "res/drawable/analog_minute_hand.png",
            "res/drawable/analog_second_hand.png",
            "res/drawable/photo_default_bg.png",
            "res/drawable/photo_circle_mask.png",
            "res/drawable/photo_default_album.png",
          )) {
            zos.putNextEntry(java.util.zip.ZipEntry(res))
            zos.write(pngBytes)
            zos.closeEntry()
          }
        }
        val res = com.example.faces.wff.WffStorageManager.importFromStream(
          context, fileName.replace(".xml", ".apk"), java.io.ByteArrayInputStream(zipOut.toByteArray()),
        )
        assertTrue("Expected success importing $fileName, got $res", res is com.example.faces.wff.WffImportResult.Success)
        val entry = (res as com.example.faces.wff.WffImportResult.Success).entry
        importedIds += entry.id
        assertEquals("Expected 0 skipped items for $fileName, got ${entry.report.skippedItems}", 0, entry.report.skippedCount)
        assertTrue("Score too low for $fileName: ${entry.report.scorePercent}", entry.report.scorePercent >= 90)
      }

      // Check Weather provenance flags NO_DATA and evaluates safely offline
      val weatherFace = com.example.faces.wff.WffStorageManager.loadAll(context)
        .first { it.face.name == "Weather Official" }.face
      val weatherProv = com.example.faces.wff.WffExpressionEvaluator.buildDataSourceProvenance(
        weatherFace, com.example.core.CoreSingletons.sensorSource(context).diagnostics.value, emptyMap(),
      )
      assertTrue(weatherProv.any { it.source == "Weather" && it.origin == com.example.core.WffDataSourceOrigin.NO_DATA })
      val weatherEvalCtx = com.example.faces.wff.WffEvalContext(FaceData())
      assertFalse(com.example.faces.wff.WffExpression.evaluate(com.example.faces.wff.WffExpression.compile("[WEATHER.IS_AVAILABLE]"), weatherEvalCtx).asBoolean())
      assertEquals("--", com.example.faces.wff.WffExpression.evaluate(com.example.faces.wff.WffExpression.compile("[WEATHER.CONDITION_NAME]"), weatherEvalCtx).asString())
    } finally {
      importedIds.forEach { com.example.faces.wff.WffStorageManager.deleteFace(context, it) }
    }
  }

  @Test
  fun `photos apply exif rotation upright on import and decode at slot pixel size up to 1080px`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val faceId = "test_photo_exif_face"
    val configId = "bg_photo"
    val srcFile = java.io.File.createTempFile("rotated_sample", ".jpg", context.cacheDir)
    try {
      // Save a wide 1600x800 landscape JPEG with EXIF ORIENTATION_ROTATE_90 -> upright should be portrait 540x1080
      val rawBmp = android.graphics.Bitmap.createBitmap(1600, 800, android.graphics.Bitmap.Config.ARGB_8888)
      val canvas = android.graphics.Canvas(rawBmp)
      canvas.drawColor(android.graphics.Color.BLUE)
      val topHalfPaint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
      canvas.drawRect(0f, 0f, 800f, 800f, topHalfPaint)
      java.io.FileOutputStream(srcFile).use { fos ->
        rawBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
      }
      rawBmp.recycle()

      val exif = android.media.ExifInterface(srcFile.absolutePath)
      exif.setAttribute(
        android.media.ExifInterface.TAG_ORIENTATION,
        android.media.ExifInterface.ORIENTATION_ROTATE_90.toString(),
      )
      exif.saveAttributes()

      val importedNames = com.example.faces.wff.WffPhotoManager.importPhotos(
        context = context,
        faceId = faceId,
        configId = configId,
        uris = listOf(android.net.Uri.fromFile(srcFile)),
      )
      assertEquals(1, importedNames.size)

      val storedFile = java.io.File(
        com.example.faces.wff.WffPhotoManager.photosDir(context, faceId, configId),
        importedNames.first(),
      )
      val storedBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      android.graphics.BitmapFactory.decodeFile(storedFile.absolutePath, storedBounds)
      assertTrue("Expected rotated portrait height > width, got ${storedBounds.outWidth}x${storedBounds.outHeight}", storedBounds.outHeight > storedBounds.outWidth)
      assertEquals(1080, maxOf(storedBounds.outWidth, storedBounds.outHeight))

      // Slot smaller than 1080 decodes at slot size; slot larger than 1080 caps longest side at 1080
      val smallDecoded = com.example.faces.wff.WffPhotoManager.decodeFacePhotos(
        context = context,
        faceId = faceId,
        savedPhotosByConfig = mapOf(configId to importedNames),
        slotSizesByConfigId = mapOf(configId to (240 to 240)),
      )[configId]!!.first()
      assertTrue("Expected <= 240 longest side for small slot, got ${smallDecoded.width}x${smallDecoded.height}", maxOf(smallDecoded.width, smallDecoded.height) <= 240)
      smallDecoded.recycle()

      val hugeFace = com.example.faces.wff.WffWatchFace(
        id = faceId,
        name = "Photo Test",
        width = 450f,
        height = 450f,
        clipShape = com.example.faces.wff.WffClipShape.NONE,
        cornerRadiusX = 0f,
        cornerRadiusY = 0f,
        backgroundColor = android.graphics.Color.BLACK,
        backgroundColorExpr = null,
        sceneTransforms = emptyList(),
        sceneVariants = emptyList(),
        nodes = listOf(
          com.example.faces.wff.WffSceneNode.PartImage(
            x = 0f,
            y = 0f,
            width = 450f,
            height = 450f,
            pivotX = 0.5f,
            pivotY = 0.5f,
            angle = 0f,
            scaleX = 1f,
            scaleY = 1f,
            alpha = 255,
            visible = true,
            resources = emptyList(),
            photos = com.example.faces.wff.WffPhotosSpec(
              sourceExpr = null,
              configId = configId,
              defaultResource = null,
              changeOnTap = true,
              changeOnVisible = false,
              changeAfterEvery = 1,
            ),
            tintColor = null,
            tintColorExpr = null,
            blendMode = com.example.faces.wff.WffBlendMode.SRC_OVER,
            transforms = emptyList(),
            variants = emptyList(),
          ),
        ),
        userConfigs = emptyList(),
        flavors = null,
        bitmapFonts = emptyMap(),
        stringResources = emptyMap(),
        localizationCalendar = null,
        localizationLocales = null,
        metadata = emptyMap(),
        usedSources = emptySet(),
        report = com.example.faces.wff.WffCompatibilityReport(emptyList()),
      )
      val hugeSlotMap = com.example.faces.wff.WffDrawHelpers.computePhotoSlotTargetSizes(hugeFace, stageScale = 4.0f)
      assertEquals(1080, hugeSlotMap[configId]!!.first)
      assertEquals(1080, hugeSlotMap[configId]!!.second)
    } finally {
      srcFile.delete()
      com.example.faces.wff.WffPhotoManager.clearConfigPhotos(context, faceId, configId)
    }
  }

  @Test
  fun `heart rate requires both hardware and permission before reporting available or registering`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app = context.applicationContext as android.app.Application
    val prefs = AuraPrefs(context)
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    val sensorSource = com.example.core.CoreFaceSensorSource(context, prefs)
    val hrPerm = sensorSource.heartRatePermission()

    // Case 1: No hardware -> NO_HARDWARE
    sensorSource.refreshDiagnostics()
    assertEquals(com.example.core.SensorAvailability.NO_HARDWARE, sensorSource.diagnostics.value.heartRate)

    // Case 2: Hardware present, permission denied -> PERMISSION_NEEDED and listener NOT registered
    val mockHrSensor = org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_HEART_RATE)
    org.robolectric.Shadows.shadowOf(sensorManager).addSensor(mockHrSensor)
    org.robolectric.Shadows.shadowOf(app).denyPermissions(hrPerm)
    sensorSource.refreshDiagnostics()
    assertEquals(com.example.core.SensorAvailability.PERMISSION_NEEDED, sensorSource.diagnostics.value.heartRate)
    assertFalse(sensorSource.hasHeartRatePermission())

    val deniedJob = launch {
      sensorSource.observeSensors(setOf(com.example.core.FaceSensorType.HEART_RATE)).collect { }
    }
    kotlinx.coroutines.delay(40)
    assertEquals(0, sensorSource.diagnostics.value.activeListenersCount)
    deniedJob.cancel()
    deniedJob.join()

    // Case 3: Hardware present AND permission granted -> AVAILABLE and registers while visible only
    org.robolectric.Shadows.shadowOf(app).grantPermissions(hrPerm)
    sensorSource.refreshDiagnostics()
    assertEquals(com.example.core.SensorAvailability.AVAILABLE, sensorSource.diagnostics.value.heartRate)
    assertTrue(sensorSource.hasHeartRatePermission())

    val hrXml = """
      <WatchFace width="450" height="450"><Scene>
        <PartText x="0" y="0" width="200" height="40"><Text><Font family="SYNC_TO_DEVICE" size="24" color="#FFFFFFFF">
          <Template>%s</Template><Parameter expression="[HEART_RATE]" />
        </Font></Text></PartText>
      </Scene></WatchFace>
    """.trimIndent()
    val hrFace = (com.example.faces.wff.WffXmlParser.parse(hrXml, "hr_test", "HR", emptyMap()) as com.example.faces.wff.WffParseResult.Success).face
    val provOk = com.example.faces.wff.WffExpressionEvaluator.buildDataSourceProvenance(hrFace, sensorSource.diagnostics.value, emptyMap())
    assertTrue(provOk.any { it.source == "Heart Rate" && it.origin == com.example.core.WffDataSourceOrigin.REAL_SENSOR && it.detail == "Available" })

    org.robolectric.Shadows.shadowOf(app).denyPermissions(hrPerm)
    sensorSource.refreshDiagnostics()
    val provDenied = com.example.faces.wff.WffExpressionEvaluator.buildDataSourceProvenance(hrFace, sensorSource.diagnostics.value, emptyMap())
    assertTrue(provDenied.any { it.source == "Heart Rate" && it.origin == com.example.core.WffDataSourceOrigin.NO_DATA && it.detail == "Permission needed" })
  }

  @Test
  fun `playback reprime logic enforces 400ms debounce 1s self audio ignore window 3s rate limit and setting`() {
    // Normal external playback -> debounces 400ms
    val initial = com.example.earbud.PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 10_000L,
      hasExternalAudio = true,
      takeOverEnabled = true,
      lastSelfAudioMs = 2_000L,
      lastReprimeMs = -1L,
    )
    assertEquals(com.example.earbud.ReprimePlanAction.SCHEDULE, initial.action)
    assertEquals(400L, initial.delayMs)
    assertTrue(initial.isExternalPlaying)

    // Own prime or TTS speech within 1s ignore window -> ignored completely
    val selfPrimeIgnored = com.example.earbud.PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 10_900L,
      hasExternalAudio = true,
      takeOverEnabled = true,
      lastSelfAudioMs = 10_400L,
      lastReprimeMs = 10_400L,
    )
    assertEquals(com.example.earbud.ReprimePlanAction.IGNORE, selfPrimeIgnored.action)
    assertTrue(selfPrimeIgnored.ignoredSelfAudio)

    val speakingIgnored = com.example.earbud.PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 15_000L,
      hasExternalAudio = true,
      takeOverEnabled = true,
      lastSelfAudioMs = 12_000L,
      lastReprimeMs = 10_400L,
      isCurrentlySpeaking = true,
    )
    assertEquals(com.example.earbud.ReprimePlanAction.IGNORE, speakingIgnored.action)
    assertTrue(speakingIgnored.ignoredSelfAudio)

    // Rate limit: re-primed at 10_400L, new external callback at 11_500L -> delayed until 13_400L (1900ms wait)
    val rateLimited = com.example.earbud.PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 11_500L,
      hasExternalAudio = true,
      takeOverEnabled = true,
      lastSelfAudioMs = 10_400L,
      lastReprimeMs = 10_400L,
    )
    assertEquals(com.example.earbud.ReprimePlanAction.SCHEDULE, rateLimited.action)
    assertEquals(1_900L, rateLimited.delayMs)
    assertFalse(
      com.example.earbud.PlaybackReprimeLogic.canExecuteReprimeNow(
        nowMs = 12_900L,
        takeOverEnabled = true,
        lastSelfAudioMs = 10_400L,
        lastReprimeMs = 10_400L,
      ),
    )
    assertTrue(
      com.example.earbud.PlaybackReprimeLogic.canExecuteReprimeNow(
        nowMs = 13_400L,
        takeOverEnabled = true,
        lastSelfAudioMs = 10_400L,
        lastReprimeMs = 10_400L,
      ),
    )

    // Setting "Let the music app keep them" (takeOverEnabled = false) skips re-prime while tracking playback
    val letMusicKeep = com.example.earbud.PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 20_000L,
      hasExternalAudio = true,
      takeOverEnabled = false,
      lastSelfAudioMs = 10_400L,
      lastReprimeMs = 10_400L,
    )
    assertEquals(com.example.earbud.ReprimePlanAction.IGNORE, letMusicKeep.action)
    assertFalse(letMusicKeep.ignoredSelfAudio)
    assertTrue(letMusicKeep.isExternalPlaying)

    // External playback stopped -> cancels pending debounce
    val stopped = com.example.earbud.PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 21_000L,
      hasExternalAudio = false,
      takeOverEnabled = true,
      lastSelfAudioMs = 10_400L,
      lastReprimeMs = 10_400L,
    )
    assertEquals(com.example.earbud.ReprimePlanAction.CANCEL_PENDING, stopped.action)
    assertFalse(stopped.isExternalPlaying)

    // Prefs persistence for "Earbud buttons while other audio is playing"
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    assertTrue(prefs.earbudTakeOverDuringAudio.value)
    prefs.setEarbudTakeOverDuringAudio(false)
    assertFalse(AuraPrefs(context).earbudTakeOverDuringAudio.value)

    // Verify all media keys (play/pause/hook single, double, next/previous) reach MediaKeyRouter
    prefs.setPlayPauseActionId(AuraPrefs.ACTION_SPEAK_TIME)
    prefs.setPlayPauseDoubleActionId(AuraPrefs.ACTION_TIMER_TOGGLE)
    prefs.setNextActionId(AuraPrefs.ACTION_TIMER_TOGGLE)
    prefs.setPreviousActionId(AuraPrefs.ACTION_NEXT_FACE)
    val seenKeys = mutableListOf<String>()
    val router = MediaKeyRouter(context, prefs) { keyLabel, _, _ -> seenKeys += keyLabel }
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)))
    assertTrue(seenKeys.any { it.contains("KEYCODE_HEADSETHOOK") })
    assertTrue(seenKeys.any { it.contains("KEYCODE_MEDIA_NEXT") })
    assertTrue(seenKeys.any { it.contains("KEYCODE_MEDIA_PREVIOUS") })
  }

  @Test
  fun `standby preview and dream service use identical dim value function and update live`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    val coordinator = StandByCalendarCoordinator(context, prefs)

    for (level in listOf(0.15f, 0.35f, 0.65f, 1.0f)) {
      val previewDim = com.example.standby.StandByChiplet.computePreviewDimValues(level)
      val dreamDim = com.example.standby.StandByDreamService.computeDimValues(level)
      assertEquals(previewDim, dreamDim)
    }

    // At 100%, nothing is dimmed
    val fullDim = com.example.standby.StandByDreamService.computeDimValues(1.0f)
    assertEquals(0f, fullDim.softwareDimAlpha, 0.001f)
    assertEquals(1.0f, fullDim.windowBrightness, 0.001f)

    // At 15%, software dim is 0.85 (1 - 0.15) and window brightness is balanced (not double-crushed to 0.15)
    val nightDim = com.example.standby.StandByDreamService.computeDimValues(0.15f)
    assertEquals(0.85f, nightDim.softwareDimAlpha, 0.001f)
    assertEquals(0.405f, nightDim.windowBrightness, 0.001f)

    // Changing slider updates coordinator diagnostics live
    prefs.setStandByDimLevel(0.15f)
    kotlinx.coroutines.delay(50)
    assertEquals(0.85f, coordinator.diagnostics.value.softwareDimLevel, 0.001f)
    assertEquals(0.405f, coordinator.diagnostics.value.requestedWindowBrightness, 0.001f)

    prefs.setStandByDimLevel(1.0f)
    kotlinx.coroutines.delay(50)
    assertEquals(0.0f, coordinator.diagnostics.value.softwareDimLevel, 0.001f)
    assertEquals(1.0f, coordinator.diagnostics.value.requestedWindowBrightness, 0.001f)
  }

  private fun buildMinimalTestArsc(
    globalStrings: List<String>,
    typeName: String,
    entries: List<Pair<String, Int>>,
  ): ByteArray {
    fun encodeUtf8Pool(strings: List<String>): ByteArray {
      val stringsData = java.io.ByteArrayOutputStream()
      val offsets = IntArray(strings.size)
      strings.forEachIndexed { idx, s ->
        offsets[idx] = stringsData.size()
        val utf8 = s.toByteArray(Charsets.UTF_8)
        stringsData.write(s.length and 0x7F)
        stringsData.write(utf8.size and 0x7F)
        stringsData.write(utf8)
        stringsData.write(0)
      }
      while ((stringsData.size() % 4) != 0) stringsData.write(0)
      val headerSize = 28
      val stringsStart = headerSize + strings.size * 4
      val chunkSize = stringsStart + stringsData.size()
      val buf = java.nio.ByteBuffer.allocate(chunkSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      buf.putShort(0x0001) // RES_STRING_POOL_TYPE
      buf.putShort(headerSize.toShort())
      buf.putInt(chunkSize)
      buf.putInt(strings.size)
      buf.putInt(0) // styleCount
      buf.putInt(1 shl 8) // UTF-8
      buf.putInt(stringsStart)
      buf.putInt(0)
      offsets.forEach { buf.putInt(it) }
      buf.put(stringsData.toByteArray())
      return buf.array()
    }

    val globalPool = encodeUtf8Pool(globalStrings)
    val typePool = encodeUtf8Pool(listOf(typeName))
    val keyPool = encodeUtf8Pool(entries.map { it.first })

    val typeHeaderSize = 24
    val offsetsSize = entries.size * 4
    val entriesBytes = java.io.ByteArrayOutputStream()
    val entryBuf = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val entryOffsets = IntArray(entries.size)
    entries.forEachIndexed { idx, (_, globalIdx) ->
      entryOffsets[idx] = entriesBytes.size()
      entryBuf.clear()
      entryBuf.putShort(8) // entry size
      entryBuf.putShort(0) // flags
      entryBuf.putInt(idx) // key index
      entryBuf.putShort(8) // Res_value size
      entryBuf.put(0)
      entryBuf.put(0x03) // TYPE_STRING
      entryBuf.putInt(globalIdx)
      entriesBytes.write(entryBuf.array())
    }
    val typeChunkSize = typeHeaderSize + offsetsSize + entriesBytes.size()
    val typeBuf = java.nio.ByteBuffer.allocate(typeChunkSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    typeBuf.putShort(0x0201) // RES_TABLE_TYPE_TYPE
    typeBuf.putShort(typeHeaderSize.toShort())
    typeBuf.putInt(typeChunkSize)
    typeBuf.put(1) // type id = 1
    typeBuf.put(0) // flags
    typeBuf.putShort(0)
    typeBuf.putInt(entries.size)
    typeBuf.putInt(typeHeaderSize + offsetsSize) // entriesStart
    typeBuf.putInt(4) // ResTable_config size
    entryOffsets.forEach { typeBuf.putInt(it) }
    typeBuf.put(entriesBytes.toByteArray())
    val typeChunk = typeBuf.array()

    val pkgHeaderSize = 288
    val typeStringsOffset = pkgHeaderSize
    val keyStringsOffset = pkgHeaderSize + typePool.size
    val pkgSize = pkgHeaderSize + typePool.size + keyPool.size + typeChunk.size
    val pkgBuf = java.nio.ByteBuffer.allocate(pkgSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    pkgBuf.putShort(0x0200) // RES_TABLE_PACKAGE_TYPE
    pkgBuf.putShort(pkgHeaderSize.toShort())
    pkgBuf.putInt(pkgSize)
    pkgBuf.putInt(0x7F)
    pkgBuf.position(pkgHeaderSize - 20)
    pkgBuf.putInt(typeStringsOffset)
    pkgBuf.putInt(0)
    pkgBuf.putInt(keyStringsOffset)
    pkgBuf.putInt(0)
    pkgBuf.putInt(0)
    pkgBuf.put(typePool)
    pkgBuf.put(keyPool)
    pkgBuf.put(typeChunk)
    val pkgChunk = pkgBuf.array()

    val rootSize = 12 + globalPool.size + pkgChunk.size
    val rootBuf = java.nio.ByteBuffer.allocate(rootSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    rootBuf.putShort(0x0002) // RES_TABLE_TYPE
    rootBuf.putShort(12)
    rootBuf.putInt(rootSize)
    rootBuf.putInt(1) // packageCount
    rootBuf.put(globalPool)
    rootBuf.put(pkgChunk)
    return rootBuf.array()
  }
}

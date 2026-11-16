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
    assertTrue(face.report.drawnElements.containsAll(listOf("WatchFace", "Scene", "AnalogClock", "HourHand", "SecondHand", "DigitalClock", "PartDraw", "PartText", "PartImage")))
    assertEquals(1, face.report.skippedItems.size)
    assertEquals("ComplicationSlot", face.report.skippedItems.first().element)

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
    val badExpr = com.example.faces.wff.WffExpression.compile("[HEART_RATE] + mysteryFn(1)")
    assertEquals(0.0, com.example.faces.wff.WffExpression.evaluate(badExpr, ctx).asDouble(), 0.001)
    assertTrue(badExpr.unknownSources.contains("HEART_RATE"))
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

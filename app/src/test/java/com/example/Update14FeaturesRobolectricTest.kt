package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.CoreSingletons
import com.example.core.CustomFontStorage
import com.example.core.FaceRenderMode
import com.example.core.FontImportResult
import com.example.earbud.EarbudChiplet
import com.example.earbud.KeyArrivalRoute
import com.example.earbud.MediaKeyRouter
import com.example.earbud.RawKeyLog
import com.example.faces.ClockTimeSnapshot
import com.example.faces.ImageDialFace
import com.example.faces.PhotoCenterCapStyle
import com.example.faces.PhotoDialBitmapProcessor
import com.example.faces.PhotoFaceConfig
import com.example.faces.PhotoFaceStorage
import com.example.faces.PhotoHandStyle
import com.example.faces.PhotoLoadResult
import com.example.faces.PhotoZipImportResult
import com.example.faces.WatchFaceRegistry
import com.example.faces.engine.FaceDefinitionParser
import com.example.ui.theme.AuraClockTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Update14FeaturesRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    CoreSingletons.clearPreferences(context)
    RawKeyLog.clear()
    CustomFontStorage.resetForTest()
    cleanupUserFiles()
  }

  @After
  fun tearDown() {
    RawKeyLog.clear()
    CustomFontStorage.resetForTest()
    cleanupUserFiles()
    CoreSingletons.clearPreferences(context)
  }

  private fun cleanupUserFiles() {
    File(context.filesDir, "fonts").deleteRecursively()
    File(context.filesDir, "photo_faces").deleteRecursively()
    WatchFaceRegistry.reloadWff(context)
  }

  // ---- PART 1: RAW KEY LOG ----

  @Test
  fun `raw key log keeps strictly last 30 events and formats copy text`() {
    for (i in 1..35) {
      RawKeyLog.record(
        keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        isDown = i % 2 == 1,
        route = KeyArrivalRoute.MEDIA_BUTTON_RECEIVER.label,
        outcome = "Event #$i",
        timestampLabel = "12:00:${"%02d".format(i)}.000",
      )
    }

    val entries = RawKeyLog.entries.value
    assertEquals(30, entries.size)
    assertEquals("Event #35", entries.first().outcome)
    assertEquals("Event #6", entries.last().outcome)

    val copied = RawKeyLog.formatAllForCopy()
    assertTrue(copied.contains("Event #35"))
    assertTrue(copied.contains("KEYCODE_MEDIA_PLAY_PAUSE"))
    assertFalse(copied.contains("Event #1\n"))

    RawKeyLog.clear()
    assertTrue(RawKeyLog.entries.value.isEmpty())
  }

  @Test
  fun `media key router records unsupported, forwarded, own-key, action, and learn mode events`() {
    val prefs = CoreSingletons.prefs(context)
    var nowMs = 1_000L
    val router = MediaKeyRouter(
      context = context,
      prefs = prefs,
      onKeyDiagnostics = { _, _, _ -> },
      forwardMediaKey = { true },
      clockMs = { nowMs },
    )

    // 1. Unsupported key (volume up / space) -> ignored as unsupported
    assertFalse(
      router.handleKeyEvent(
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0),
        route = KeyArrivalRoute.MEDIA_BUTTON_RECEIVER.label,
      ),
    )
    assertTrue(RawKeyLog.entries.value.first().outcome.contains("unsupported", ignoreCase = true))

    // 2. Next key runs default timer toggle -> recorded as action
    assertTrue(
      router.routeKeyCode(
        KeyEvent.KEYCODE_MEDIA_NEXT,
        allowDedup = false,
        route = KeyArrivalRoute.MEDIA_SESSION_NEXT.label,
      ),
    )
    assertTrue(RawKeyLog.entries.value.first().outcome.contains("Ran action", ignoreCase = true))

    // 3. Play/pause single default is forward_to_music (ensure double & triple NONE for immediate dispatch)
    prefs.setPlayPauseDoubleActionId(com.example.core.AuraPrefs.ACTION_NONE)
    prefs.setPlayPauseTripleActionId(com.example.core.AuraPrefs.ACTION_NONE)
    nowMs += 500L
    assertTrue(
      router.handleKeyEvent(
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0),
        route = KeyArrivalRoute.MEDIA_SESSION_EVENT.label,
      ),
    )
    assertTrue(RawKeyLog.entries.value.first().outcome.contains("Forwarded", ignoreCase = true))

    // 4. Bounced self-forwarded key within 1 second -> ignored as its own key
    nowMs += 50L
    assertFalse(
      router.handleKeyEvent(
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0),
        route = KeyArrivalRoute.MEDIA_BUTTON_RECEIVER.label,
      ),
    )
    assertTrue(RawKeyLog.entries.value.first().outcome.contains("own key", ignoreCase = true))

    // 5. Learn mode intercept logs route & outcome
    nowMs += 1_500L
    router.setLearnCaptureListener { _, _ -> }
    assertTrue(
      router.handleKeyEvent(
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0),
        route = KeyArrivalRoute.MEDIA_BUTTON_RECEIVER.label,
      ),
    )
    val learnEntry = RawKeyLog.entries.value.first()
    assertTrue(learnEntry.route.contains("Learn mode"))
    assertTrue(learnEntry.outcome.contains("Learn mode"))
  }

  @Test
  fun `diagnostics screen renders raw key log with working copy and clear buttons`() {
    RawKeyLog.record(
      keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
      isDown = true,
      route = KeyArrivalRoute.MEDIA_SESSION_NEXT.label,
      outcome = "Ran action: Start / pause timer",
      timestampLabel = "09:15:30.120",
    )

    lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
    composeTestRule.setContent {
      AuraClockTheme {
        clipboard = LocalClipboardManager.current
        EarbudChiplet.DiagnosticsScreen(onBack = {})
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("diag_raw_key_log_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("diag_raw_key_log_count").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("1/30").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("diag_key_log_copy_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    val clipboardText = composeTestRule.runOnIdle { clipboard.getText()?.text }
    assertNotNull(clipboardText)
    assertTrue(clipboardText!!.contains("KEYCODE_MEDIA_NEXT"))

    composeTestRule.onNodeWithTag("diag_key_log_clear_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(RawKeyLog.entries.value.isEmpty())
    composeTestRule.onNodeWithText("0/30").performScrollTo().assertIsDisplayed()
  }

  // ---- PART 2: PHOTO DIAL FACES ----

  @Test
  fun `face json round trip preserves all customization fields and rejects bad or invalid data`() {
    val original = PhotoFaceConfig(
      id = "photo_sunset_42",
      name = "Alpine Sunset",
      dialImageFile = "dial.png",
      zoom = 1.85f,
      panXRatio = -0.22f,
      panYRatio = 0.14f,
      rotationDeg = -12.5f,
      brightness = 0.12f,
      contrast = 1.25f,
      saturation = 1.35f,
      handStyle = PhotoHandStyle.DAUPHINE,
      hourColorArgb = Color.WHITE,
      minuteColorArgb = Color.LTGRAY,
      secondColorArgb = Color.RED,
      lumeOutlineEnabled = true,
      lumeColorArgb = Color.GREEN,
      hourLengthRatio = 0.52f,
      minuteLengthRatio = 0.80f,
      handWidthScale = 1.3f,
      showSecondHand = true,
      smoothSeconds = false,
      centerCapStyle = PhotoCenterCapStyle.OPEN_HUB,
      shadowStrength = 0.7f,
      dateEnabled = true,
      dateXRatio = 0.50f,
      dateYRatio = -0.15f,
      dateSizeRatio = 0.095f,
      dateColorArgb = Color.YELLOW,
      dateFontId = "font_custom_1",
      updatedAtMs = 1732000000000L,
    )

    val json = original.toJsonString()
    val parsed = PhotoFaceConfig.fromJson(json)
    assertTrue(parsed is PhotoFaceConfig.ParseResult.Success)
    val loaded = (parsed as PhotoFaceConfig.ParseResult.Success).config
    assertEquals(original, loaded)

    // Reject path traversal in dialImageFile or id
    assertTrue(
      PhotoFaceConfig.fromJson("""{"id":"../bad","name":"X","dialImageFile":"dial.png"}""")
        is PhotoFaceConfig.ParseResult.Error,
    )
    assertTrue(
      PhotoFaceConfig.fromJson("""{"id":"ok","name":"X","dialImageFile":"../secret.png"}""")
        is PhotoFaceConfig.ParseResult.Error,
    )
    // Reject invalid handStyle and corrupt JSON
    assertTrue(
      PhotoFaceConfig.fromJson("""{"id":"ok","name":"X","handStyle":"dragon"}""")
        is PhotoFaceConfig.ParseResult.Error,
    )
    assertTrue(PhotoFaceConfig.fromJson("{not-json") is PhotoFaceConfig.ParseResult.Error)
  }

  @Test
  fun `photo loader rejects images over 40 megapixels and over 25 MB`() {
    // Bounds validation above 40 MP (e.g. 8000 x 6000 = 48 MP)
    val tooBigMp = PhotoDialBitmapProcessor.validateBounds(8000, 6000)
    assertNotNull(tooBigMp)
    assertTrue(tooBigMp!!.contains("40 megapixels"))

    // Exactly 40 MP (8000 x 5000) is accepted
    assertNull(PhotoDialBitmapProcessor.validateBounds(8000, 5000))

    // File > 25 MB is rejected before decoding
    val giantFile = File(context.cacheDir, "giant_photo_test.jpg")
    try {
      RandomAccessFile(giantFile, "rw").use { raf ->
        raf.setLength(PhotoDialBitmapProcessor.MAX_PICKED_BYTES + 1024L)
      }
      val res = PhotoDialBitmapProcessor.loadPhotoFileSafely(giantFile)
      assertTrue(res is PhotoLoadResult.Error)
      assertTrue((res as PhotoLoadResult.Error).reason.contains("25 MB"))
    } finally {
      giantFile.delete()
    }
  }

  @Test
  fun `photo face save, zip export, re-import, delete, and ambient render round trip`() {
    val srcBitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(30, 80, 140))
    }
    val baked = PhotoDialBitmapProcessor.bakeCircularDialBitmap(
      source = srcBitmap,
      zoom = 1.2f,
      panXRatio = 0.05f,
      panYRatio = -0.05f,
      rotationDeg = 5f,
      brightness = 0.05f,
      contrast = 1.1f,
      saturation = 1.0f,
    )
    assertTrue(baked.width <= PhotoDialBitmapProcessor.MAX_SAVED_DIAL_PX)
    // Corners outside the circular crop must be transparent (alpha == 0)
    assertEquals(0, Color.alpha(baked.getPixel(0, 0)))

    val config = PhotoFaceConfig(
      id = "photo_alpine_test",
      name = "Alpine Test",
      handStyle = PhotoHandStyle.ARROW,
      dateEnabled = true,
      dateXRatio = 0.45f,
      dateYRatio = 0.10f,
    )
    val saved = WatchFaceRegistry.savePhotoFace(context, config, baked)
    assertTrue(saved.dialFile.exists())
    assertTrue(WatchFaceRegistry.faces.any { it.id == saved.id })

    // Export to zip
    val zipFile = File(context.cacheDir, "exported_photo_face.zip")
    FileOutputStream(zipFile).use { fos ->
      PhotoFaceStorage.exportFaceToZipStream(saved, fos)
    }
    assertTrue(zipFile.length() > 100L)

    // Delete original face, then re-import from zip URI
    assertTrue(WatchFaceRegistry.deletePhotoFace(context, saved.id))
    assertFalse(WatchFaceRegistry.faces.any { it.id == saved.id })

    val reimportRes = WatchFaceRegistry.importAnyArchive(context, Uri.fromFile(zipFile))
    assertTrue(reimportRes is WatchFaceRegistry.UnifiedImportResult.PhotoSuccess)
    val restored = (reimportRes as WatchFaceRegistry.UnifiedImportResult.PhotoSuccess).stored
    assertEquals("Alpine Test", restored.displayName)
    assertEquals(PhotoHandStyle.ARROW, restored.config.handStyle)
    assertTrue(restored.config.dateEnabled)

    // Verify rendering in AMBIENT & NORMAL modes works cleanly
    val restoredWatchFace = WatchFaceRegistry.findById(restored.id) as ImageDialFace
    composeTestRule.setContent {
      AuraClockTheme {
        restoredWatchFace.Render(
          timeProvider = { ClockTimeSnapshot.now() },
          accent = com.example.core.AuraThemeColor.COBALT,
          smoothSeconds = true,
          modifier = androidx.compose.ui.Modifier,
          renderMode = FaceRenderMode.AMBIENT,
        )
      }
    }
    composeTestRule.waitForIdle()
    zipFile.delete()
  }

  @Test
  fun `bad and path-traversal photo zip archives are rejected cleanly`() {
    val badZip = File(context.cacheDir, "bad_photo_traversal.zip")
    ZipOutputStream(FileOutputStream(badZip)).use { zos ->
      zos.putNextEntry(ZipEntry("face.json"))
      zos.write("""{"id":"ok","name":"Bad Zip","dialImageFile":"dial.png"}""".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("../evil.png"))
      zos.write(byteArrayOf(1, 2, 3, 4))
      zos.closeEntry()
    }
    val res = PhotoFaceStorage.importFromZipFile(context, badZip)
    assertTrue(res is PhotoZipImportResult.Error)
    badZip.delete()
  }

  // ---- PART 3: CUSTOM FONTS ----

  @Test
  fun `custom font validation accepts valid ttf, rejects corrupt bytes, and rejects files over 5 MB`() {
    // 1. Valid bundled TTF from R.font.jetbrains_mono
    val validFontStream = context.resources.openRawResource(R.font.jetbrains_mono)
    val validRes = CustomFontStorage.importFromStream(context, "JetBrainsTest.ttf", validFontStream)
    assertTrue("Expected valid TTF import to succeed, got $validRes", validRes is FontImportResult.Success)
    val imported = (validRes as FontImportResult.Success).font
    assertNotNull(CustomFontStorage.resolveTypeface(context, imported.id))

    // 2. Corrupt font rejected cleanly
    val corruptStream = ByteArrayInputStream("definitely not a font".toByteArray())
    val corruptRes = CustomFontStorage.importFromStream(context, "corrupt.ttf", corruptStream)
    assertTrue(corruptRes is FontImportResult.Error)

    // 3. Oversized (> 5 MB) font rejected cleanly
    val oversizeBytes = ByteArray(CustomFontStorage.MAX_FONT_BYTES.toInt() + 1024)
    oversizeBytes[0] = 0x00
    oversizeBytes[1] = 0x01
    oversizeBytes[2] = 0x00
    oversizeBytes[3] = 0x00
    val oversizeRes = CustomFontStorage.importFromStream(context, "huge.ttf", ByteArrayInputStream(oversizeBytes))
    assertTrue(oversizeRes is FontImportResult.Error)
    assertTrue((oversizeRes as FontImportResult.Error).reason.contains("5 MB"))

    // 4. Per-face font assignment & deletion cleanup
    val prefs = CoreSingletons.prefs(context)
    prefs.setFaceDigitalFontId("standby_digital", imported.id)
    assertEquals(imported.id, prefs.getFaceDigitalFontId("standby_digital"))

    assertTrue(CustomFontStorage.deleteFont(context, imported.id))
    assertNull(prefs.getFaceDigitalFontId("standby_digital"))
    assertNull(CustomFontStorage.resolveTypeface(context, imported.id))
  }

  @Test
  fun `definition parser accepts user custom font reference and default`() {
    val json = """
      {
        "id": "custom_font_test_face",
        "name": "Custom Font Face",
        "canvas": "circle",
        "layers": [
          {
            "id": "clock_text",
            "type": "text",
            "template": "{hour}:{minute}",
            "x": 0.0,
            "y": 0.0,
            "size": 0.3,
            "color": "#FFFFFF",
            "font": "user:my_font_1"
          },
          {
            "id": "date_slot",
            "type": "slot",
            "template": "{day}",
            "x": 0.0,
            "y": 0.4,
            "width": 0.3,
            "height": 0.12,
            "font": "default"
          }
        ]
      }
    """.trimIndent()
    val res = FaceDefinitionParser.parse(json)
    assertTrue(res is FaceDefinitionParser.ParseResult.Success)
    val def = (res as FaceDefinitionParser.ParseResult.Success).definition
    val textLayer = def.layers[0] as com.example.faces.engine.TextLayer
    assertEquals("user:my_font_1", textLayer.customFontRef)
  }

  @Test
  fun `settings watch faces section displays photo face button, fonts note, and digital font selector`() {
    // Import a valid font and save a sample photo face so all management rows show up
    CustomFontStorage.importFromStream(
      context,
      "SampleGrotesque.ttf",
      context.resources.openRawResource(R.font.space_grotesk),
    )
    val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
    WatchFaceRegistry.savePhotoFace(context, PhotoFaceConfig(id = "photo_ui_1", name = "UI Dial"), bmp)

    composeTestRule.setContent {
      AuraClockTheme {
        SettingsScreen()
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_section_faces"))
    composeTestRule.onNodeWithTag("settings_section_faces").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_add_photo_face_button").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("imported_photo_row_photo_ui_1").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("photo_edit_button_photo_ui_1").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("photo_export_button_photo_ui_1").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("photo_delete_button_photo_ui_1").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("settings_digital_font_card").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("settings_fonts_section").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_fonts_legal_note").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Only import fonts you have the right to use.").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_import_font_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("12:45 0123456789").performScrollTo().assertIsDisplayed()
  }
}

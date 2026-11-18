package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.core.AuraPrefs
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.core.StartupSafetyNet
import com.example.earbud.EarbudListenerService
import com.example.faces.FacesChiplet
import com.example.faces.WatchFaceRegistry
import com.example.timer.TimerForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupSafetyNetRobolectricTest {

  @get:Rule
  val composeRule = createEmptyComposeRule()

  @After
  fun tearDown() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    StartupSafetyNet.resetAllForTest(context)
    File(context.filesDir, "wff").deleteRecursively()
    File(context.filesDir, "last_crash.txt").delete()
    CoreSingletons.clearPreferences(context)
    FacesChiplet.reloadFaces(context)
  }

  @Test
  fun `earbud service calls startForeground cleanly when enabled and stops cleanly when disabled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = CoreSingletons.prefs(context)

    // Enabled path: enters foreground and stays active
    prefs.setEarbudEnabled(true)
    val enabledController = Robolectric.buildService(EarbudListenerService::class.java)
    val enabledService = enabledController.create().get()
    val enabledResult = enabledService.onStartCommand(Intent(context, EarbudListenerService::class.java), 0, 1)
    val enabledShadow = Shadows.shadowOf(enabledService)
    assertEquals(android.app.Service.START_STICKY, enabledResult)
    assertNotNull(enabledShadow.lastForegroundNotification)
    assertFalse(enabledShadow.isStoppedBySelf)
    enabledController.destroy()

    // Disabled path: enters foreground first, then stopsForeground and calls stopSelf()
    prefs.setEarbudEnabled(false)
    val disabledController = Robolectric.buildService(EarbudListenerService::class.java)
    val disabledService = disabledController.create().get()
    val disabledResult = disabledService.onStartCommand(Intent(context, EarbudListenerService::class.java), 0, 2)
    val disabledShadow = Shadows.shadowOf(disabledService)
    assertEquals(android.app.Service.START_NOT_STICKY, disabledResult)
    assertEquals(1002, disabledShadow.lastForegroundNotificationId)
    assertTrue("Expected stopForeground before stopSelf when disabled", disabledShadow.isForegroundStopped)
    assertTrue("Expected service to call stopSelf() when disabled", disabledShadow.isStoppedBySelf)
    disabledController.destroy()
  }

  @Test
  fun `timer foreground service enters foreground on start and cold stop actions without throwing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val startIntent = Intent(context, TimerForegroundService::class.java).apply {
      action = TimerForegroundService.ACTION_START
    }
    val startController = Robolectric.buildService(TimerForegroundService::class.java, startIntent)
    val startService = startController.create().get()
    val startResult = startService.onStartCommand(startIntent, 0, 1)
    val startShadow = Shadows.shadowOf(startService)
    assertEquals(android.app.Service.START_NOT_STICKY, startResult)
    assertNotNull(startShadow.lastForegroundNotification)
    assertEquals(1001, startShadow.lastForegroundNotificationId)
    assertFalse(startShadow.isStoppedBySelf)
    startController.destroy()

    for (action in listOf(TimerForegroundService.ACTION_STOP_SERVICE, TimerForegroundService.ACTION_USER_STOP)) {
      val intent = Intent(context, TimerForegroundService::class.java).apply { this.action = action }
      val controller = Robolectric.buildService(TimerForegroundService::class.java, intent)
      val service = controller.create().get()
      val result = service.onStartCommand(intent, 0, 2)

      val shadowService = Shadows.shadowOf(service)
      assertEquals(android.app.Service.START_NOT_STICKY, result)
      assertEquals("Expected startForeground(1001) before stopSelf for $action", 1001, shadowService.lastForegroundNotificationId)
      assertTrue("Expected stopForeground for $action", shadowService.isForegroundStopped)
      assertTrue("Expected stopSelf for $action", shadowService.isStoppedBySelf)
      controller.destroy()
    }
  }

  @Test
  fun `aura prefs tolerates mismatched value types from upgrades without throwing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val raw = context.getSharedPreferences("auraclock_prefs", Context.MODE_PRIVATE)
    raw.edit()
      .putString("timer_seconds", "not_an_int")
      .putInt("rounds_mode", 1)
      .putString("standby_dim", "0.5f_bad")
      .putBoolean("theme_color", true)
      .putString("standby_cal_ids", "not_a_set")
      .commit()

    val prefs = AuraPrefs(context)
    assertEquals(60, prefs.timerDurationSeconds.value)
    assertFalse(prefs.roundsMode.value)
    assertEquals(0.35f, prefs.standByDimLevel.value, 0.001f)
    assertEquals(AuraThemeColor.COBALT, prefs.themeColor.value)
    assertTrue(prefs.selectedCalendarIds.value.isEmpty())
  }

  @Test
  fun `optional startup step failure is isolated and recorded without stopping other chiplets`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val app = context as AuraClockApp

    StartupSafetyNet.simulatedStepFailureHook = { stepName ->
      if (stepName == "Stand By calendar") {
        throw IllegalStateException("Injected Stand By step failure")
      }
    }

    app.initializeApplication()

    val failures = StartupSafetyNet.stepFailures()
    assertEquals(1, failures.size)
    assertEquals("Stand By calendar", failures.first().stepName)
    assertTrue(failures.first().summary.contains("Injected Stand By step failure"))
    assertNotNull("Timer chiplet should still initialize", CoreSingletons.timerController)
    assertNotNull("Faces chiplet should still initialize", CoreSingletons.faceNavigator)
    assertTrue("CrashCatcher should persist step failure", CrashCatcher.readLastCrashLog(context).orEmpty().contains("Injected Stand By step failure"))
    assertFalse("Single isolated non-fatal step failure must not lock recovery screen", StartupSafetyNet.shouldShowRecoveryScreen(context))
  }

  @Test
  fun `two consecutive startup crashes trigger Startup problem screen with Copy Share and Safe Mode`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // Pre-import a sample WFF face so we can verify Safe Mode skips loading files/wff/.
    val sampleXml = """
      <WatchFace width="450" height="450">
        <Metadata key="title" value="Test WFF Safety" />
        <Scene><DigitalClock x="0" y="0" width="450" height="100"><TimeText format="HH:mm" /></DigitalClock></Scene>
      </WatchFace>
    """.trimIndent()
    com.example.faces.wff.WffStorageManager.importFromStream(
      context,
      "safety_test.xml",
      ByteArrayInputStream(sampleXml.toByteArray(Charsets.UTF_8)),
    )
    FacesChiplet.reloadFaces(context)
    assertTrue(WatchFaceRegistry.faces.any { it.displayName == "Test WFF Safety" })

    // Simulate two consecutive startup crashes cleanly.
    StartupSafetyNet.simulateStartupCrashForTest(context, RuntimeException("Simulated startup crash #1"))
    StartupSafetyNet.simulateStartupCrashForTest(context, IllegalStateException("Simulated startup crash #2"))
    assertEquals(2, StartupSafetyNet.consecutiveStartupCrashCount(context))
    assertTrue(StartupSafetyNet.shouldShowRecoveryScreen(context))

    ActivityScenario.launch(MainActivity::class.java).use {
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("startup_problem_title")
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains("Startup problem", substring = true)
      composeRule.onNodeWithTag("startup_problem_error_text")
        .assertTextContains("Simulated startup crash #2", substring = true)
      composeRule.onNodeWithTag("startup_problem_copy_button")
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
      composeRule.waitForIdle()

      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
      assertTrue(clipText.contains("Simulated startup crash #2"))

      val shareIntent = StartupSafetyNet.createShareIntent(context, clipText)
      assertEquals(Intent.ACTION_CHOOSER, shareIntent.action)
      val innerSend = IntentCompat.getParcelableExtra(shareIntent, Intent.EXTRA_INTENT, Intent::class.java)
      assertEquals(Intent.ACTION_SEND, innerSend?.action)
      assertTrue(innerSend?.getStringExtra(Intent.EXTRA_TEXT).orEmpty().contains("Simulated startup crash #2"))

      composeRule.onNodeWithTag("startup_problem_safe_mode_button")
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
      composeRule.waitForIdle()

      assertTrue("Expected Safe Mode active after tapping Start in safe mode", StartupSafetyNet.isSafeModeActive)
      assertFalse(StartupSafetyNet.shouldShowRecoveryScreen(context))
      assertFalse("Safe Mode must skip loading imported WFF faces", WatchFaceRegistry.faces.any { it.displayName == "Test WFF Safety" })
      composeRule.onNodeWithTag("tab_timer").assertIsDisplayed()
    }
  }

  @Test
  fun `clear imported faces and settings deletes wff folder resets prefs and starts clean`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = CoreSingletons.prefs(context)
    prefs.setThemeColor(AuraThemeColor.GOLD)

    val wffDir = File(context.filesDir, "wff/corrupt_face").apply { mkdirs() }
    val wffFile = File(wffDir, "watchface.xml").apply { writeText("<broken") }
    assertTrue(wffFile.exists())

    StartupSafetyNet.simulateStartupCrashForTest(context, RuntimeException("Crash A"))
    StartupSafetyNet.simulateStartupCrashForTest(context, RuntimeException("Crash B"))
    assertTrue(StartupSafetyNet.shouldShowRecoveryScreen(context))

    ActivityScenario.launch(MainActivity::class.java).use {
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("startup_problem_clear_button")
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
      composeRule.waitForIdle()

      assertFalse("files/wff should be deleted", File(context.filesDir, "wff").exists())
      assertEquals(AuraThemeColor.COBALT, prefs.themeColor.value)
      assertFalse(StartupSafetyNet.isSafeModeActive)
      assertFalse(StartupSafetyNet.shouldShowRecoveryScreen(context))
      assertEquals(0, StartupSafetyNet.consecutiveStartupCrashCount(context))
      composeRule.onNodeWithTag("tab_timer").assertIsDisplayed()
    }
  }
}

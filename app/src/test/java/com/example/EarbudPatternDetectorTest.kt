package com.example

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.AuraPrefs
import com.example.core.CoreSingletons
import com.example.core.LearnedPressPattern
import com.example.earbud.DetectorConfig
import com.example.earbud.DetectorState
import com.example.earbud.MediaKeyRouter
import com.example.earbud.PressKeyGroup
import com.example.earbud.PressPatternDetector
import com.example.earbud.PressPatternKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EarbudPatternDetectorTest {

  @After
  fun tearDown() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    CoreSingletons.clearPreferences(context)
  }

  @Test
  fun `pure detector classifies one two three and four presses cleanly`() {
    val cfg = DetectorConfig(windowMs = 400L, maxPlayPauseCount = 4)

    val single = PressPatternDetector.detectAll(
      listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_000L),
      cfg,
    )
    assertEquals(1, single.size)
    assertEquals(PressPatternKind.SINGLE, single.first().kind)
    assertEquals(1, single.first().count)

    val dbl = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_000L,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_220L,
      ),
      cfg,
    )
    assertEquals(1, dbl.size)
    assertEquals(PressPatternKind.DOUBLE, dbl.first().kind)
    assertEquals(2, dbl.first().count)

    val triple = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_000L,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_250L,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_500L,
      ),
      cfg,
    )
    assertEquals(1, triple.size)
    assertEquals(PressPatternKind.TRIPLE, triple.first().kind)
    assertEquals(3, triple.first().count)

    // Quadruple reaches max burst size (4) and emits immediately without waiting for timeout.
    val step1 = PressPatternDetector.onKeyPress(DetectorState(), KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1_000L, cfg)
    val step2 = PressPatternDetector.onKeyPress(step1.nextState, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1_150L, cfg)
    val step3 = PressPatternDetector.onKeyPress(step2.nextState, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1_300L, cfg)
    assertTrue(step3.emitted.isEmpty())
    val step4 = PressPatternDetector.onKeyPress(step3.nextState, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1_450L, cfg)
    assertEquals(1, step4.emitted.size)
    assertEquals(PressPatternKind.QUADRUPLE, step4.emitted.first().kind)
    assertEquals(4, step4.emitted.first().count)
    assertNull(step4.flushDeadlineMs)
  }

  @Test
  fun `pure detector acts immediately on first press when double and triple are disabled`() {
    val cfg = DetectorConfig(windowMs = 400L, maxPlayPauseCount = 1)
    val step = PressPatternDetector.onKeyPress(DetectorState(), KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 100L, cfg)
    assertEquals(1, step.emitted.size)
    assertEquals(PressPatternKind.SINGLE, step.emitted.first().kind)
    assertNull(step.flushDeadlineMs)
    assertTrue(step.nextState.isEmpty)
  }

  @Test
  fun `pure detector timing respects exact window edges`() {
    val cfg = DetectorConfig(windowMs = 400L, maxPlayPauseCount = 4)

    // Exactly at 400ms edge (t=1000 -> t=1400) stays within same burst -> DOUBLE.
    val atEdge = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_000L,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_400L,
      ),
      cfg,
    )
    assertEquals(listOf(PressPatternKind.DOUBLE), atEdge.map { it.kind })

    // At 401ms (t=1000 -> t=1401) crosses window edge -> two separate SINGLE presses.
    val pastEdge = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_000L,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_401L,
      ),
      cfg,
    )
    assertEquals(listOf(PressPatternKind.SINGLE, PressPatternKind.SINGLE), pastEdge.map { it.kind })

    // Incremental onTimeout: 399ms elapsed stays buffered; 400ms elapsed flushes.
    val first = PressPatternDetector.onKeyPress(DetectorState(), KeyEvent.KEYCODE_HEADSETHOOK, 1_000L, cfg)
    val tooEarly = PressPatternDetector.onTimeout(first.nextState, 1_399L, cfg)
    assertTrue(tooEarly.emitted.isEmpty())
    assertFalse(tooEarly.nextState.isEmpty)
    val onBoundary = PressPatternDetector.onTimeout(tooEarly.nextState, 1_400L, cfg)
    assertEquals(1, onBoundary.emitted.size)
    assertEquals(PressPatternKind.SINGLE, onBoundary.emitted.first().kind)
  }

  @Test
  fun `pure detector merges mixed Play Pause family codes and splits incompatible keys`() {
    val cfg = DetectorConfig(windowMs = 400L, maxPlayPauseCount = 4)

    // Play -> Pause -> HeadsetHook within window = single Triple press in Play/Pause family
    val mixedSameFamily = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY to 1_000L,
        KeyEvent.KEYCODE_MEDIA_PAUSE to 1_150L,
        KeyEvent.KEYCODE_HEADSETHOOK to 1_300L,
      ),
      cfg,
    )
    assertEquals(1, mixedSameFamily.size)
    assertEquals(PressKeyGroup.PLAY_PAUSE_FAMILY, mixedSameFamily.first().keyGroup)
    assertEquals(PressPatternKind.TRIPLE, mixedSameFamily.first().kind)
    assertEquals(3, mixedSameFamily.first().count)

    // Play/Pause followed 100ms later by Next flushes Play/Pause immediately and handles Next separately
    val crossFamily = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_000L,
        KeyEvent.KEYCODE_MEDIA_NEXT to 1_100L,
      ),
      cfg,
    )
    assertEquals(2, crossFamily.size)
    assertEquals(PressPatternKind.SINGLE, crossFamily[0].kind)
    assertEquals(PressPatternKind.NEXT, crossFamily[1].kind)
  }

  @Test
  fun `pure detector and learn analyzer handle learned patterns`() {
    val prevAsTriple = LearnedPressPattern(
      keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
      count = 1,
      actionId = AuraPrefs.ACTION_SPEAK_TIME,
      keyLabel = "Previous track",
    )
    val doubleNext = LearnedPressPattern(
      keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
      count = 2,
      actionId = AuraPrefs.ACTION_TIMER_TOGGLE,
      keyLabel = "Next track",
    )
    val cfg = DetectorConfig(
      windowMs = 400L,
      maxPlayPauseCount = 3,
      learnedPatterns = listOf(prevAsTriple, doubleNext),
    )

    val prevDetected = PressPatternDetector.detectAll(
      listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS to 1_000L),
      cfg,
    )
    assertEquals(1, prevDetected.size)
    assertEquals(prevAsTriple, prevDetected.first().matchedLearnedPattern)

    val doubleNextDetected = PressPatternDetector.detectAll(
      listOf(
        KeyEvent.KEYCODE_MEDIA_NEXT to 1_000L,
        KeyEvent.KEYCODE_MEDIA_NEXT to 1_200L,
      ),
      cfg,
    )
    assertEquals(1, doubleNextDetected.size)
    assertEquals(2, doubleNextDetected.first().count)
    assertEquals(doubleNext, doubleNextDetected.first().matchedLearnedPattern)

    // Learn Mode analyzer outputs expected plain-words summaries
    val tripleSummary = PressPatternDetector.analyzeLearnedEvents(
      listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY to 1_000L,
        KeyEvent.KEYCODE_MEDIA_PAUSE to 1_300L,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 1_600L,
      ),
    )
    assertNotNull(tripleSummary)
    assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, tripleSummary!!.keyCode)
    assertEquals(3, tripleSummary.count)
    assertEquals("Triple press arrives as 3 Play/Pause presses within 0.6 s", tripleSummary.plainSummary)

    val prevSummary = PressPatternDetector.analyzeLearnedEvents(
      listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS to 2_000L),
    )
    assertNotNull(prevSummary)
    assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, prevSummary!!.keyCode)
    assertEquals(1, prevSummary.count)
    assertEquals("Arrives as Previous track", prevSummary.plainSummary)
  }

  @Test
  fun `earbud defaults window clamping and learned pattern persistence behave as specified`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)

    assertEquals(AuraPrefs.ACTION_FORWARD_TO_MUSIC, prefs.playPauseActionId.value)
    assertEquals(AuraPrefs.ACTION_NONE, prefs.playPauseDoubleActionId.value)
    assertEquals(AuraPrefs.ACTION_SPEAK_TIME, prefs.playPauseTripleActionId.value)
    assertEquals(400, prefs.earbudPressWindowMs.value)

    prefs.setEarbudPressWindowMs(200)
    assertEquals(300, prefs.earbudPressWindowMs.value)
    prefs.setEarbudPressWindowMs(750)
    assertEquals(600, prefs.earbudPressWindowMs.value)
    prefs.setEarbudPressWindowMs(500)

    val pattern = LearnedPressPattern(
      keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
      count = 1,
      actionId = AuraPrefs.ACTION_SPEAK_TIME,
      keyLabel = "Previous track",
    )
    prefs.saveLearnedPattern(pattern)

    val reloaded = AuraPrefs(context)
    assertEquals(500, reloaded.earbudPressWindowMs.value)
    assertEquals(listOf(pattern), reloaded.learnedPatterns.value)

    reloaded.removeLearnedPattern(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1)
    assertTrue(AuraPrefs(context).learnedPatterns.value.isEmpty())
  }

  @Test
  fun `router forwards single press with 1s self-send loop guard and speaks on triple press`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    var fakeElapsedMs = 1_000L
    val forwardedKeys = mutableListOf<Int>()
    var lastPattern = ""
    var lastAction = ""
    var forwardCount = 0

    val router = MediaKeyRouter(
      context = context,
      prefs = prefs,
      onKeyDiagnostics = { _, _, _ -> },
      onPatternDiagnostics = { pattern, _, _, action ->
        lastPattern = pattern
        lastAction = action
      },
      onForwardResult = { ok -> if (ok) forwardCount++ },
      forwardMediaKey = { keyCode ->
        forwardedKeys += keyCode
        true
      },
      clockMs = { fakeElapsedMs },
    )

    // Single press under defaults waits 400ms then forwards to music app & enters 1s self-ignore window
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    fakeElapsedMs = 1_450L
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))

    assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), forwardedKeys)
    assertEquals(1, forwardCount)
    assertEquals("Single press", lastPattern)
    assertTrue(lastAction.contains("Forwarded"))

    // Echoed key event arriving 200ms after forwarding (at 1_650ms < 2_450ms) is ignored (returns false)
    fakeElapsedMs = 1_650L
    assertFalse(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    assertEquals(1, forwardedKeys.size)

    // After 1s ignore window expires (at 2_500ms), triple press within 400ms window speaks time
    fakeElapsedMs = 2_500L
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    fakeElapsedMs = 2_700L
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    fakeElapsedMs = 2_900L
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    fakeElapsedMs = 3_350L
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))

    assertEquals("Triple press", lastPattern)
    assertEquals("Speak current time", lastAction)
    // No extra forward happened for triple press
    assertEquals(1, forwardedKeys.size)
  }

  @Test
  fun `double press runs no app action, forward failures are counted, and learn mode intercepts presses`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AuraPrefs(context)
    var fakeElapsedMs = 5_000L
    var lastPattern = ""
    var lastAction = ""
    var failureCount = 0

    val router = MediaKeyRouter(
      context = context,
      prefs = prefs,
      onKeyDiagnostics = { _, _, _ -> },
      onPatternDiagnostics = { pattern, _, _, action ->
        lastPattern = pattern
        lastAction = action
      },
      onForwardResult = { ok -> if (!ok) failureCount++ },
      forwardMediaKey = { false },
      clockMs = { fakeElapsedMs },
    )

    // Double press: 2 presses within 400ms; fails forward cleanly and records failure without throwing
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    fakeElapsedMs = 5_150L
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    fakeElapsedMs = 5_600L
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))

    assertEquals("Double press", lastPattern)
    assertTrue(lastAction.startsWith("No action"))
    assertEquals(1, failureCount)

    // Learn mode intercepts presses without dispatching normal router actions
    fakeElapsedMs = 7_000L
    val captured = mutableListOf<Pair<Int, Long>>()
    router.setLearnCaptureListener { code, ts -> captured += code to ts }
    assertTrue(router.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
    fakeElapsedMs = 7_500L
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS to 7_000L), captured)
    assertEquals("Double press", lastPattern)
    router.setLearnCaptureListener(null)
  }
}

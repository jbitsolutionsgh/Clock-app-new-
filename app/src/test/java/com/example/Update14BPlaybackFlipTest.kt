package com.example

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.core.CoreSingletons
import com.example.earbud.EarbudChiplet
import com.example.earbud.EarbudListenerService
import com.example.earbud.PlaybackReprimeLogic
import com.example.earbud.ReprimePlanAction
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Update14BPlaybackFlipTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    CoreSingletons.clearPreferences(context)
    EarbudChiplet.resetDiagnosticsForTest()
  }

  @After
  fun tearDown() {
    EarbudChiplet.resetDiagnosticsForTest()
    CoreSingletons.clearPreferences(context)
  }

  @Test
  fun `shouldFlipPlaybackState obeys speaking, ignore window, take-over setting, and trigger rules`() {
    // Take-over off -> never flip
    assertFalse(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 10_000L,
        takeOverEnabled = false,
        lastSelfAudioMs = -1L,
        isCurrentlySpeaking = false,
        hasRelevantTrigger = true,
      ),
    )

    // Currently speaking -> never flip
    assertFalse(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 10_000L,
        takeOverEnabled = true,
        lastSelfAudioMs = -1L,
        isCurrentlySpeaking = true,
        hasRelevantTrigger = true,
      ),
    )

    // Inside 1-second self-audio ignore window -> never flip
    assertFalse(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 10_500L,
        takeOverEnabled = true,
        lastSelfAudioMs = 10_000L,
        isCurrentlySpeaking = false,
        hasRelevantTrigger = true,
      ),
    )
    assertFalse(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 10_999L,
        takeOverEnabled = true,
        lastSelfAudioMs = 10_000L,
        isCurrentlySpeaking = false,
        hasRelevantTrigger = true,
      ),
    )

    // No external audio / trigger when idle -> never flip ("Nothing changes when nothing is playing")
    assertFalse(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 11_000L,
        takeOverEnabled = true,
        lastSelfAudioMs = 10_000L,
        isCurrentlySpeaking = false,
        hasRelevantTrigger = false,
      ),
    )

    // Boundary: exactly 1,000 ms after last self-audio with take-over on and not speaking -> allowed
    assertTrue(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 11_000L,
        takeOverEnabled = true,
        lastSelfAudioMs = 10_000L,
        isCurrentlySpeaking = false,
        hasRelevantTrigger = true,
      ),
    )

    // Clean initial state with never any self-audio -> allowed when triggered
    assertTrue(
      PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = 5_000L,
        takeOverEnabled = true,
        lastSelfAudioMs = -1L,
        isCurrentlySpeaking = false,
        hasRelevantTrigger = true,
      ),
    )
  }

  @Test
  fun `evaluatePlaybackChange keeps debounce, 3s rate-limit, and take-over gating`() {
    val scheduled = PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 10_000L,
      hasExternalAudio = true,
      takeOverEnabled = true,
      lastSelfAudioMs = 5_000L,
      lastReprimeMs = -1L,
      isCurrentlySpeaking = false,
    )
    assertEquals(ReprimePlanAction.SCHEDULE, scheduled.action)
    assertEquals(PlaybackReprimeLogic.DEBOUNCE_MS, scheduled.delayMs)

    val rateLimited = PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 10_000L,
      hasExternalAudio = true,
      takeOverEnabled = true,
      lastSelfAudioMs = 5_000L,
      lastReprimeMs = 8_500L,
      isCurrentlySpeaking = false,
    )
    assertEquals(ReprimePlanAction.SCHEDULE, rateLimited.action)
    assertEquals(1_500L, rateLimited.delayMs)

    val stopped = PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = 10_000L,
      hasExternalAudio = false,
      takeOverEnabled = true,
      lastSelfAudioMs = 5_000L,
      lastReprimeMs = 5_000L,
      isCurrentlySpeaking = false,
    )
    assertEquals(ReprimePlanAction.CANCEL_PENDING, stopped.action)
  }

  @Test
  fun `service idle stays STATE_PAUSED with null metadata, while reprime flips PLAYING for 1_5s then PAUSED`() {
    val controller = Robolectric.buildService(EarbudListenerService::class.java).create()
    val service = controller.get()
    val session = service.mediaSessionForTest()!!

    assertEquals(PlaybackState.STATE_PAUSED, service.lastAppliedPlaybackStateForTest()?.state)
    assertNull(session.controller.metadata as MediaMetadata?)
    assertEquals(0, EarbudChiplet.ensureInitialized(context).diagnostics.value.stateFlipCount)
    assertEquals("STATE_PAUSED", EarbudChiplet.ensureInitialized(context).diagnostics.value.sessionPlaybackStateLabel)

    // Advance past the 1-second self-audio window from initial prime
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))

    // Trigger re-prime -> flips immediately to STATE_PLAYING with position 0, speed 1.0, same actions
    service.primeMediaButtonRouting(isReprime = true, externalAudioPlaying = true)
    val playingState = service.lastAppliedPlaybackStateForTest()!!
    assertEquals(PlaybackState.STATE_PLAYING, playingState.state)
    assertEquals(0L, playingState.position)
    assertEquals(1.0f, playingState.playbackSpeed, 0.001f)
    assertTrue((playingState.actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L)
    assertTrue((playingState.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L)
    assertNull(session.controller.metadata as MediaMetadata?)

    val diagMidFlip = EarbudChiplet.ensureInitialized(context).diagnostics.value
    assertEquals("STATE_PLAYING", diagMidFlip.sessionPlaybackStateLabel)
    assertEquals(1, diagMidFlip.stateFlipCount)
    assertFalse(diagMidFlip.lastStateFlipTimeLabel == "Never")

    // Before 1.5s finishes, session remains STATE_PLAYING
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
    assertEquals(PlaybackState.STATE_PLAYING, service.lastAppliedPlaybackStateForTest()?.state)

    // Past 1.5s total -> session returns cleanly to STATE_PAUSED
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    assertEquals(PlaybackState.STATE_PAUSED, service.lastAppliedPlaybackStateForTest()?.state)
    assertEquals("STATE_PAUSED", EarbudChiplet.ensureInitialized(context).diagnostics.value.sessionPlaybackStateLabel)

    controller.destroy()
    assertEquals("Inactive", EarbudChiplet.ensureInitialized(context).diagnostics.value.sessionPlaybackStateLabel)
  }

  @Test
  fun `take-over disabled never flips state, and pass-through returns to PLAYING for 1_5s after reactivation`() {
    val prefs = CoreSingletons.prefs(context)
    val controller = Robolectric.buildService(EarbudListenerService::class.java).create()
    val service = controller.get()
    val session = service.mediaSessionForTest()!!

    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))

    // 1. With take-over disabled, re-priming never flips state
    prefs.setEarbudTakeOverDuringAudio(false)
    service.primeMediaButtonRouting(isReprime = true, externalAudioPlaying = true)
    assertEquals(PlaybackState.STATE_PAUSED, service.lastAppliedPlaybackStateForTest()?.state)
    assertEquals(0, EarbudChiplet.ensureInitialized(context).diagnostics.value.stateFlipCount)

    // 2. Re-enable take-over and run past 1-second self-audio ignore window, then trigger single-press pass-through
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
    prefs.setEarbudTakeOverDuringAudio(true)

    assertTrue(service.forwardMediaKeyFromSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    // Immediately deactivated while dispatching key
    assertFalse(session.isActive)

    // After 300 ms reactivation delay -> session reactivated AND flipped to STATE_PLAYING for 1.5s
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(320))
    assertTrue(session.isActive)
    assertEquals(PlaybackState.STATE_PLAYING, service.lastAppliedPlaybackStateForTest()?.state)
    assertEquals(1, EarbudChiplet.ensureInitialized(context).diagnostics.value.stateFlipCount)

    // After another 1.5 s -> returns to STATE_PAUSED
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_550))
    assertEquals(PlaybackState.STATE_PAUSED, service.lastAppliedPlaybackStateForTest()?.state)

    controller.destroy()
  }

  @Test
  fun `diagnostics screen renders media session playback state, flip count, and last flip time`() {
    EarbudChiplet.recordSessionStateFlip(stateLabel = "STATE_PLAYING", timeLabel = "10:42:15")

    composeTestRule.setContent {
      AuraClockTheme {
        EarbudChiplet.DiagnosticsScreen(onBack = {})
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("diag_session_playback_state")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("STATE_PLAYING", substring = true)

    composeTestRule.onNodeWithTag("diag_state_flip_count")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("1", substring = true)

    composeTestRule.onNodeWithTag("diag_last_state_flip_time")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("10:42:15", substring = true)
  }
}

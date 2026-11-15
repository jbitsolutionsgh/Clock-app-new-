package com.example

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.AuraPrefs
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.earbud.MediaKeyRouter
import com.example.faces.FacesChiplet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
  fun `face navigator cycles through registered faces`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = CoreSingletons.prefs(context)
    val nav = FacesChiplet.ensureInitialized(context)
    val options = nav.getFaceOptions()
    assertEquals(4, options.size)

    prefs.setSelectedFaceId(options.first().first)
    nav.nextFace()
    assertEquals(options[1].first, prefs.selectedFaceId.value)
  }
}

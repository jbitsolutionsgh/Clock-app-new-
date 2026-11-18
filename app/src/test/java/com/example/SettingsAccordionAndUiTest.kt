package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.core.CoreSingletons
import com.example.core.ui.ALLOW_MULTIPLE_OPEN_SECTIONS
import com.example.core.ui.SettingsAccordion
import com.example.ui.theme.AuraClockTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAccordionAndUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @After
  fun tearDown() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    CoreSingletons.clearPreferences(context)
  }

  @Test
  fun `pure open-section logic starts closed, enforces single open, and supports multi-open constant`() {
    assertFalse(ALLOW_MULTIPLE_OPEN_SECTIONS)

    // Starts with all sections closed
    val initial = SettingsAccordion.initialOpenSections()
    assertTrue(initial.isEmpty())
    assertFalse(SettingsAccordion.isExpanded(initial, "timer"))

    // Opening one section marks only that section open
    val firstOpen = SettingsAccordion.toggleSection(initial, "timer")
    assertEquals(setOf("timer"), firstOpen)
    assertTrue(SettingsAccordion.isExpanded(firstOpen, "timer"))

    // Opening another section closes the previous one under single-open mode
    val secondOpen = SettingsAccordion.toggleSection(firstOpen, "earbuds")
    assertEquals(setOf("earbuds"), secondOpen)
    assertFalse(SettingsAccordion.isExpanded(secondOpen, "timer"))
    assertTrue(SettingsAccordion.isExpanded(secondOpen, "earbuds"))

    // Closing the open section returns to all closed
    val allClosed = SettingsAccordion.toggleSection(secondOpen, "earbuds")
    assertTrue(allClosed.isEmpty())

    // Changing allowMultiple = true allows several sections open and closes individually
    val multiOne = SettingsAccordion.toggleSection(initial, "timer", allowMultiple = true)
    val multiTwo = SettingsAccordion.toggleSection(multiOne, "faces", allowMultiple = true)
    assertEquals(setOf("timer", "faces"), multiTwo)
    val multiClosedOne = SettingsAccordion.toggleSection(multiTwo, "timer", allowMultiple = true)
    assertEquals(setOf("faces"), multiClosedOne)
  }

  @Test
  fun `settings opens all collapsed, lazily composes content, and switches one open section at a time`() {
    composeTestRule.setContent {
      AuraClockTheme {
        SettingsScreen()
      }
    }
    composeTestRule.waitForIdle()

    val headerTags = listOf(
      "settings_section_timer",
      "settings_section_faces",
      "settings_section_standby",
      "settings_section_earbuds",
      "settings_section_diagnostics",
    )
    headerTags.forEach { tag ->
      composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag(tag))
      composeTestRule.onNodeWithTag(tag)
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    // Inner section contents are not composed while collapsed
    composeTestRule.onNodeWithTag("settings_sound_name").assertDoesNotExist()
    composeTestRule.onNodeWithTag("settings_import_wff_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("settings_standby_mirror_switch").assertDoesNotExist()
    composeTestRule.onNodeWithTag("settings_learn_press_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("settings_open_diagnostics").assertDoesNotExist()

    // Scroll to Earbuds and voice -> verify default summary when closed, then open it
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_section_earbuds"))
    composeTestRule.onNodeWithText("Triple press: Speak current time").assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_section_earbuds").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_section_earbuds")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    composeTestRule.onNodeWithTag("settings_learn_press_button").assertExists()

    // Opening Earbuds scrolled its header to the top of settings_list (accounting for 8.dp top padding)
    composeTestRule.mainClock.advanceTimeBy(300L)
    composeTestRule.waitForIdle()
    val listTop = composeTestRule.onNodeWithTag("settings_list").fetchSemanticsNode().boundsInRoot.top
    val earbudsHeaderTop = composeTestRule.onNodeWithTag("settings_section_earbuds")
      .fetchSemanticsNode().boundsInRoot.top
    assertTrue(
      "Expected Earbuds header near list top ($listTop), got $earbudsHeaderTop",
      kotlin.math.abs(earbudsHeaderTop - listTop) < 30f,
    )

    // Closing an open section does not jump back to top if another item is above it; toggling closes cleanly
    composeTestRule.onNodeWithTag("settings_section_earbuds").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_section_earbuds")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    composeTestRule.onNodeWithTag("settings_learn_press_button").assertDoesNotExist()

    // Tap Watch faces -> opens Watch faces; tapping Earbuds next closes Watch faces
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_section_faces"))
    composeTestRule.onNodeWithTag("settings_section_faces").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_section_faces")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    composeTestRule.onNodeWithTag("settings_import_wff_button").assertExists()

    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_section_earbuds"))
    composeTestRule.onNodeWithTag("settings_section_earbuds").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_section_earbuds")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    composeTestRule.onNodeWithTag("settings_import_wff_button").assertDoesNotExist()
  }

  @Test
  fun `open section and scroll survive state restoration across rotation and sub-screen back`() {
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      AuraClockTheme {
        SettingsScreen()
      }
    }
    composeTestRule.waitForIdle()

    // Scroll to and open Diagnostics and about
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_section_diagnostics"))
    composeTestRule.onNodeWithTag("settings_section_diagnostics").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_open_diagnostics").assertExists()

    // Simulate Activity / configuration recreation (rotation)
    restorationTester.emulateSavedInstanceStateRestore()
    composeTestRule.waitForIdle()

    // Diagnostics section remains open and visible at the same scroll position
    composeTestRule.onNodeWithTag("settings_section_diagnostics")
      .assertIsDisplayed()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    composeTestRule.onNodeWithTag("settings_open_diagnostics").assertExists()

    // Scroll to button, record exact Diagnostics header position, open DiagnosticsScreen, and press Back
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_open_diagnostics"))
    val headerTopBefore = composeTestRule.onNodeWithTag("settings_section_diagnostics")
      .fetchSemanticsNode().boundsInRoot.top

    composeTestRule.onNodeWithTag("settings_open_diagnostics").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("diagnostics_back_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_section_diagnostics")
      .assertIsDisplayed()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    val headerTopAfter = composeTestRule.onNodeWithTag("settings_section_diagnostics")
      .fetchSemanticsNode().boundsInRoot.top
    assertEquals(headerTopBefore, headerTopAfter, 2f)
    composeTestRule.onNodeWithTag("settings_open_diagnostics").assertExists()
  }

  @Test
  fun `settings renders cleanly in light theme and large system font scale`() {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.8f)) {
        MaterialTheme(colorScheme = lightColorScheme()) {
          SettingsScreen()
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_section_timer").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_section_earbuds"))
    composeTestRule.onNodeWithTag("settings_section_earbuds").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_section_earbuds")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    composeTestRule.onNodeWithTag("settings_learn_press_button").assertExists()
  }
}

package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.ui.SettingsAccordion
import com.example.earbud.EarbudChiplet
import com.example.faces.FacesChiplet
import com.example.standby.StandByChiplet
import com.example.timer.TimerChiplet

private object SettingsSectionId {
  const val TIMER = "timer"
  const val FACES = "faces"
  const val STANDBY = "standby"
  const val EARBUDS = "earbuds"
  const val DIAGNOSTICS = "diagnostics"
}

private data class ScrollToSectionRequest(
  val index: Int,
  val id: String,
  val token: Int,
)

/**
 * Clean, collapsible Settings screen hosting chiplet-owned expandable sections.
 * Holds screen-level saveable scroll and open-section state so sub-screens, sheets,
 * pickers, and rotation preserve the user's exact place while cold restarts start collapsed.
 */
@Composable
fun SettingsScreen(
  onPreviewStandBy: () -> Unit = {},
  openDiagnosticsRequest: Boolean = false,
  onDiagnosticsRequestHandled: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showDiagnostics by rememberSaveable { mutableStateOf(false) }
  var openSectionIds by rememberSaveable(stateSaver = SettingsAccordion.saver()) {
    mutableStateOf(SettingsAccordion.initialOpenSections())
  }
  val listState = rememberLazyListState()
  var scrollRequest by remember { mutableStateOf<ScrollToSectionRequest?>(null) }

  LaunchedEffect(openDiagnosticsRequest) {
    if (openDiagnosticsRequest) {
      showDiagnostics = true
      onDiagnosticsRequestHandled()
    }
  }

  LaunchedEffect(scrollRequest) {
    val req = scrollRequest ?: return@LaunchedEffect
    kotlinx.coroutines.delay(220L)
    if (SettingsAccordion.isExpanded(openSectionIds, req.id)) {
      listState.animateScrollToItem(index = req.index, scrollOffset = 0)
    }
    scrollRequest = null
  }

  if (showDiagnostics) {
    BackHandler { showDiagnostics = false }
    EarbudChiplet.DiagnosticsScreen(
      onBack = { showDiagnostics = false },
      modifier = modifier,
    )
    return
  }

  val onToggleSection: (index: Int, id: String) -> Unit = { index, id ->
    val wasExpanded = SettingsAccordion.isExpanded(openSectionIds, id)
    openSectionIds = SettingsAccordion.toggleSection(openSectionIds, id)
    scrollRequest = if (!wasExpanded) {
      ScrollToSectionRequest(index = index, id = id, token = (scrollRequest?.token ?: 0) + 1)
    } else {
      null
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    Text(
      text = "Settings",
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
    )

    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .testTag("settings_list"),
      contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = SettingsSectionId.TIMER) {
        TimerChiplet.SettingsSection(
          expanded = SettingsAccordion.isExpanded(openSectionIds, SettingsSectionId.TIMER),
          onToggle = { onToggleSection(0, SettingsSectionId.TIMER) },
        )
      }

      item(key = SettingsSectionId.FACES) {
        FacesChiplet.SettingsSection(
          expanded = SettingsAccordion.isExpanded(openSectionIds, SettingsSectionId.FACES),
          onToggle = { onToggleSection(1, SettingsSectionId.FACES) },
        )
      }

      item(key = SettingsSectionId.STANDBY) {
        StandByChiplet.SettingsSection(
          expanded = SettingsAccordion.isExpanded(openSectionIds, SettingsSectionId.STANDBY),
          onToggle = { onToggleSection(2, SettingsSectionId.STANDBY) },
          onOpenPreview = onPreviewStandBy,
        )
      }

      item(key = SettingsSectionId.EARBUDS) {
        EarbudChiplet.SettingsSection(
          expanded = SettingsAccordion.isExpanded(openSectionIds, SettingsSectionId.EARBUDS),
          onToggle = { onToggleSection(3, SettingsSectionId.EARBUDS) },
        )
      }

      item(key = SettingsSectionId.DIAGNOSTICS) {
        EarbudChiplet.DiagnosticsAboutSection(
          expanded = SettingsAccordion.isExpanded(openSectionIds, SettingsSectionId.DIAGNOSTICS),
          onToggle = { onToggleSection(4, SettingsSectionId.DIAGNOSTICS) },
          onOpenDiagnostics = { showDiagnostics = true },
        )
      }
    }
  }
}

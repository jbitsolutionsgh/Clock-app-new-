package com.example.earbud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraPrefs
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.core.LearnedPressPattern
import com.example.core.ui.CollapsibleSection
import kotlin.math.roundToInt

private enum class EarbudActionSlot(val title: String) {
  PLAY_PAUSE("Play / Pause (single press)"),
  PLAY_PAUSE_DOUBLE("Play / Pause (double press)"),
  PLAY_PAUSE_TRIPLE("Play / Pause (triple press)"),
  NEXT("Next track (MEDIA_NEXT)"),
  PREVIOUS("Previous track (MEDIA_PREVIOUS)"),
}

/**
 * Earbuds and voice settings section owned by the earbud chiplet.
 */
@Composable
internal fun EarbudSettingsSection(
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }

  val earbudEnabled by prefs.earbudEnabled.collectAsStateWithLifecycle()
  val playPauseAction by prefs.playPauseActionId.collectAsStateWithLifecycle()
  val playPauseTripleAction by prefs.playPauseTripleActionId.collectAsStateWithLifecycle()

  val summary = when {
    !earbudEnabled -> "Listener off"
    playPauseTripleAction != AuraPrefs.ACTION_NONE ->
      "Triple press: ${EarbudChiplet.actionLabel(playPauseTripleAction)}"
    else -> "Single press: ${EarbudChiplet.actionLabel(playPauseAction)}"
  }

  CollapsibleSection(
    title = "Earbuds and voice",
    summary = summary,
    icon = Icons.Default.Headphones,
    expanded = expanded,
    onToggle = onToggle,
    headerTestTag = "settings_section_earbuds",
    modifier = modifier,
  ) {
    EarbudSettingsBody()
  }
}

/**
 * Diagnostics and about settings section owned by the earbud chiplet.
 */
@Composable
internal fun DiagnosticsAboutSettingsSection(
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val controller = remember { EarbudChiplet.ensureInitialized(context) }
  val diag by controller.diagnostics.collectAsStateWithLifecycle()
  val hasCrash = remember(expanded) { CrashCatcher.hasCrashLog(context) }

  val summary = when {
    hasCrash -> "Crash log saved • Tap to review"
    diag.serviceRunning -> "Service running • Offline only"
    else -> "Service stopped • Offline only"
  }

  CollapsibleSection(
    title = "Diagnostics and about",
    summary = summary,
    icon = Icons.Default.Build,
    expanded = expanded,
    onToggle = onToggle,
    headerTestTag = "settings_section_diagnostics",
    modifier = modifier,
  ) {
    val speaker = remember { CoreSingletons.speaker(context) }
    val ttsReady by speaker.isReady.collectAsStateWithLifecycle()
    val ttsStatus by speaker.statusMessage.collectAsStateWithLifecycle()

    Text(
      text = "AuraClock • Offline only (no internet permission)",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = "Earbud listener: ${if (diag.serviceRunning) "Running" else "Stopped"} • TTS: ${if (ttsReady) "Ready ($ttsStatus)" else ttsStatus}",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = if (hasCrash) "A local crash log is saved on this device." else "No uncaught crashes recorded.",
      style = MaterialTheme.typography.bodyMedium,
      color = if (hasCrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
      onClick = onOpenDiagnostics,
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("settings_open_diagnostics"),
    ) {
      Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text("Open Diagnostics & Crash Log")
    }
  }
}

@Composable
private fun EarbudSettingsBody() {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }
  val earbudController = remember { EarbudChiplet.ensureInitialized(context) }
  val speaker = remember { CoreSingletons.speaker(context) }

  val earbudEnabled by prefs.earbudEnabled.collectAsStateWithLifecycle()
  val earbudTakeOver by prefs.earbudTakeOverDuringAudio.collectAsStateWithLifecycle()
  val playPauseAction by prefs.playPauseActionId.collectAsStateWithLifecycle()
  val playPauseDoubleAction by prefs.playPauseDoubleActionId.collectAsStateWithLifecycle()
  val playPauseTripleAction by prefs.playPauseTripleActionId.collectAsStateWithLifecycle()
  val pressWindowMs by prefs.earbudPressWindowMs.collectAsStateWithLifecycle()
  val learnedPatterns by prefs.learnedPatterns.collectAsStateWithLifecycle()
  val learnModeState by EarbudChiplet.learnModeState.collectAsStateWithLifecycle()
  val nextAction by prefs.nextActionId.collectAsStateWithLifecycle()
  val previousAction by prefs.previousActionId.collectAsStateWithLifecycle()

  var editingActionSlot by remember { mutableStateOf<EarbudActionSlot?>(null) }
  var editingLearnedPattern by remember { mutableStateOf<LearnedPressPattern?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      if (EarbudChiplet.learnModeState.value is LearnModeState.Listening) {
        EarbudChiplet.cancelLearnMode()
      }
    }
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Earbud button listener",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Keeps MediaSession ready for button presses",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = earbudEnabled,
      onCheckedChange = { earbudController.setListenerEnabled(it) },
      modifier = Modifier.testTag("settings_earbud_switch"),
    )
  }

  Text(
    text = "Earbud buttons while other audio is playing",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    EarbudChoiceRow(
      label = "AuraClock actions (default)",
      selected = earbudTakeOver,
      testTag = "earbud_other_audio_auraclock",
      onClick = {
        prefs.setEarbudTakeOverDuringAudio(true)
        EarbudChiplet.notifyOtherAudioPolicyChanged(context)
      },
    )
    EarbudChoiceRow(
      label = "Let the music app keep them",
      selected = !earbudTakeOver,
      testTag = "earbud_other_audio_music",
      onClick = { prefs.setEarbudTakeOverDuringAudio(false) },
    )
  }

  EarbudActionMappingRow(
    label = EarbudActionSlot.PLAY_PAUSE.title,
    currentAction = EarbudChiplet.actionLabel(playPauseAction),
    testTag = "settings_action_play_pause",
    onClick = { editingActionSlot = EarbudActionSlot.PLAY_PAUSE },
  )
  EarbudActionMappingRow(
    label = EarbudActionSlot.PLAY_PAUSE_DOUBLE.title,
    currentAction = EarbudChiplet.actionLabel(playPauseDoubleAction),
    testTag = "settings_action_play_pause_double",
    onClick = { editingActionSlot = EarbudActionSlot.PLAY_PAUSE_DOUBLE },
  )
  Text(
    text = "Many earbuds use a double press for calls, so it may never reach this app.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .padding(horizontal = 4.dp)
      .testTag("settings_double_press_note"),
  )
  EarbudActionMappingRow(
    label = EarbudActionSlot.PLAY_PAUSE_TRIPLE.title,
    currentAction = EarbudChiplet.actionLabel(playPauseTripleAction),
    testTag = "settings_action_play_pause_triple",
    onClick = { editingActionSlot = EarbudActionSlot.PLAY_PAUSE_TRIPLE },
  )
  EarbudActionMappingRow(
    label = EarbudActionSlot.NEXT.title,
    currentAction = EarbudChiplet.actionLabel(nextAction),
    testTag = "settings_action_next",
    onClick = { editingActionSlot = EarbudActionSlot.NEXT },
  )
  EarbudActionMappingRow(
    label = EarbudActionSlot.PREVIOUS.title,
    currentAction = EarbudChiplet.actionLabel(previousAction),
    testTag = "settings_action_previous",
    onClick = { editingActionSlot = EarbudActionSlot.PREVIOUS },
  )

  Text(
    text = "Multi-press window: $pressWindowMs ms",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.testTag("settings_press_window_label"),
  )
  Slider(
    value = pressWindowMs.toFloat(),
    onValueChange = { raw ->
      val snapped = ((raw / 50f).roundToInt() * 50).coerceIn(300, 600)
      prefs.setEarbudPressWindowMs(snapped)
    },
    valueRange = 300f..600f,
    steps = 5,
    modifier = Modifier.testTag("settings_press_window_slider"),
  )

  Button(
    onClick = { EarbudChiplet.startLearnMode(context) },
    modifier = Modifier
      .fillMaxWidth()
      .height(48.dp)
      .testTag("settings_learn_press_button"),
  ) {
    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text("Learn a press")
  }

  if (learnedPatterns.isNotEmpty()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "Learned patterns",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      learnedPatterns.forEach { pattern ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { editingLearnedPattern = pattern }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = EarbudChiplet.formatPatternLabel(pattern),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = EarbudChiplet.actionLabel(pattern.actionId),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          IconButton(
            onClick = { prefs.removeLearnedPattern(pattern.keyCode, pattern.count) },
          ) {
            Icon(
              imageVector = Icons.Default.DeleteOutline,
              contentDescription = "Remove learned pattern",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }

  OutlinedButton(
    onClick = { speaker.speakCurrentTime() },
    modifier = Modifier
      .fillMaxWidth()
      .height(48.dp)
      .testTag("settings_speak_time_button"),
  ) {
    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text("Test voice (Speak current time)")
  }

  OutlinedButton(
    onClick = { openBatteryOptimizationSettings(context) },
    modifier = Modifier
      .fillMaxWidth()
      .height(48.dp)
      .testTag("settings_battery_opt_button"),
  ) {
    Icon(Icons.Default.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text("Ignore battery optimization")
  }

  editingActionSlot?.let { slot ->
    val currentId = when (slot) {
      EarbudActionSlot.PLAY_PAUSE -> playPauseAction
      EarbudActionSlot.PLAY_PAUSE_DOUBLE -> playPauseDoubleAction
      EarbudActionSlot.PLAY_PAUSE_TRIPLE -> playPauseTripleAction
      EarbudActionSlot.NEXT -> nextAction
      EarbudActionSlot.PREVIOUS -> previousAction
    }
    AlertDialog(
      onDismissRequest = { editingActionSlot = null },
      title = { Text(slot.title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          EarbudChiplet.availableActions().forEach { (id, label) ->
            val selected = id == currentId
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                  if (selected) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable {
                  when (slot) {
                    EarbudActionSlot.PLAY_PAUSE -> prefs.setPlayPauseActionId(id)
                    EarbudActionSlot.PLAY_PAUSE_DOUBLE -> prefs.setPlayPauseDoubleActionId(id)
                    EarbudActionSlot.PLAY_PAUSE_TRIPLE -> prefs.setPlayPauseTripleActionId(id)
                    EarbudActionSlot.NEXT -> prefs.setNextActionId(id)
                    EarbudActionSlot.PREVIOUS -> prefs.setPreviousActionId(id)
                  }
                  editingActionSlot = null
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(label, color = MaterialTheme.colorScheme.onSurface)
              if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { editingActionSlot = null }) { Text("Close") }
      },
    )
  }

  editingLearnedPattern?.let { pattern ->
    AlertDialog(
      onDismissRequest = { editingLearnedPattern = null },
      title = { Text(EarbudChiplet.formatPatternLabel(pattern)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          EarbudChiplet.availableActions().forEach { (id, label) ->
            val selected = id == pattern.actionId
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                  if (selected) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable {
                  prefs.saveLearnedPattern(pattern.copy(actionId = id))
                  editingLearnedPattern = null
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(label, color = MaterialTheme.colorScheme.onSurface)
              if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { editingLearnedPattern = null }) { Text("Close") }
      },
    )
  }

  when (val learnState = learnModeState) {
    LearnModeState.Idle -> Unit
    is LearnModeState.Listening -> {
      AlertDialog(
        onDismissRequest = { EarbudChiplet.cancelLearnMode() },
        title = { Text("Learn a press") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "Press your earbud button now (${learnState.remainingSeconds} s left).",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("learn_mode_countdown_text"),
            )
            Text(
              text = if (learnState.capturedCount == 0) {
                "Waiting for earbud press..."
              } else {
                "Captured ${learnState.capturedCount} press(es): ${learnState.latestKeyName.orEmpty()}"
              },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("learn_mode_status_text"),
            )
          }
        },
        confirmButton = {
          if (learnState.capturedCount > 0) {
            TextButton(onClick = { EarbudChiplet.finishLearnModeNow() }) {
              Text("Done now")
            }
          }
        },
        dismissButton = {
          TextButton(onClick = { EarbudChiplet.cancelLearnMode() }) {
            Text("Cancel")
          }
        },
      )
    }
    is LearnModeState.Captured -> {
      AlertDialog(
        onDismissRequest = { EarbudChiplet.cancelLearnMode() },
        title = { Text("Learned press") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
              text = learnState.candidate.plainSummary,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("learn_mode_result_summary"),
            )
            Text(
              text = "Choose what this press should do:",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            EarbudChiplet.availableActions().forEach { (id, label) ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant)
                  .clickable { EarbudChiplet.saveLearnedPattern(context, learnState.candidate, id) }
                  .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(label, color = MaterialTheme.colorScheme.onSurface)
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { EarbudChiplet.cancelLearnMode() }) { Text("Cancel") }
        },
      )
    }
    LearnModeState.TimedOutEmpty -> {
      AlertDialog(
        onDismissRequest = { EarbudChiplet.cancelLearnMode() },
        title = { Text("No press detected") },
        text = {
          Text("No earbud button presses arrived during the 6 seconds. Make sure Bluetooth is connected and Earbud button listener is on.")
        },
        confirmButton = {
          TextButton(onClick = { EarbudChiplet.startLearnMode(context) }) { Text("Try again") }
        },
        dismissButton = {
          TextButton(onClick = { EarbudChiplet.cancelLearnMode() }) { Text("Close") }
        },
      )
    }
  }
}

@Composable
private fun EarbudActionMappingRow(
  label: String,
  currentAction: String,
  testTag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp)
      .testTag(testTag),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      Text(currentAction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Choose action")
  }
}

@Composable
private fun EarbudChoiceRow(
  label: String,
  selected: Boolean,
  testTag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
      )
      .border(
        width = if (selected) 1.5.dp else 1.dp,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        shape = RoundedCornerShape(12.dp),
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 12.dp)
      .testTag(testTag),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (selected) {
      Icon(
        imageVector = Icons.Default.Check,
        contentDescription = "Selected",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

private fun openBatteryOptimizationSettings(context: Context) {
  val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
    data = Uri.parse("package:${context.packageName}")
  }
  runCatching {
    context.startActivity(direct)
  }.onFailure {
    runCatching {
      context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
  }
}

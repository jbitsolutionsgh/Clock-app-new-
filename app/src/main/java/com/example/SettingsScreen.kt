package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.earbud.EarbudChiplet
import com.example.standby.StandByChiplet
import com.example.timer.TimerChiplet
import com.example.ui.theme.OledBlack
import kotlin.math.roundToInt

private enum class ActionSlot(val title: String) {
  PLAY_PAUSE("Play / Pause (single press)"),
  PLAY_PAUSE_DOUBLE("Play / Pause (double press)"),
  NEXT("Next (held volume up)"),
  PREVIOUS("Previous (held volume down)"),
}

/**
 * Unified Settings screen for earbud actions, timer sound options, colour theme,
 * Stand By screensaver options, and Diagnostics access.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  onPreviewStandBy: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }
  val earbudController = remember { EarbudChiplet.ensureInitialized(context) }

  var showDiagnostics by remember { mutableStateOf(false) }
  if (showDiagnostics) {
    BackHandler { showDiagnostics = false }
    EarbudChiplet.DiagnosticsScreen(
      onBack = { showDiagnostics = false },
      modifier = modifier,
    )
    return
  }

  val themeColor by prefs.themeColor.collectAsStateWithLifecycle()
  val beepDurationMs by prefs.beepDurationMs.collectAsStateWithLifecycle()
  val useAlarmStream by prefs.useAlarmStream.collectAsStateWithLifecycle()
  val soundUri by prefs.soundUri.collectAsStateWithLifecycle()
  val soundName by prefs.soundName.collectAsStateWithLifecycle()
  val timerFullScreenByDefault by prefs.timerFullScreenByDefault.collectAsStateWithLifecycle()
  val timerGoFullScreenOnStart by prefs.timerGoFullScreenOnStart.collectAsStateWithLifecycle()

  val earbudEnabled by prefs.earbudEnabled.collectAsStateWithLifecycle()
  val playPauseAction by prefs.playPauseActionId.collectAsStateWithLifecycle()
  val playPauseDoubleAction by prefs.playPauseDoubleActionId.collectAsStateWithLifecycle()
  val nextAction by prefs.nextActionId.collectAsStateWithLifecycle()
  val previousAction by prefs.previousActionId.collectAsStateWithLifecycle()

  var editingActionSlot by remember { mutableStateOf<ActionSlot?>(null) }

  // (a) System sound picker launcher
  val ringtoneLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      @Suppress("DEPRECATION")
      val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
      } else {
        result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
      }
      if (uri != null) {
        val title = RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "System Sound"
        prefs.setTimerSound(uri.toString(), title)
      } else {
        prefs.setTimerSound(null, "Default Tone")
      }
    }
  }

  // (b) Document file picker launcher (audio/*) with persistable read permission
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }
      val fileName = resolveDisplayName(context, uri) ?: "Audio File"
      prefs.setTimerSound(uri.toString(), fileName)
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(OledBlack)
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      text = "Settings",
      style = MaterialTheme.typography.headlineMedium,
      color = Color.White,
    )

    // 1. Timer Sound Options
    SettingsCard(title = "Timer Sound", icon = Icons.Default.VolumeUp) {
      Text(
        text = "Current sound: $soundName",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("settings_sound_name"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
              putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
              putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
              putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
              soundUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
            }
            ringtoneLauncher.launch(intent)
          },
          modifier = Modifier.weight(1f).testTag("settings_pick_system_sound"),
        ) {
          Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("System picker")
        }

        OutlinedButton(
          onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
          modifier = Modifier.weight(1f).testTag("settings_pick_audio_file"),
        ) {
          Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Audio file")
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = { TimerChiplet.playTestSound(context) },
          modifier = Modifier.weight(1f).testTag("settings_test_sound"),
        ) {
          Text("Test sound")
        }
        if (soundUri != null) {
          TextButton(
            onClick = { prefs.setTimerSound(null, "Default Tone") },
            modifier = Modifier.testTag("settings_reset_sound"),
          ) {
            Text("Use default")
          }
        }
      }

      val beepSec = beepDurationMs / 1000f
      Text(
        text = "Beep length: ${"%.1f".format(beepSec)} s",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Slider(
        value = beepSec,
        onValueChange = { raw ->
          val snapped = (raw * 2f).roundToInt() * 500
          prefs.setBeepDurationMs(snapped)
        },
        valueRange = 0.5f..5.0f,
        steps = 8,
        modifier = Modifier.testTag("settings_beep_duration_slider"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Use Alarm volume", style = MaterialTheme.typography.titleMedium)
          Text(
            text = if (useAlarmStream) "Plays on Alarm stream" else "Plays on Media stream",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = useAlarmStream,
          onCheckedChange = { prefs.setUseAlarmStream(it) },
          modifier = Modifier.testTag("settings_alarm_stream_switch"),
        )
      }
    }

    // 1b. Timer Display (Full-Screen Classroom Options)
    SettingsCard(title = "Full-Screen Timer", icon = Icons.Default.Fullscreen) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Full-screen timer by default", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Opens the Timer tab in full-screen mode",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = timerFullScreenByDefault,
          onCheckedChange = { prefs.setTimerFullScreenByDefault(it) },
          modifier = Modifier.testTag("settings_timer_fullscreen_default_switch"),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Go full screen when the timer starts", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Automatically expands when you press Start",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = timerGoFullScreenOnStart,
          onCheckedChange = { prefs.setTimerGoFullScreenOnStart(it) },
          modifier = Modifier.testTag("settings_timer_fullscreen_on_start_switch"),
        )
      }
    }

    // 2. Earbud Button Actions
    SettingsCard(title = "Bluetooth Earbuds", icon = Icons.Default.Headphones) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Earbud button listener", style = MaterialTheme.typography.titleMedium)
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

      ActionMappingRow(
        label = ActionSlot.PLAY_PAUSE.title,
        currentAction = EarbudChiplet.actionLabel(playPauseAction),
        testTag = "settings_action_play_pause",
        onClick = { editingActionSlot = ActionSlot.PLAY_PAUSE },
      )
      ActionMappingRow(
        label = ActionSlot.PLAY_PAUSE_DOUBLE.title,
        currentAction = EarbudChiplet.actionLabel(playPauseDoubleAction),
        testTag = "settings_action_play_pause_double",
        onClick = { editingActionSlot = ActionSlot.PLAY_PAUSE_DOUBLE },
      )
      ActionMappingRow(
        label = ActionSlot.NEXT.title,
        currentAction = EarbudChiplet.actionLabel(nextAction),
        testTag = "settings_action_next",
        onClick = { editingActionSlot = ActionSlot.NEXT },
      )
      ActionMappingRow(
        label = ActionSlot.PREVIOUS.title,
        currentAction = EarbudChiplet.actionLabel(previousAction),
        testTag = "settings_action_previous",
        onClick = { editingActionSlot = ActionSlot.PREVIOUS },
      )

      OutlinedButton(
        onClick = { openBatteryOptimizationRequest(context) },
        modifier = Modifier.fillMaxWidth().testTag("settings_battery_opt_button"),
      ) {
        Icon(Icons.Default.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Ignore battery optimization")
      }
    }

    // 3. Colour Theme
    SettingsCard(title = "Colour Theme", icon = Icons.Default.Palette) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AuraThemeColor.entries.forEach { option ->
          val selected = option == themeColor
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(14.dp))
              .background(
                if (selected) option.container else MaterialTheme.colorScheme.surfaceVariant,
              )
              .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) option.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp),
              )
              .clickable { prefs.setThemeColor(option) }
              .padding(horizontal = 12.dp, vertical = 10.dp)
              .testTag("theme_chip_${option.id}"),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(option.primary),
              contentAlignment = Alignment.Center,
            ) {
              if (selected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = Color.Black,
                  modifier = Modifier.size(12.dp),
                )
              }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(option.label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
          }
        }
      }
    }

    // 4. Stand By Screensaver & Calendar (owned by standby chiplet)
    StandByChiplet.SettingsSection(onOpenPreview = onPreviewStandBy)

    // 5. Diagnostics Entry
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        .clip(RoundedCornerShape(18.dp))
        .clickable { showDiagnostics = true }
        .testTag("settings_open_diagnostics"),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text("Diagnostics & Crash Log", style = MaterialTheme.typography.titleMedium)
            Text(
              text = "Service status, TTS check, last media key, crash log",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open Diagnostics")
      }
    }
  }

  editingActionSlot?.let { slot ->
    val currentId = when (slot) {
      ActionSlot.PLAY_PAUSE -> playPauseAction
      ActionSlot.PLAY_PAUSE_DOUBLE -> playPauseDoubleAction
      ActionSlot.NEXT -> nextAction
      ActionSlot.PREVIOUS -> previousAction
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
                    ActionSlot.PLAY_PAUSE -> prefs.setPlayPauseActionId(id)
                    ActionSlot.PLAY_PAUSE_DOUBLE -> prefs.setPlayPauseDoubleActionId(id)
                    ActionSlot.NEXT -> prefs.setNextActionId(id)
                    ActionSlot.PREVIOUS -> prefs.setPreviousActionId(id)
                  }
                  editingActionSlot = null
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(label, color = Color.White)
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
}

@Composable
private fun SettingsCard(
  title: String,
  icon: ImageVector,
  content: @Composable () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
      }
      content()
    }
  }
}

@Composable
private fun ActionMappingRow(
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
      Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
      Text(currentAction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Choose action")
  }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? =
  runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) cursor.getString(idx) else null
      } else null
    }
  }.getOrNull()

private fun openBatteryOptimizationRequest(context: Context) {
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

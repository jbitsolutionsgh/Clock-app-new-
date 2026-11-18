package com.example.timer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CoreSingletons
import com.example.core.ui.CollapsibleSection
import com.example.ui.theme.JetBrainsMonoFamily
import kotlin.math.roundToInt

/**
 * Timer and rounds settings section owned by the timer chiplet.
 */
@Composable
internal fun TimerSettingsSection(
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }

  val soundName by prefs.soundName.collectAsStateWithLifecycle()
  val beepDurationMs by prefs.beepDurationMs.collectAsStateWithLifecycle()
  val roundsMode by prefs.roundsMode.collectAsStateWithLifecycle()

  val beepLabel = "%.1f s".format(beepDurationMs / 1000f)
  val summary = if (roundsMode) {
    "$soundName • $beepLabel • Rounds on"
  } else {
    "$soundName • $beepLabel beep"
  }

  CollapsibleSection(
    title = "Timer and rounds",
    summary = summary,
    icon = Icons.Default.HourglassBottom,
    expanded = expanded,
    onToggle = onToggle,
    headerTestTag = "settings_section_timer",
    modifier = modifier,
  ) {
    TimerSettingsBody()
  }
}

@Composable
private fun TimerSettingsBody() {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }

  val beepDurationMs by prefs.beepDurationMs.collectAsStateWithLifecycle()
  val useAlarmStream by prefs.useAlarmStream.collectAsStateWithLifecycle()
  val soundUri by prefs.soundUri.collectAsStateWithLifecycle()
  val soundName by prefs.soundName.collectAsStateWithLifecycle()
  val timerFullScreenByDefault by prefs.timerFullScreenByDefault.collectAsStateWithLifecycle()
  val timerGoFullScreenOnStart by prefs.timerGoFullScreenOnStart.collectAsStateWithLifecycle()
  val roundsMode by prefs.roundsMode.collectAsStateWithLifecycle()
  val roundGapSeconds by prefs.roundGapSeconds.collectAsStateWithLifecycle()
  val turnIndicatorEnabled by prefs.turnIndicatorEnabled.collectAsStateWithLifecycle()
  val turnGroupSize by prefs.turnGroupSize.collectAsStateWithLifecycle()

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
      val fileName = resolveAudioDisplayName(context, uri) ?: "Audio File"
      prefs.setTimerSound(uri.toString(), fileName)
    }
  }

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

  SettingsSwitchRow(
    title = "Use Alarm volume",
    subtitle = if (useAlarmStream) "Plays on Alarm stream" else "Plays on Media stream",
    checked = useAlarmStream,
    onCheckedChange = { prefs.setUseAlarmStream(it) },
    testTag = "settings_alarm_stream_switch",
  )

  SettingsSwitchRow(
    title = "Full-screen timer by default",
    subtitle = "Opens the Timer tab in full-screen mode",
    checked = timerFullScreenByDefault,
    onCheckedChange = { prefs.setTimerFullScreenByDefault(it) },
    testTag = "settings_timer_fullscreen_default_switch",
  )

  SettingsSwitchRow(
    title = "Go full screen when the timer starts",
    subtitle = "Automatically expands when you press Start",
    checked = timerGoFullScreenOnStart,
    onCheckedChange = { prefs.setTimerGoFullScreenOnStart(it) },
    testTag = "settings_timer_fullscreen_on_start_switch",
  )

  SettingsSwitchRow(
    title = "Rounds mode",
    subtitle = "Auto-restart with gap between rounds",
    checked = roundsMode,
    onCheckedChange = { prefs.setRoundsMode(it) },
    testTag = "settings_rounds_mode_switch",
  )

  if (roundsMode) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = "Gap between rounds",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..3).forEach { sec ->
          val selected = roundGapSeconds == sec
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
              )
              .clickable { prefs.setRoundGapSeconds(sec) }
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("settings_gap_chip_$sec"),
          ) {
            Text(
              text = "${sec}s",
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
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
        text = "Turn indicator",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = if (turnIndicatorEnabled) "Group of $turnGroupSize speakers" else "Off",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (turnIndicatorEnabled) {
        IconButton(
          onClick = { prefs.setTurnGroupSize((turnGroupSize - 1).coerceAtLeast(2)) },
          modifier = Modifier.testTag("settings_turn_group_minus"),
        ) {
          Icon(Icons.Default.Remove, contentDescription = "Decrease group size")
        }
        Text(
          text = "$turnGroupSize",
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
          onClick = { prefs.setTurnGroupSize((turnGroupSize + 1).coerceAtMost(10)) },
          modifier = Modifier.testTag("settings_turn_group_plus"),
        ) {
          Icon(Icons.Default.Add, contentDescription = "Increase group size")
        }
      }
      Switch(
        checked = turnIndicatorEnabled,
        onCheckedChange = { prefs.setTurnIndicatorEnabled(it) },
        modifier = Modifier.testTag("settings_turn_indicator_switch"),
      )
    }
  }
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(testTag),
    )
  }
}

private fun resolveAudioDisplayName(context: Context, uri: Uri): String? =
  runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) cursor.getString(idx) else null
      } else null
    }
  }.getOrNull()

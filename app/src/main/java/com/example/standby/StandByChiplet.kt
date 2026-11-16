package com.example.standby

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CoreSingletons
import com.example.core.StandByCalendarWindow
import com.example.core.StandByDiagnosticsProvider
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack
import kotlin.math.roundToInt

/**
 * Public entry point for the standby chiplet.
 * Owns calendar diagnostics, Stand By settings UI, and the full-screen Stand By preview.
 */
object StandByChiplet {

  @Volatile
  private var coordinatorInstance: StandByCalendarCoordinator? = null

  /** Initializes and registers the StandByDiagnosticsProvider singleton in CoreSingletons. */
  fun ensureInitialized(context: Context): StandByDiagnosticsProvider {
    val existing = coordinatorInstance
    if (existing != null) return existing
    return synchronized(this) {
      coordinatorInstance ?: StandByCalendarCoordinator(
        context = context.applicationContext,
        prefs = CoreSingletons.prefs(context),
      ).also {
        coordinatorInstance = it
        CoreSingletons.standByDiagnosticsProvider = it
        CoreSingletons.nextEventSource = it
      }
    }
  }

  internal fun coordinator(context: Context): StandByCalendarCoordinator {
    ensureInitialized(context)
    return coordinatorInstance!!
  }

  /**
   * Single public Settings composable embedded by SettingsScreen so Settings stays thin.
   * Includes Stand By face/brightness options, calendar permissions & calendar picker,
   * Preview Stand By button, and system screensaver shortcut.
   */
  @OptIn(ExperimentalLayoutApi::class)
  @Composable
  fun SettingsSection(
    onOpenPreview: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val prefs = remember { CoreSingletons.prefs(context) }
    val coordinator = remember { coordinator(context) }

    val standByUseSelected by prefs.standByUseSelectedFace.collectAsStateWithLifecycle()
    val standByDim by prefs.standByDimLevel.collectAsStateWithLifecycle()
    val calendarEnabled by prefs.standByCalendarEnabled.collectAsStateWithLifecycle()
    val selectedIds by prefs.selectedCalendarIds.collectAsStateWithLifecycle()
    val maxEvents by prefs.standByMaxEvents.collectAsStateWithLifecycle()
    val calendarWindow by prefs.standByCalendarWindow.collectAsStateWithLifecycle()
    val showTitles by prefs.standByShowEventTitles.collectAsStateWithLifecycle()
    val calSnapshot by coordinator.snapshot.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
      coordinator.refreshSuspend()
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
      if (granted) {
        prefs.setStandByCalendarEnabled(true)
      }
      coordinator.refreshNow()
    }

    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(18.dp),
      modifier = modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(modifier = Modifier.width(10.dp))
          Text("Stand By Screensaver", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Mirror active watch face", style = MaterialTheme.typography.titleMedium)
            Text(
              text = if (standByUseSelected) "Uses active Clock face" else "Uses Big Stand By Digital",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = standByUseSelected,
            onCheckedChange = { prefs.setStandByUseSelectedFace(it) },
            modifier = Modifier.testTag("settings_standby_mirror_switch"),
          )
        }

        Text(
          text = "Night brightness: ${(standByDim * 100).roundToInt()}%",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
          value = standByDim,
          onValueChange = { prefs.setStandByDimLevel(it) },
          valueRange = 0.15f..1.0f,
          modifier = Modifier.testTag("settings_standby_dim_slider"),
        )

        // Calendar integration section
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Stand By Calendar", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }

        Text(
          text = "AuraClock reads your calendar to show upcoming events on the charging screen. Nothing leaves your phone.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!calSnapshot.permissionGranted) {
          Button(
            onClick = { calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("settings_grant_calendar_permission"),
          ) {
            Text("Allow calendar access")
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Show calendar on Stand By", style = MaterialTheme.typography.titleMedium)
              Text(
                text = "Displays upcoming events beside the clock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = calendarEnabled,
              onCheckedChange = {
                prefs.setStandByCalendarEnabled(it)
                coordinator.refreshNow()
              },
              modifier = Modifier.testTag("settings_standby_calendar_switch"),
            )
          }

          if (calendarEnabled) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Show event titles", style = MaterialTheme.typography.titleMedium)
                Text(
                  text = if (showTitles) "Shows time and title" else "Shows time and \"Event\"",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = showTitles,
                onCheckedChange = { prefs.setStandByShowEventTitles(it) },
                modifier = Modifier.testTag("settings_standby_show_titles_switch"),
              )
            }

            Text(
              text = "Events shown",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              (2..6).forEach { count ->
                val selected = maxEvents == count
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable {
                      prefs.setStandByMaxEvents(count)
                      coordinator.refreshNow()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("standby_max_events_$count"),
                ) {
                  Text(
                    text = "$count",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                  )
                }
              }
            }

            Text(
              text = "Time window",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              StandByCalendarWindow.entries.forEach { win ->
                val selected = calendarWindow == win
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                      if (selected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .border(
                      width = 1.dp,
                      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                      shape = RoundedCornerShape(12.dp),
                    )
                    .clickable {
                      prefs.setStandByCalendarWindow(win)
                      coordinator.refreshNow()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("standby_window_${win.id}"),
                ) {
                  Text(
                    text = win.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                  )
                }
              }
            }

            Text(
              text = "Calendars to show",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (calSnapshot.calendars.isEmpty()) {
              Text(
                text = "No calendars found. Open the Business Calendar app, make sure your Google account is added, and check that calendar sync is on in your phone’s account settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_no_calendars_help"),
              )
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                calSnapshot.calendars.forEach { cal ->
                  val checked = cal.id in selectedIds
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(12.dp))
                      .background(MaterialTheme.colorScheme.surfaceVariant)
                      .clickable {
                        val updated = if (checked) selectedIds - cal.id else selectedIds + cal.id
                        prefs.setSelectedCalendarIds(updated, userEdited = true)
                        coordinator.refreshNow()
                      }
                      .padding(horizontal = 12.dp, vertical = 6.dp)
                      .testTag("calendar_row_${cal.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Box(
                      modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(cal.colorArgb)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = cal.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      if (cal.accountName.isNotBlank()) {
                        Text(
                          text = cal.accountName,
                          style = MaterialTheme.typography.bodyMedium,
                          fontSize = 12.sp,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                    Checkbox(
                      checked = checked,
                      onCheckedChange = { isChecked ->
                        val updated = if (isChecked) selectedIds + cal.id else selectedIds - cal.id
                        prefs.setSelectedCalendarIds(updated, userEdited = true)
                        coordinator.refreshNow()
                      },
                    )
                  }
                }
              }
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = onOpenPreview,
            modifier = Modifier
              .weight(1f)
              .testTag("settings_preview_standby"),
          ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Preview Stand By")
          }
          OutlinedButton(
            onClick = { openDreamSettings(context) },
            modifier = Modifier
              .weight(1f)
              .testTag("settings_open_screensaver"),
          ) {
            Text("Screensaver")
          }
        }
      }
    }
  }

  /** Full-screen Stand By preview inside the app; tap anywhere or press Back to exit. */
  @Composable
  fun PreviewScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val prefs = remember { CoreSingletons.prefs(context) }
    val coordinator = remember { coordinator(context) }

    BackHandler(onBack = onExit)

    Box(
      modifier = modifier
        .fillMaxSize()
        .background(OledBlack)
        .clickable(onClick = onExit)
        .testTag("standby_preview_overlay"),
    ) {
      StandByStage(
        coordinator = coordinator,
        prefs = prefs,
        applyDimAlpha = true,
        isDreamService = false,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

private fun openDreamSettings(context: Context) {
  runCatching {
    context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
  }.onFailure {
    runCatching {
      context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
    }
  }
}

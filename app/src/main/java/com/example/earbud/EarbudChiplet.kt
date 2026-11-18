package com.example.earbud

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.core.EarbudController
import com.example.core.EarbudDiagnostics
import com.example.core.FaceEngineDiagnostics
import com.example.core.StandByDiagnostics
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Public entry point for the earbud chiplet.
 * Manages the shared MediaKeyRouter, EarbudListenerService lifecycle, and Diagnostics UI.
 */
object EarbudChiplet {

  private val _diagnostics = MutableStateFlow(EarbudDiagnostics())
  private var routerInstance: MediaKeyRouter? = null
  private val learnController = EarbudLearnController()

  /** Observable 6-second "Learn a press" recording/result state for Settings UI. */
  internal val learnModeState: StateFlow<LearnModeState> = learnController.state

  /** Initializes and registers the EarbudController in CoreSingletons. */
  fun ensureInitialized(context: Context): EarbudController {
    val existing = CoreSingletons.earbudController
    if (existing != null) return existing
    val appContext = context.applicationContext
    val prefs = CoreSingletons.prefs(appContext)
    router(appContext)

    val controller = object : EarbudController {
      override val diagnostics: StateFlow<EarbudDiagnostics> = _diagnostics.asStateFlow()

      override fun setListenerEnabled(enabled: Boolean) {
        prefs.setEarbudEnabled(enabled)
        if (enabled) {
          startService(appContext)
        } else {
          appContext.stopService(Intent(appContext, EarbudListenerService::class.java))
        }
      }

      override fun ensureStarted() {
        if (prefs.earbudEnabled.value) {
          startService(appContext)
        }
      }
    }
    CoreSingletons.earbudController = controller
    return controller
  }

  /** Returns the single shared MediaKeyRouter instance. */
  internal fun router(context: Context): MediaKeyRouter {
    return routerInstance ?: synchronized(this) {
      routerInstance ?: MediaKeyRouter(
        context = context.applicationContext,
        prefs = CoreSingletons.prefs(context),
        onKeyDiagnostics = { keyLabel, timeLabel, actionLabel ->
          _diagnostics.value = _diagnostics.value.copy(
            lastKeyCodeLabel = keyLabel,
            lastKeyTimeLabel = timeLabel,
            lastActionExecuted = actionLabel,
          )
        },
        onPatternDiagnostics = { patternLabel, keyLabel, timeLabel, actionLabel ->
          _diagnostics.value = _diagnostics.value.copy(
            lastKeyCodeLabel = keyLabel,
            lastKeyTimeLabel = timeLabel,
            lastDetectedPattern = patternLabel,
            lastActionExecuted = actionLabel,
          )
        },
        onForwardResult = { success ->
          _diagnostics.value = if (success) {
            _diagnostics.value.copy(forwardedKeyCount = _diagnostics.value.forwardedKeyCount + 1)
          } else {
            _diagnostics.value.copy(forwardFailureCount = _diagnostics.value.forwardFailureCount + 1)
          }
        },
      ).also { routerInstance = it }
    }
  }

  /** Starts the 6-second hardware press recorder in Settings. */
  internal fun startLearnMode(context: Context) {
    learnController.start(context.applicationContext, router(context))
  }

  /** Finishes Learn Mode early with presses captured so far. */
  internal fun finishLearnModeNow() {
    learnController.finishNow()
  }

  /** Cancels any active Learn Mode recording or result dialog. */
  internal fun cancelLearnMode() {
    learnController.cancel()
  }

  /** Persists a learned press pattern mapped to [actionId]. */
  internal fun saveLearnedPattern(context: Context, candidate: LearnedPressCandidate, actionId: String) {
    learnController.confirmAndSave(context, candidate, actionId)
  }

  /** Formats a learned pattern concisely for Settings and Diagnostics display. */
  fun formatPatternLabel(pattern: com.example.core.LearnedPressPattern): String =
    PressPatternDetector.formatPatternLabel(pattern)

  internal fun updateServiceRunning(running: Boolean) {
    _diagnostics.value = _diagnostics.value.copy(serviceRunning = running)
  }

  internal fun updateOtherAppPlaying(playing: Boolean) {
    _diagnostics.value = _diagnostics.value.copy(otherAppPlaying = playing)
  }

  internal fun recordReprime(timeLabel: String) {
    _diagnostics.value = _diagnostics.value.copy(
      reprimeCount = _diagnostics.value.reprimeCount + 1,
      lastReprimeTimeLabel = timeLabel,
    )
  }

  /** Notifies the running service to re-check active playback if the user switched back to AuraClock actions. */
  fun notifyOtherAudioPolicyChanged(context: Context) {
    val appContext = context.applicationContext
    val prefs = CoreSingletons.prefs(appContext)
    if (!prefs.earbudEnabled.value || !prefs.earbudTakeOverDuringAudio.value) return
    runCatching {
      val intent = Intent(appContext, EarbudListenerService::class.java).apply {
        action = EarbudListenerService.ACTION_RECHECK_PLAYBACK
      }
      ContextCompat.startForegroundService(appContext, intent)
    }
  }

  /** Returns (id, label) pairs for all available earbud button actions. */
  fun availableActions(): List<Pair<String, String>> =
    EarbudActionRegistry.actions.map { it.id to it.label }

  /** Resolves a display label for an action ID. */
  fun actionLabel(actionId: String): String =
    EarbudActionRegistry.findById(actionId).label

  private fun startService(context: Context) {
    runCatching {
      val intent = Intent(context, EarbudListenerService::class.java)
      ContextCompat.startForegroundService(context, intent)
    }
  }

  /** Renders the collapsible Earbuds and voice settings section owned by earbud chiplet. */
  @Composable
  fun SettingsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    EarbudSettingsSection(expanded = expanded, onToggle = onToggle, modifier = modifier)
  }

  /** Renders the collapsible Diagnostics and about summary section owned by earbud chiplet. */
  @Composable
  fun DiagnosticsAboutSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    DiagnosticsAboutSettingsSection(
      expanded = expanded,
      onToggle = onToggle,
      onOpenDiagnostics = onOpenDiagnostics,
      modifier = modifier,
    )
  }

  /** Renders the Diagnostics screen showing service state, TTS readiness, last key, and crash log share. */
  @Composable
  fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val controller = remember { ensureInitialized(context) }
    val diag by controller.diagnostics.collectAsStateWithLifecycle()
    val speaker = remember { CoreSingletons.speaker(context) }
    val ttsReady by speaker.isReady.collectAsStateWithLifecycle()
    val ttsStatus by speaker.statusMessage.collectAsStateWithLifecycle()
    val hasCrash = remember { CrashCatcher.hasCrashLog(context) }
    val standByProvider = remember { CoreSingletons.standByDiagnosticsProvider }
    val standByDiag by (standByProvider?.diagnostics
      ?: remember { MutableStateFlow(StandByDiagnostics()) }).collectAsStateWithLifecycle()
    val faceDiagProvider = remember { CoreSingletons.faceDiagnosticsProvider }
    val faceDiag by (faceDiagProvider?.diagnostics
      ?: remember { MutableStateFlow(FaceEngineDiagnostics()) }).collectAsStateWithLifecycle()
    val prefs = remember { CoreSingletons.prefs(context) }
    val sensorSource = remember { CoreSingletons.sensorSource(context) }
    val sensorDiag by sensorSource.diagnostics.collectAsStateWithLifecycle()

    LaunchedEffect(standByProvider, sensorSource) {
      standByProvider?.refreshNow()
      sensorSource.refreshDiagnostics()
    }

    Column(
      modifier = modifier
        .fillMaxSize()
        .background(OledBlack)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("diagnostics_back_button")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Diagnostics",
          style = MaterialTheme.typography.headlineMedium,
        )
      }

      DiagnosticCard(
        title = "Earbud Listener Service",
        value = if (diag.serviceRunning) "Running" else "Stopped",
        isHealthy = diag.serviceRunning,
        testTag = "diag_service_status",
      )

      DiagnosticCard(
        title = "Text-to-Speech Engine",
        value = if (ttsReady) "Ready ($ttsStatus)" else ttsStatus,
        isHealthy = ttsReady,
        testTag = "diag_tts_status",
      )

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Last Media Key Received",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = diag.lastKeyCodeLabel,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("diag_last_key_code"),
          )
          Text(
            text = "Pattern: ${diag.lastDetectedPattern}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_last_pattern"),
          )
          Text(
            text = "Time: ${diag.lastKeyTimeLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("diag_last_key_time"),
          )
          Text(
            text = "Action: ${diag.lastActionExecuted}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diag_last_action"),
          )
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Earbud Audio Routing",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "Another app playing: ${if (diag.otherAppPlaying) "Yes" else "No"}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (diag.otherAppPlaying) Color(0xFFF59E0B) else Color(0xFF10B981),
            modifier = Modifier.testTag("diag_other_app_playing"),
          )
          Text(
            text = "Forwarded to music app: ${diag.forwardedKeyCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_forwarded_count"),
          )
          Text(
            text = "Forward failures: ${diag.forwardFailureCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (diag.forwardFailureCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diag_forward_failures"),
          )
          Text(
            text = "Re-primes: ${diag.reprimeCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_reprime_count"),
          )
          Text(
            text = "Last re-prime: ${diag.lastReprimeTimeLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diag_last_reprime_time"),
          )
        }
      }

      val learnedPatterns by prefs.learnedPatterns.collectAsStateWithLifecycle()
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
          .testTag("diag_learned_patterns_card"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Learned Earbud Patterns",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (learnedPatterns.isEmpty()) {
            Text(
              text = "None learned yet.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("diag_learned_patterns_list"),
            )
          } else {
            Column(
              verticalArrangement = Arrangement.spacedBy(4.dp),
              modifier = Modifier.testTag("diag_learned_patterns_list"),
            ) {
              learnedPatterns.forEach { pattern ->
                Text(
                  text = "• ${formatPatternLabel(pattern)} → ${actionLabel(pattern.actionId)}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = Color.White,
                )
              }
            }
          }
        }
      }

      Button(
        onClick = { speaker.speakCurrentTime() },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(54.dp)
          .testTag("diag_speak_time_button"),
      ) {
        Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
        Spacer(modifier = Modifier.width(10.dp))
        Text("Speak time now", fontWeight = FontWeight.Bold)
      }

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Stand By Calendar",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = if (standByDiag.calendarPermissionGranted) "Permission: Granted" else "Permission: Not granted",
            style = MaterialTheme.typography.titleLarge,
            color = if (standByDiag.calendarPermissionGranted) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("diag_calendar_permission"),
          )
          Text(
            text = "Calendars found: ${standByDiag.calendarsFoundCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_calendar_count"),
          )
          Text(
            text = "Events in window: ${standByDiag.eventsFoundCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_calendar_events"),
          )
          Text(
            text = "Last refresh: ${standByDiag.lastRefreshTimeLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diag_calendar_refresh"),
          )
          Text(
            text = "Stand By last started at: ${standByDiag.standByLastStartedLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_standby_last_started"),
          )
          Text(
            text = "Events shown on last Stand By: ${standByDiag.standByLastEventCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_standby_events_shown"),
          )
          Text(
            text = "Requested window brightness: ${(standByDiag.requestedWindowBrightness * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.testTag("diag_standby_window_brightness"),
          )
          Text(
            text = "Software dim level: ${(standByDiag.softwareDimLevel * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diag_standby_software_dim"),
          )
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
          .testTag("diag_phone_sensors_card"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Phone Sensors (On-Demand)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          val lastDailySteps by prefs.lastDailySteps.collectAsStateWithLifecycle()
          SensorStatusLine("Steps ($lastDailySteps steps since first seen today)", sensorDiag.steps, "diag_sensor_steps")
          SensorStatusLine("Heart Rate", sensorDiag.heartRate, "diag_sensor_heart_rate")
          SensorStatusLine("Ambient Light", sensorDiag.light, "diag_sensor_light")
          SensorStatusLine("Barometer (Pressure)", sensorDiag.pressure, "diag_sensor_pressure")
          SensorStatusLine("Accelerometer", sensorDiag.accelerometer, "diag_sensor_accelerometer")
          Text(
            text = "Active listeners right now: ${sensorDiag.activeListenersCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diag_sensor_active_count"),
          )
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Watch Face Definitions",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "Loaded: ${faceDiag.loadedDefinitions.size}",
            style = MaterialTheme.typography.titleLarge,
            color = if (faceDiag.errors.isEmpty()) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("diag_face_defs_count"),
          )
          if (faceDiag.loadedDefinitions.isNotEmpty()) {
            Text(
              text = faceDiag.loadedDefinitions.joinToString(", ") { "${it.second} (${it.first})" },
              style = MaterialTheme.typography.bodyMedium,
              color = Color.White,
              modifier = Modifier.testTag("diag_face_defs_list"),
            )
          }
          if (faceDiag.errors.isEmpty()) {
            Text(
              text = "Errors: None",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("diag_face_defs_errors"),
            )
          } else {
            faceDiag.errors.forEach { err ->
              Text(
                text = "Skipped ${err.fileName}: ${err.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("diag_wff_compatibility_row"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Compatibility",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val avgScore = if (faceDiag.wffFaces.isEmpty()) {
              "No imported faces"
            } else {
              "${faceDiag.wffFaces.sumOf { it.scorePercent } / faceDiag.wffFaces.size}% avg (${faceDiag.wffFaces.size})"
            }
            Text(
              text = avgScore,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("diag_wff_faces_count"),
            )
          }
          faceDiag.wffFaces.forEach { wff ->
            Text(
              text = "• ${wff.name}: ${wff.scorePercent}% (${wff.drawnCount} drawn, ${wff.partlyDrawnCount} partly drawn, ${wff.skippedCount} skipped)",
              style = MaterialTheme.typography.bodyMedium,
              color = if (wff.skippedCount == 0 && wff.partlyDrawnCount == 0) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("diag_wff_compatibility_${wff.id}"),
            )
            wff.dataSources.forEach { ds ->
              val color = when (ds.origin) {
                com.example.core.WffDataSourceOrigin.REAL_SENSOR -> Color(0xFF10B981)
                com.example.core.WffDataSourceOrigin.AURACLOCK_DATA -> MaterialTheme.colorScheme.primary
                com.example.core.WffDataSourceOrigin.NO_DATA -> Color(0xFFF59E0B)
              }
              Text(
                text = "   • ${ds.source}: ${ds.origin.label} (${ds.detail})",
                style = MaterialTheme.typography.bodySmall,
                color = color,
              )
            }
            wff.partlyDrawnItems.forEach { partly ->
              Text(
                text = "   ~ ${partly.element}: ${partly.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF59E0B),
              )
            }
            wff.skippedItems.forEach { skipped ->
              Text(
                text = "   - ${skipped.element}: ${skipped.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Offline Crash Catcher",
              style = MaterialTheme.typography.titleMedium,
            )
          }
          Text(
            text = if (hasCrash) "A crash log is saved on this device." else "No uncaught crashes recorded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(
            onClick = { context.startActivity(CrashCatcher.createShareIntent(context)) },
            enabled = hasCrash,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("diag_share_crash_button"),
          ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share last crash log")
          }
        }
      }
    }
  }
}

@Composable
private fun DiagnosticCard(
  title: String,
  value: String,
  isHealthy: Boolean,
  testTag: String,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = value,
          style = MaterialTheme.typography.titleLarge,
          color = Color.White,
          modifier = Modifier.testTag(testTag),
        )
      }
      Icon(
        imageVector = if (isHealthy) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
        contentDescription = if (isHealthy) "Healthy" else "Inactive",
        tint = if (isHealthy) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun SensorStatusLine(
  label: String,
  status: com.example.core.SensorAvailability,
  tag: String,
) {
  val statusColor = when (status) {
    com.example.core.SensorAvailability.AVAILABLE -> Color(0xFF10B981)
    com.example.core.SensorAvailability.PERMISSION_NEEDED -> Color(0xFFF59E0B)
    com.example.core.SensorAvailability.NO_HARDWARE -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(
    modifier = Modifier.fillMaxWidth().testTag(tag),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    Text(status.label, style = MaterialTheme.typography.bodyMedium, color = statusColor)
  }
}

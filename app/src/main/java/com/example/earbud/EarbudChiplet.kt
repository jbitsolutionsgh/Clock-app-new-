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
import com.example.core.StandByDiagnostics
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public entry point for the earbud chiplet.
 * Manages the shared MediaKeyRouter, EarbudListenerService lifecycle, and Diagnostics UI.
 */
object EarbudChiplet {

  private val _diagnostics = MutableStateFlow(EarbudDiagnostics())
  private var routerInstance: MediaKeyRouter? = null

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
      ).also { routerInstance = it }
    }
  }

  internal fun updateServiceRunning(running: Boolean) {
    _diagnostics.value = _diagnostics.value.copy(serviceRunning = running)
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

    LaunchedEffect(standByProvider) {
      standByProvider?.refreshNow()
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
            text = "Time: ${diag.lastKeyTimeLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("diag_last_key_time"),
          )
          Text(
            text = "Action: ${diag.lastActionExecuted}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
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

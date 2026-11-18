package com.example.timer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraPrefs
import com.example.core.CoreSingletons
import com.example.core.TimerController
import com.example.core.TimerPhase
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack
import kotlinx.coroutines.delay

/**
 * Public entry point for the timer chiplet.
 * Connects to the single shared TimerController and keeps the screen awake while open.
 */
object TimerChiplet {
  /** Ensures the single shared TimerEngine is registered in CoreSingletons. */
  fun ensureInitialized(context: Context): TimerController {
    val existing = CoreSingletons.timerController
    if (existing != null) return existing
    val created = TimerEngine(context.applicationContext, CoreSingletons.prefs(context))
    CoreSingletons.timerController = created
    return created
  }

  /** Plays a test sound sample from Settings using the timer's audio player. */
  fun playTestSound(context: Context) {
    val engine = ensureInitialized(context) as? TimerEngine ?: return
    engine.playTestBeep()
  }

  /** Renders the collapsible Timer and rounds settings section owned by timer chiplet. */
  @Composable
  fun SettingsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    TimerSettingsSection(expanded = expanded, onToggle = onToggle, modifier = modifier)
  }

  /** Renders the classroom countdown timer UI, supporting both normal and immersive full-screen modes. */
  @Composable
  fun Screen(
    isFullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val controller = remember { ensureInitialized(context) }
    val prefs = remember { CoreSingletons.prefs(context) }
    TimerScreenContent(
      controller = controller,
      prefs = prefs,
      isFullScreen = isFullScreen,
      onFullScreenChange = onFullScreenChange,
      modifier = modifier,
    )
  }
}

@Composable
private fun TimerScreenContent(
  controller: TimerController,
  prefs: AuraPrefs,
  isFullScreen: Boolean,
  onFullScreenChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val view = LocalView.current
  val context = LocalContext.current

  // Keep the screen awake while the teacher has the timer tab open (normal and full-screen).
  DisposableEffect(view) {
    val previous = view.keepScreenOn
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = previous }
  }

  val notifPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = { },
  )

  val timerDurationSeconds by prefs.timerDurationSeconds.collectAsStateWithLifecycle()
  val quickPresets by prefs.quickPresets.collectAsStateWithLifecycle()
  val roundsMode by prefs.roundsMode.collectAsStateWithLifecycle()
  val roundGapSeconds by prefs.roundGapSeconds.collectAsStateWithLifecycle()
  val turnIndicatorEnabled by prefs.turnIndicatorEnabled.collectAsStateWithLifecycle()
  val turnGroupSize by prefs.turnGroupSize.collectAsStateWithLifecycle()
  val goFullScreenOnStart by prefs.timerGoFullScreenOnStart.collectAsStateWithLifecycle()

  var showKeypad by remember { mutableStateOf(false) }
  var editingPresetIndex by remember { mutableStateOf<Int?>(null) }

  val requestNotifAndStart: () -> Unit = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
    val currentPhase = controller.state.value.phase
    val startingFromStopped = currentPhase != TimerPhase.RUNNING && currentPhase != TimerPhase.GAP_WAIT
    controller.startOrPause()
    if (goFullScreenOnStart && startingFromStopped) {
      onFullScreenChange(true)
    }
  }

  if (isFullScreen) {
    FullScreenTimerView(
      controller = controller,
      roundsMode = roundsMode,
      turnIndicatorEnabled = turnIndicatorEnabled,
      turnGroupSize = turnGroupSize,
      onToggleRounds = { prefs.setRoundsMode(it) },
      onStartPauseClick = requestNotifAndStart,
      onExitFullScreen = { onFullScreenChange(false) },
      modifier = modifier,
    )
    return
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(OledBlack)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    val isLandscape = maxWidth > maxHeight
    val ringDiameter = if (isLandscape) {
      (maxHeight * 0.72f).coerceIn(190.dp, 360.dp)
    } else {
      (minOf(maxWidth, maxHeight * 0.44f)).coerceIn(220.dp, 340.dp)
    }

    if (isLandscape) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Box(
          modifier = Modifier
            .weight(1.1f)
            .fillMaxHeight(),
          contentAlignment = Alignment.Center,
        ) {
          TimerDialSection(
            controller = controller,
            roundsMode = roundsMode,
            turnIndicatorEnabled = turnIndicatorEnabled,
            turnGroupSize = turnGroupSize,
            ringDiameter = ringDiameter,
            onDigitsClick = { showKeypad = true },
          )
        }
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
          QuickPresetsRow(
            presets = quickPresets,
            activeDurationSeconds = timerDurationSeconds,
            onPresetClick = { controller.setDurationSeconds(it) },
            onPresetLongClick = { index -> editingPresetIndex = index },
          )
          RoundsAndTurnPanel(
            roundsMode = roundsMode,
            roundGapSeconds = roundGapSeconds,
            turnIndicatorEnabled = turnIndicatorEnabled,
            turnGroupSize = turnGroupSize,
            onToggleRounds = { prefs.setRoundsMode(it) },
            onSelectGap = { prefs.setRoundGapSeconds(it) },
            onToggleTurn = { prefs.setTurnIndicatorEnabled(it) },
            onChangeGroupSize = { prefs.setTurnGroupSize(it) },
          )
          TimerControlsRow(
            controller = controller,
            onStartPauseClick = requestNotifAndStart,
          )
        }
      }
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Spacer(modifier = Modifier.height(8.dp))
        TimerDialSection(
          controller = controller,
          roundsMode = roundsMode,
          turnIndicatorEnabled = turnIndicatorEnabled,
          turnGroupSize = turnGroupSize,
          ringDiameter = ringDiameter,
          onDigitsClick = { showKeypad = true },
        )
        Spacer(modifier = Modifier.height(12.dp))
        QuickPresetsRow(
          presets = quickPresets,
          activeDurationSeconds = timerDurationSeconds,
          onPresetClick = { controller.setDurationSeconds(it) },
          onPresetLongClick = { index -> editingPresetIndex = index },
        )
        Spacer(modifier = Modifier.height(10.dp))
        RoundsAndTurnPanel(
          roundsMode = roundsMode,
          roundGapSeconds = roundGapSeconds,
          turnIndicatorEnabled = turnIndicatorEnabled,
          turnGroupSize = turnGroupSize,
          onToggleRounds = { prefs.setRoundsMode(it) },
          onSelectGap = { prefs.setRoundGapSeconds(it) },
          onToggleTurn = { prefs.setTurnIndicatorEnabled(it) },
          onChangeGroupSize = { prefs.setTurnGroupSize(it) },
        )
        Spacer(modifier = Modifier.height(12.dp))
        TimerControlsRow(
          controller = controller,
          onStartPauseClick = requestNotifAndStart,
        )
      }
    }

    // Expand button to immediately enter full-screen classroom mode.
    IconButton(
      onClick = { onFullScreenChange(true) },
      modifier = Modifier
        .align(Alignment.TopEnd)
        .size(48.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
        .testTag("timer_fullscreen_button"),
    ) {
      Icon(
        imageVector = Icons.Default.Fullscreen,
        contentDescription = "Enter full-screen timer",
        tint = Color.White,
      )
    }
  }

  if (showKeypad) {
    TimerKeypadDialog(
      title = "Set Timer Duration",
      initialSeconds = timerDurationSeconds,
      onDismiss = { showKeypad = false },
      onConfirmSeconds = { seconds ->
        showKeypad = false
        controller.setDurationSeconds(seconds)
      },
    )
  }

  editingPresetIndex?.let { index ->
    val currentVal = quickPresets.getOrElse(index) { 30 }
    TimerKeypadDialog(
      title = "Edit Preset #${index + 1}",
      initialSeconds = currentVal,
      onDismiss = { editingPresetIndex = null },
      onConfirmSeconds = { seconds ->
        editingPresetIndex = null
        prefs.updateQuickPreset(index, seconds)
        controller.setDurationSeconds(seconds)
      },
    )
  }
}

/**
 * Full-screen classroom timer view filling the screen in both portrait and landscape.
 * Auto-hides controls after 3 seconds and reveals them on tap while keeping round/speaker badges visible.
 */
@Composable
private fun FullScreenTimerView(
  controller: TimerController,
  roundsMode: Boolean,
  turnIndicatorEnabled: Boolean,
  turnGroupSize: Int,
  onToggleRounds: (Boolean) -> Unit,
  onStartPauseClick: () -> Unit,
  onExitFullScreen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onExitFullScreen)

  val snap by controller.state.collectAsStateWithLifecycle()
  val isRunning = snap.phase == TimerPhase.RUNNING || snap.phase == TimerPhase.GAP_WAIT

  var controlsVisible by remember { mutableStateOf(true) }
  var interactionNonce by remember { mutableIntStateOf(0) }

  // Automatically hide controls 3 seconds after entering full-screen or last user interaction.
  LaunchedEffect(controlsVisible, interactionNonce) {
    if (controlsVisible) {
      delay(3_000L)
      controlsVisible = false
    }
  }

  val touchInteraction: () -> Unit = {
    if (snap.isRinging) {
      controller.silenceBeep()
    }
    controlsVisible = true
    interactionNonce++
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(OledBlack)
      .safeDrawingPadding()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = touchInteraction,
      )
      .testTag("timer_fullscreen_root"),
    contentAlignment = Alignment.Center,
  ) {
    val ringDiameter = (minOf(maxWidth, maxHeight) * 0.88f).coerceAtLeast(240.dp)
    val accentColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val alertColor = MaterialTheme.colorScheme.error

    val progress = if (snap.totalDurationMs > 0L) {
      (snap.remainingMs.toFloat() / snap.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
      0f
    }

    val totalSec = when (snap.phase) {
      TimerPhase.IDLE -> snap.totalDurationMs / 1000L
      TimerPhase.GAP_WAIT -> (snap.gapRemainingMs + 999L) / 1000L
      else -> ((snap.remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    }
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val displayText = if (snap.phase == TimerPhase.GAP_WAIT) {
      "GAP ${totalSec}s"
    } else {
      "%02d:%02d".format(mins, secs)
    }

    val digitFontSize = (ringDiameter.value * 0.27f).coerceIn(58f, 200f).sp
    val ringColor = when {
      snap.isRinging || snap.phase == TimerPhase.FINISHED -> alertColor
      snap.phase == TimerPhase.GAP_WAIT -> accentColor.copy(alpha = 0.55f)
      else -> accentColor
    }

    // Center progress ring and giant classroom countdown digits.
    Box(
      modifier = Modifier.size(ringDiameter),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val strokePx = 18.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)

        drawArc(
          color = trackColor,
          startAngle = -90f,
          sweepAngle = 360f,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
          color = ringColor,
          startAngle = -90f,
          sweepAngle = 360f * progress,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = displayText,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = if (snap.phase == TimerPhase.GAP_WAIT) (digitFontSize.value * 0.65f).sp else digitFontSize,
          color = if (snap.isRinging) alertColor else Color.White,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.testTag("timer_fullscreen_digits"),
        )
        if (snap.isRinging || snap.phase == TimerPhase.PAUSED || snap.phase == TimerPhase.GAP_WAIT) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = when {
              snap.isRinging -> "Tap anywhere to silence"
              snap.phase == TimerPhase.GAP_WAIT -> "Next round starting"
              else -> "Paused"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (snap.isRinging) alertColor else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // Always keep compact Round and Speaker indicators visible when enabled.
    if (roundsMode || turnIndicatorEnabled) {
      Row(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (roundsMode) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
            shape = RoundedCornerShape(50),
          ) {
            Text(
              text = "Round ${snap.roundNumber}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag("timer_fullscreen_round_badge"),
            )
          }
        }
        if (turnIndicatorEnabled) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
            shape = RoundedCornerShape(50),
          ) {
            Text(
              text = "Speaker ${snap.currentSpeaker} of $turnGroupSize",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag("timer_fullscreen_speaker_badge"),
            )
          }
        }
      }
    }

    // Exit full-screen button (auto-hides after 3 seconds).
    AnimatedVisibility(
      visible = controlsVisible,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(12.dp),
    ) {
      IconButton(
        onClick = onExitFullScreen,
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
          .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
          .testTag("timer_exit_fullscreen_button"),
      ) {
        Icon(
          imageVector = Icons.Default.FullscreenExit,
          contentDescription = "Exit full-screen timer",
          tint = Color.White,
        )
      }
    }

    // Floating control bar (Start/Pause, Reset, Rounds switch) auto-hides after 3 seconds.
    AnimatedVisibility(
      visible = controlsVisible,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
      Surface(
        color = Color(0xE611161F),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
          .testTag("timer_fullscreen_controls"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            onClick = {
              interactionNonce++
              onStartPauseClick()
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .height(48.dp)
              .testTag("timer_fullscreen_start_pause"),
          ) {
            Icon(
              imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = when {
                isRunning -> "Pause"
                snap.phase == TimerPhase.PAUSED -> "Resume"
                else -> "Start"
              },
              fontWeight = FontWeight.Bold,
            )
          }

          OutlinedButton(
            onClick = {
              interactionNonce++
              controller.reset()
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .height(48.dp)
              .testTag("timer_fullscreen_reset"),
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reset", fontWeight = FontWeight.Bold)
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = "Rounds",
              style = MaterialTheme.typography.bodyMedium,
              color = Color.White,
            )
            Switch(
              checked = roundsMode,
              onCheckedChange = { enabled ->
                interactionNonce++
                onToggleRounds(enabled)
              },
              modifier = Modifier.testTag("timer_fullscreen_rounds_switch"),
            )
          }
        }
      }
    }
  }
}

/**
 * Isolated dial composable so tick updates recompose only the ring and digits,
 * never the surrounding presets, switches, or controls.
 */
@Composable
private fun TimerDialSection(
  controller: TimerController,
  roundsMode: Boolean,
  turnIndicatorEnabled: Boolean,
  turnGroupSize: Int,
  ringDiameter: Dp,
  onDigitsClick: () -> Unit,
) {
  val snap by controller.state.collectAsStateWithLifecycle()
  val accentColor = MaterialTheme.colorScheme.primary
  val trackColor = MaterialTheme.colorScheme.surfaceVariant
  val alertColor = MaterialTheme.colorScheme.error

  val progress = if (snap.totalDurationMs > 0L) {
    (snap.remainingMs.toFloat() / snap.totalDurationMs.toFloat()).coerceIn(0f, 1f)
  } else {
    0f
  }

  val totalSec = when (snap.phase) {
    TimerPhase.IDLE -> snap.totalDurationMs / 1000L
    TimerPhase.GAP_WAIT -> (snap.gapRemainingMs + 999L) / 1000L
    else -> ((snap.remainingMs + 999L) / 1000L).coerceAtLeast(0L)
  }
  val mins = totalSec / 60L
  val secs = totalSec % 60L
  val displayText = if (snap.phase == TimerPhase.GAP_WAIT) {
    "GAP ${totalSec}s"
  } else {
    "%02d:%02d".format(mins, secs)
  }

  val digitFontSize = (ringDiameter.value * 0.25f).coerceIn(44f, 84f).sp
  val ringColor = when {
    snap.isRinging || snap.phase == TimerPhase.FINISHED -> alertColor
    snap.phase == TimerPhase.GAP_WAIT -> accentColor.copy(alpha = 0.55f)
    else -> accentColor
  }

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    if (roundsMode || turnIndicatorEnabled) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp),
      ) {
        if (roundsMode) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(50),
          ) {
            Text(
              text = "Round ${snap.roundNumber}",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("timer_round_badge"),
            )
          }
        }
        if (turnIndicatorEnabled) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
          ) {
            Text(
              text = "Speaker ${snap.currentSpeaker} of $turnGroupSize",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("timer_speaker_badge"),
            )
          }
        }
      }
    }

    Box(
      modifier = Modifier
        .size(ringDiameter)
        .clip(CircleShape)
        .clickable {
          if (snap.isRinging) {
            controller.silenceBeep()
          } else {
            onDigitsClick()
          }
        }
        .testTag("timer_digits_target"),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val strokePx = 14.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)

        drawArc(
          color = trackColor,
          startAngle = -90f,
          sweepAngle = 360f,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
          color = ringColor,
          startAngle = -90f,
          sweepAngle = 360f * progress,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = displayText,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = if (snap.phase == TimerPhase.GAP_WAIT) (digitFontSize.value * 0.65f).sp else digitFontSize,
          color = if (snap.isRinging) alertColor else Color.White,
          modifier = Modifier.testTag("timer_digits_text"),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = when {
            snap.isRinging -> "Tap to silence"
            snap.phase == TimerPhase.IDLE -> "Tap digits to edit"
            snap.phase == TimerPhase.PAUSED -> "Paused • Tap to edit"
            snap.phase == TimerPhase.GAP_WAIT -> "Next round starting"
            else -> "Running"
          },
          style = MaterialTheme.typography.bodyMedium,
          color = if (snap.isRinging) alertColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickPresetsRow(
  presets: List<Int>,
  activeDurationSeconds: Int,
  onPresetClick: (Int) -> Unit,
  onPresetLongClick: (Int) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      presets.forEachIndexed { index, seconds ->
        val selected = seconds == activeDurationSeconds
        val label = if (seconds >= 60 && seconds % 60 == 0) {
          "${seconds / 60}m"
        } else if (seconds > 60) {
          "${seconds / 60}:${"%02d".format(seconds % 60)}"
        } else {
          "${seconds}s"
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
              if (selected) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
              width = 1.dp,
              color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
              shape = RoundedCornerShape(14.dp),
            )
            .combinedClickable(
              onClick = { onPresetClick(seconds) },
              onLongClick = { onPresetLongClick(index) },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("preset_chip_$index"),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "Long-press a preset to customize",
      style = MaterialTheme.typography.bodyMedium,
      fontSize = 11.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun RoundsAndTurnPanel(
  roundsMode: Boolean,
  roundGapSeconds: Int,
  turnIndicatorEnabled: Boolean,
  turnGroupSize: Int,
  onToggleRounds: (Boolean) -> Unit,
  onSelectGap: (Int) -> Unit,
  onToggleTurn: (Boolean) -> Unit,
  onChangeGroupSize: (Int) -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Repeat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = "Rounds mode",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Auto-restart with gap",
              style = MaterialTheme.typography.bodyMedium,
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Switch(
          checked = roundsMode,
          onCheckedChange = onToggleRounds,
          modifier = Modifier.testTag("rounds_mode_switch"),
        )
      }

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
                  .clickable { onSelectGap(sec) }
                  .padding(horizontal = 11.dp, vertical = 7.dp)
                  .testTag("gap_chip_$sec"),
              ) {
                Text(
                  text = "${sec}s",
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
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
        Column {
          Text(
            text = "Turn indicator",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = if (turnIndicatorEnabled) "Group of $turnGroupSize speakers" else "Off",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (turnIndicatorEnabled) {
            IconButton(
              onClick = { onChangeGroupSize((turnGroupSize - 1).coerceAtLeast(2)) },
              modifier = Modifier.testTag("turn_group_minus"),
            ) {
              Icon(Icons.Default.Remove, contentDescription = "Decrease group size")
            }
            Text(
              text = "$turnGroupSize",
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 16.sp,
            )
            IconButton(
              onClick = { onChangeGroupSize((turnGroupSize + 1).coerceAtMost(10)) },
              modifier = Modifier.testTag("turn_group_plus"),
            ) {
              Icon(Icons.Default.Add, contentDescription = "Increase group size")
            }
          }
          Switch(
            checked = turnIndicatorEnabled,
            onCheckedChange = onToggleTurn,
            modifier = Modifier.testTag("turn_indicator_switch"),
          )
        }
      }
    }
  }
}

@Composable
private fun TimerControlsRow(
  controller: TimerController,
  onStartPauseClick: () -> Unit,
) {
  val snap by controller.state.collectAsStateWithLifecycle()
  val isRunning = snap.phase == TimerPhase.RUNNING || snap.phase == TimerPhase.GAP_WAIT

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (snap.isRinging) {
      Button(
        onClick = { controller.silenceBeep() },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .testTag("timer_silence_button"),
      ) {
        Icon(Icons.Default.NotificationsOff, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Silence Beep", fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = { controller.reset() },
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .weight(1f)
          .height(60.dp)
          .testTag("timer_reset_button"),
      ) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Reset", fontSize = 17.sp, fontWeight = FontWeight.Bold)
      }

      Button(
        onClick = onStartPauseClick,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .weight(1.4f)
          .height(60.dp)
          .testTag("timer_start_pause_button"),
      ) {
        Icon(
          imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(26.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = when {
            isRunning -> "Pause"
            snap.phase == TimerPhase.PAUSED -> "Resume"
            else -> "Start"
          },
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

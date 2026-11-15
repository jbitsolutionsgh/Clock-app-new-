package com.example.stopwatch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CoreSingletons
import com.example.core.StopwatchController
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack

/**
 * Public entry point for the stopwatch chiplet.
 */
object StopwatchChiplet {
  /** Ensures the single shared StopwatchEngine is registered in CoreSingletons. */
  fun ensureInitialized(): StopwatchController {
    val existing = CoreSingletons.stopwatchController
    if (existing != null) return existing
    val created = StopwatchEngine()
    CoreSingletons.stopwatchController = created
    return created
  }

  /** Renders the large, classroom-readable stopwatch screen with laps. */
  @Composable
  fun Screen(modifier: Modifier = Modifier) {
    val controller = remember { ensureInitialized() }
    StopwatchScreenContent(controller = controller, modifier = modifier)
  }
}

@Composable
private fun StopwatchScreenContent(
  controller: StopwatchController,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(OledBlack)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    val isLandscape = maxWidth > maxHeight

    if (isLandscape) {
      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(
          modifier = Modifier
            .weight(1.1f)
            .fillMaxHeight(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          StopwatchDigitsDisplay(controller = controller, compact = true)
          Spacer(modifier = Modifier.height(24.dp))
          StopwatchControlsRow(controller = controller)
        }
        Box(
          modifier = Modifier
            .weight(0.9f)
            .fillMaxHeight(),
        ) {
          StopwatchLapsList(controller = controller)
        }
      }
    } else {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Spacer(modifier = Modifier.height(20.dp))
        StopwatchDigitsDisplay(controller = controller, compact = false)
        Spacer(modifier = Modifier.height(20.dp))
        StopwatchControlsRow(controller = controller)
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          StopwatchLapsList(controller = controller)
        }
      }
    }
  }
}

/** Isolated digits composable so ticking recomposes only the readout. */
@Composable
private fun StopwatchDigitsDisplay(
  controller: StopwatchController,
  compact: Boolean,
) {
  val snap by controller.state.collectAsStateWithLifecycle()
  val formatted = formatStopwatchMs(snap.elapsedMs)

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(28.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp)),
  ) {
    Column(
      modifier = Modifier.padding(vertical = if (compact) 24.dp else 36.dp, horizontal = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = formatted,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = if (compact) 54.sp else 62.sp,
        color = if (snap.isRunning) MaterialTheme.colorScheme.primary else Color.White,
        modifier = Modifier.testTag("stopwatch_digits_text"),
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = when {
          snap.isRunning -> "Running"
          snap.elapsedMs > 0L -> "Paused"
          else -> "Ready"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun StopwatchControlsRow(controller: StopwatchController) {
  val snap by controller.state.collectAsStateWithLifecycle()

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    OutlinedButton(
      onClick = {
        if (snap.isRunning) controller.lap() else controller.reset()
      },
      enabled = snap.isRunning || snap.elapsedMs > 0L,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier
        .weight(1f)
        .height(60.dp)
        .testTag("stopwatch_secondary_button"),
    ) {
      Icon(
        imageVector = if (snap.isRunning) Icons.Default.Flag else Icons.Default.Refresh,
        contentDescription = null,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = if (snap.isRunning) "Lap" else "Reset",
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    Button(
      onClick = { controller.startOrPause() },
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier
        .weight(1.4f)
        .height(60.dp)
        .testTag("stopwatch_start_pause_button"),
    ) {
      Icon(
        imageVector = if (snap.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = null,
        modifier = Modifier.size(26.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = when {
          snap.isRunning -> "Pause"
          snap.elapsedMs > 0L -> "Resume"
          else -> "Start"
        },
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun StopwatchLapsList(controller: StopwatchController) {
  val snap by controller.state.collectAsStateWithLifecycle()
  val laps = snap.lapsMs

  if (laps.isEmpty()) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "Tap Lap while running to record splits",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("stopwatch_laps_list"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    itemsIndexed(laps) { idx, cumulativeMs ->
      val lapNumber = laps.size - idx
      val prevCumulative = laps.getOrElse(idx + 1) { 0L }
      val splitMs = (cumulativeMs - prevCumulative).coerceAtLeast(0L)

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Lap $lapNumber",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "+${formatStopwatchMs(splitMs)}",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = formatStopwatchMs(cumulativeMs),
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

private fun formatStopwatchMs(ms: Long): String {
  val totalCentis = (ms / 10L).coerceAtLeast(0L)
  val centis = totalCentis % 100L
  val totalSecs = totalCentis / 100L
  val secs = totalSecs % 60L
  val mins = (totalSecs / 60L) % 60L
  val hours = totalSecs / 3600L
  return if (hours > 0L) {
    "%d:%02d:%02d.%02d".format(hours, mins, secs, centis)
  } else {
    "%02d:%02d.%02d".format(mins, secs, centis)
  }
}

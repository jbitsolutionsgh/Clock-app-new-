package com.example.faces

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.core.AuraPrefs
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.FaceNavigator
import com.example.ui.theme.OledBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Public entry point for the faces chiplet.
 * Implements FaceNavigator in core and renders both the main Clock tab and Stand By faces.
 */
object FacesChiplet {

  /** Registers the FaceNavigator singleton in CoreSingletons. */
  fun ensureInitialized(context: Context): FaceNavigator {
    val existing = CoreSingletons.faceNavigator
    if (existing != null) return existing
    val prefs = CoreSingletons.prefs(context)
    val navigator = object : FaceNavigator {
      override fun nextFace() {
        val all = WatchFaceRegistry.faces
        if (all.isEmpty()) return
        val currentIdx = all.indexOfFirst { it.id == prefs.selectedFaceId.value }
        val next = all[(currentIdx + 1) % all.size]
        prefs.setSelectedFaceId(next.id)
      }

      override fun getFaceOptions(): List<Pair<String, String>> =
        allFacePairs()
    }
    CoreSingletons.faceNavigator = navigator
    CoreSingletons.standByClockRenderer = com.example.core.StandByClockRenderer { useSelectedFace, modifier ->
      StandByClockView(
        useSelectedFace = useSelectedFace,
        prefs = prefs,
        modifier = modifier,
      )
    }
    return navigator
  }

  private fun allFacePairs(): List<Pair<String, String>> =
    WatchFaceRegistry.faces.map { it.id to it.displayName }

  /** Renders the interactive Clock tab with smooth sweep when visible and face picker chips. */
  @Composable
  fun Screen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { CoreSingletons.prefs(context) }
    val selectedId by prefs.selectedFaceId.collectAsStateWithLifecycle()
    val accent by prefs.themeColor.collectAsStateWithLifecycle()
    val activeFace = remember(selectedId) { WatchFaceRegistry.findById(selectedId) }

    BoxWithConstraints(
      modifier = modifier
        .fillMaxSize()
        .background(OledBlack)
        .padding(12.dp),
    ) {
      val isLandscape = maxWidth > maxHeight

      if (isLandscape) {
        Row(
          modifier = Modifier.fillMaxSize(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          ClockFaceStage(
            face = activeFace,
            accent = accent,
            smoothSweep = true,
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight(),
          )
          LazyColumn(
            modifier = Modifier.padding(end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(WatchFaceRegistry.faces, key = { it.id }) { face ->
              FaceChip(
                face = face,
                selected = face.id == activeFace.id,
                onClick = { prefs.setSelectedFaceId(face.id) },
              )
            }
          }
        }
      } else {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          ClockFaceStage(
            face = activeFace,
            accent = accent,
            smoothSweep = true,
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
          )
          LazyRow(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 8.dp, bottom = 4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(WatchFaceRegistry.faces, key = { it.id }) { face ->
              FaceChip(
                face = face,
                selected = face.id == activeFace.id,
                onClick = { prefs.setSelectedFaceId(face.id) },
              )
            }
          }
        }
      }
    }
  }

  /** Renders a watch face for the Stand By screensaver (ticks once per real second to save battery). */
  @Composable
  fun StandByClockView(
    useSelectedFace: Boolean,
    prefs: AuraPrefs,
    modifier: Modifier = Modifier,
  ) {
    val selectedId by prefs.selectedFaceId.collectAsStateWithLifecycle()
    val accent by prefs.themeColor.collectAsStateWithLifecycle()
    val face = remember(useSelectedFace, selectedId) {
      if (useSelectedFace) WatchFaceRegistry.findById(selectedId)
      else WatchFaceRegistry.findById("standby_digital")
    }
    ClockFaceStage(
      face = face,
      accent = accent,
      smoothSweep = false,
      modifier = modifier,
    )
  }
}

/**
 * Isolated stage that drives the clock tick loop only while lifecycle-RESUMED.
 * Recomputes only the clock face itself without recomposing surrounding selector UI.
 */
@Composable
private fun ClockFaceStage(
  face: WatchFace,
  accent: AuraThemeColor,
  smoothSweep: Boolean,
  modifier: Modifier = Modifier,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var timeSnapshot by remember { mutableStateOf(ClockTimeSnapshot.now()) }

  LaunchedEffect(lifecycleOwner, smoothSweep) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (smoothSweep) {
        while (isActive) {
          withFrameNanos {
            timeSnapshot = ClockTimeSnapshot.now()
          }
        }
      } else {
        while (isActive) {
          timeSnapshot = ClockTimeSnapshot.now()
          val nowMs = System.currentTimeMillis()
          val nextSecondDelay = (1000L - (nowMs % 1000L)).coerceAtLeast(16L)
          delay(nextSecondDelay)
        }
      }
    }
  }

  Box(
    modifier = modifier.testTag("clock_face_${face.id}"),
    contentAlignment = Alignment.Center,
  ) {
    face.Render(
      timeProvider = { timeSnapshot },
      accent = accent,
      smoothSeconds = smoothSweep,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

@Composable
private fun FaceChip(
  face: WatchFace,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
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
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .testTag("face_chip_${face.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    face.Thumbnail(modifier = Modifier.size(44.dp))
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = face.displayName,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
  }
}

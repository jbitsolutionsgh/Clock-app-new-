package com.example.faces.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.FaceData
import com.example.core.FaceRenderMode
import com.example.core.FaceTimeData
import com.example.core.FaceUpdatePolicy
import com.example.faces.ClockTimeSnapshot
import com.example.faces.WatchFace
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * [WatchFace] adapter backed by a data-driven [FaceDefinition].
 * Subscribes only to the face's declared [FaceDefinition.dataNeeds], respects [FaceRenderMode],
 * drives updates strictly at the derived [FaceUpdatePolicy], and halts all work when not RESUMED.
 */
internal class DefinitionFace(
  val definition: FaceDefinition,
) : WatchFace {

  override val id: String get() = definition.id
  override val displayName: String get() = definition.name
  override val usesOwnDataLoop: Boolean get() = true

  @Composable
  override fun Thumbnail(modifier: Modifier) {
    FaceEngine.Thumbnail(definition = definition, modifier = modifier)
  }

  @Composable
  override fun Render(
    timeProvider: () -> ClockTimeSnapshot,
    accent: AuraThemeColor,
    smoothSeconds: Boolean,
    modifier: Modifier,
    renderMode: FaceRenderMode,
  ) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dataSource = remember { CoreSingletons.faceDataSource(context) }

    val basePolicy = remember(definition, renderMode) { definition.updatePolicy(renderMode) }
    val effectivePolicy = remember(basePolicy, smoothSeconds, renderMode) {
      if (basePolicy == FaceUpdatePolicy.SMOOTH_FRAME && (!smoothSeconds || renderMode == FaceRenderMode.AMBIENT)) {
        FaceUpdatePolicy.EVERY_SECOND
      } else {
        basePolicy
      }
    }

    var faceData by remember(definition.id) {
      mutableStateOf(FaceData(time = dataSource.currentTime(context)))
    }

    LaunchedEffect(lifecycleOwner, definition.id, definition.dataNeeds, effectivePolicy) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        launch {
          dataSource.observe(definition.dataNeeds, effectivePolicy).collect { updated ->
            faceData = updated
          }
        }
        if (effectivePolicy == FaceUpdatePolicy.SMOOTH_FRAME) {
          val cal = Calendar.getInstance()
          while (isActive) {
            withFrameNanos {
              cal.timeInMillis = System.currentTimeMillis()
              val prevTime = faceData.time
              faceData = faceData.copy(
                time = fastFrameTimeSnapshot(cal = cal, template = prevTime),
              )
            }
          }
        }
      }
    }

    FaceEngine.Render(
      definition = definition,
      data = faceData,
      mode = renderMode,
      modifier = modifier,
    )
  }

  private fun fastFrameTimeSnapshot(cal: Calendar, template: FaceTimeData): FaceTimeData {
    val h24 = cal.get(Calendar.HOUR_OF_DAY)
    val h12Raw = cal.get(Calendar.HOUR)
    val h12 = if (h12Raw == 0) 12 else h12Raw
    return template.copy(
      hour12 = h12,
      hour24 = h24,
      minute = cal.get(Calendar.MINUTE),
      second = cal.get(Calendar.SECOND),
      millis = cal.get(Calendar.MILLISECOND),
      amPm = if (h24 < 12) "AM" else "PM",
      dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
    )
  }
}

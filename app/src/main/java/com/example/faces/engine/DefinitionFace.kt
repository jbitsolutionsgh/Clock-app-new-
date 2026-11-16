package com.example.faces.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
 * Reuses a single [FaceData] / [FaceTimeData] snapshot buffer and holds a primitive frame counter
 * read inside [FaceEngine.Render]'s Canvas draw block so smooth sweeps redraw without recomposing.
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

    // Reused snapshot objects updated in place; frameVersion is read only inside Canvas draw scope.
    val liveData = remember(definition.id) {
      FaceData(time = dataSource.currentTime(context))
    }
    val frameVersion = remember(definition.id) { mutableLongStateOf(0L) }

    LaunchedEffect(lifecycleOwner, definition.id, definition.dataNeeds, effectivePolicy) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        launch {
          dataSource.observe(definition.dataNeeds, effectivePolicy).collect { updated ->
            liveData.copyFrom(updated)
            frameVersion.longValue = frameVersion.longValue + 1L
          }
        }
        if (effectivePolicy == FaceUpdatePolicy.SMOOTH_FRAME) {
          val cal = Calendar.getInstance()
          var lastDayOfYear = cal.get(Calendar.DAY_OF_YEAR)
          var lastYear = cal.get(Calendar.YEAR)
          while (isActive) {
            withFrameNanos {
              cal.timeInMillis = System.currentTimeMillis()
              val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
              val year = cal.get(Calendar.YEAR)
              val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
              val dayChanged = dayOfYear != lastDayOfYear ||
                year != lastYear ||
                dayOfMonth != liveData.time.dayOfMonth
              if (dayChanged) {
                lastDayOfYear = dayOfYear
                lastYear = year
              }
              updateTimeInPlace(
                cal = cal,
                target = liveData.time,
                refreshCalendarDay = dayChanged,
              )
              frameVersion.longValue = frameVersion.longValue + 1L
            }
          }
        }
      }
    }

    FaceEngine.Render(
      definition = definition,
      dataProvider = {
        // Reading frameVersion here registers the state dependency exclusively on the draw block.
        frameVersion.longValue
        liveData
      },
      mode = renderMode,
      modifier = modifier,
    )
  }

  /** Updates [target] fields in place without allocating, refreshing day/month labels when the day rolls over. */
  internal fun updateTimeInPlace(
    cal: Calendar,
    target: FaceTimeData,
    refreshCalendarDay: Boolean,
  ) {
    val h24 = cal.get(Calendar.HOUR_OF_DAY)
    val h12Raw = cal.get(Calendar.HOUR)
    target.hour12 = if (h12Raw == 0) 12 else h12Raw
    target.hour24 = h24
    target.minute = cal.get(Calendar.MINUTE)
    target.second = cal.get(Calendar.SECOND)
    target.millis = cal.get(Calendar.MILLISECOND)
    target.amPm = if (h24 < 12) "AM" else "PM"
    if (refreshCalendarDay) {
      target.dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
      target.dayNameShort = FaceTimeData.dayNameShortFor(cal)
      target.monthNameShort = FaceTimeData.monthNameShortFor(cal)
    }
  }
}

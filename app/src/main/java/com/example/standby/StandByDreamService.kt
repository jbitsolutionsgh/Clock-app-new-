package com.example.standby

import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.core.CoreSingletons
import com.example.ui.theme.AuraClockTheme
import com.example.ui.theme.OledBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * System DreamService (charging screensaver) showing the big digital face or the selected face,
 * with dimmed night brightness and periodic pixel-drift burn-in protection.
 */
class StandByDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

  private val lifecycleRegistry = LifecycleRegistry(this)
  private val savedStateController = SavedStateRegistryController.create(this)

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  override val savedStateRegistry: SavedStateRegistry
    get() = savedStateController.savedStateRegistry

  override fun onCreate() {
    super.onCreate()
    savedStateController.performRestore(null)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    isInteractive = false
    isFullscreen = true
    isScreenBright = false

    val prefs = CoreSingletons.prefs(this)
    window?.attributes = window?.attributes?.apply {
      screenBrightness = prefs.standByDimLevel.value.coerceIn(0.1f, 1.0f)
    }

    val composeView = ComposeView(this).apply {
      setViewTreeLifecycleOwner(this@StandByDreamService)
      setViewTreeSavedStateRegistryOwner(this@StandByDreamService)
      setContent {
        val accent by prefs.themeColor.collectAsStateWithLifecycle()
        val useSelected by prefs.standByUseSelectedFace.collectAsStateWithLifecycle()
        val dimLevel by prefs.standByDimLevel.collectAsStateWithLifecycle()

        AuraClockTheme(accent = accent) {
          StandByDreamStage(
            useSelectedFace = useSelected,
            dimLevel = dimLevel,
          )
        }
      }
    }

    window?.decorView?.let { decor ->
      decor.setViewTreeLifecycleOwner(this)
      decor.setViewTreeSavedStateRegistryOwner(this)
    }
    setContentView(composeView)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
  }

  override fun onDreamingStopped() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    super.onDreamingStopped()
  }

  override fun onDetachedFromWindow() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    super.onDetachedFromWindow()
  }

  override fun onDestroy() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    super.onDestroy()
  }
}

@Composable
private fun StandByDreamStage(
  useSelectedFace: Boolean,
  dimLevel: Float,
) {
  // Subtle OLED burn-in protection: shift by a few pixels every 60 seconds.
  var driftStep by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    while (isActive) {
      delay(60_000L)
      driftStep = (driftStep + 1) % DRIFT_OFFSETS.size
    }
  }
  val (dx, dy) = DRIFT_OFFSETS[driftStep]

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(OledBlack)
      .padding(16.dp)
      .alpha(dimLevel.coerceIn(0.15f, 1.0f))
      .offset { IntOffset(dx, dy) },
    contentAlignment = Alignment.Center,
  ) {
    val renderer = CoreSingletons.standByClockRenderer
    if (renderer != null) {
      renderer.Render(
        useSelectedFace = useSelectedFace,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Text(
        text = "AuraClock",
        fontSize = 36.sp,
        color = Color.White,
      )
    }
  }
}

private val DRIFT_OFFSETS = listOf(
  0 to 0,
  6 to 4,
  12 to -3,
  5 to -9,
  -7 to -6,
  -12 to 3,
  -6 to 9,
  3 to 6,
)

package com.example.standby

import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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

/**
 * System DreamService (charging screensaver) hosting the shared StandByStage
 * with clock, upcoming calendar events, dimmed brightness, and burn-in drift.
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
    val coordinator = StandByChiplet.coordinator(this)
    window?.attributes = window?.attributes?.apply {
      screenBrightness = prefs.standByDimLevel.value.coerceIn(0.1f, 1.0f)
    }

    val composeView = ComposeView(this).apply {
      setViewTreeLifecycleOwner(this@StandByDreamService)
      setViewTreeSavedStateRegistryOwner(this@StandByDreamService)
      setContent {
        val accent by prefs.themeColor.collectAsStateWithLifecycle()

        AuraClockTheme(accent = accent) {
          StandByStage(
            coordinator = coordinator,
            prefs = prefs,
            applyDimAlpha = false,
            isDreamService = true,
            modifier = Modifier,
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

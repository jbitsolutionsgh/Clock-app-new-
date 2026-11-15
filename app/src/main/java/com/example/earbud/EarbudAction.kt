package com.example.earbud

import android.content.Context
import com.example.core.AuraPrefs
import com.example.core.CoreSingletons

/**
 * Contract for any action triggered by an earbud media key.
 * New actions can be added here without modifying MediaKeyRouter.
 */
internal interface EarbudAction {
  val id: String
  val label: String
  fun execute(context: Context)
}

internal class NoOpAction : EarbudAction {
  override val id: String = AuraPrefs.ACTION_NONE
  override val label: String = "Disabled (None)"
  override fun execute(context: Context) = Unit
}

internal class SpeakTimeAction : EarbudAction {
  override val id: String = AuraPrefs.ACTION_SPEAK_TIME
  override val label: String = "Speak current time"
  override fun execute(context: Context) {
    CoreSingletons.speaker(context).speakCurrentTime()
  }
}

internal class ToggleTimerAction : EarbudAction {
  override val id: String = AuraPrefs.ACTION_TIMER_TOGGLE
  override val label: String = "Start / Pause timer"
  override fun execute(context: Context) {
    CoreSingletons.timerController?.startOrPause()
  }
}

internal class ToggleStopwatchAction : EarbudAction {
  override val id: String = AuraPrefs.ACTION_STOPWATCH_TOGGLE
  override val label: String = "Start / Pause stopwatch"
  override fun execute(context: Context) {
    CoreSingletons.stopwatchController?.startOrPause()
  }
}

internal class NextWatchFaceAction : EarbudAction {
  override val id: String = AuraPrefs.ACTION_NEXT_FACE
  override val label: String = "Next watch face"
  override fun execute(context: Context) {
    CoreSingletons.faceNavigator?.nextFace()
  }
}

/** Single registry of all available earbud actions. */
internal object EarbudActionRegistry {
  val actions: List<EarbudAction> = listOf(
    SpeakTimeAction(),
    ToggleTimerAction(),
    ToggleStopwatchAction(),
    NextWatchFaceAction(),
    NoOpAction(),
  )

  /** Looks up an action by ID, defaulting to NoOpAction. */
  fun findById(id: String): EarbudAction =
    actions.firstOrNull { it.id == id } ?: actions.last()
}

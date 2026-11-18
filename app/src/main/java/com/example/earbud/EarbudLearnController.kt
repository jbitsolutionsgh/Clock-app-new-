package com.example.earbud

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.core.CoreSingletons
import com.example.core.LearnedPressPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface LearnModeState {
  data object Idle : LearnModeState
  data class Listening(
    val remainingSeconds: Int,
    val capturedCount: Int,
    val latestKeyName: String?,
  ) : LearnModeState
  data class Captured(val candidate: LearnedPressCandidate) : LearnModeState
  data object TimedOutEmpty : LearnModeState
}

/**
 * Coordinates the 6-second "Learn a press" recording session with MediaKeyRouter
 * and persists confirmed LearnedPressPattern entries into AuraPrefs.
 */
internal class EarbudLearnController(
  private val handler: Handler = Handler(Looper.getMainLooper()),
) {
  private val _state = MutableStateFlow<LearnModeState>(LearnModeState.Idle)
  val state: StateFlow<LearnModeState> = _state.asStateFlow()

  private val capturedEvents = mutableListOf<Pair<Int, Long>>()
  private var remainingSec = LEARN_DURATION_SECONDS
  private var activeRouter: MediaKeyRouter? = null
  private var tickRunnable: Runnable? = null

  /** Starts a 6-second capture session listening to incoming earbud key codes. */
  fun start(context: Context, router: MediaKeyRouter) {
    stopTimersAndListener()
    capturedEvents.clear()
    remainingSec = LEARN_DURATION_SECONDS
    activeRouter = router

    router.setLearnCaptureListener { keyCode, timestampMs ->
      capturedEvents += keyCode to timestampMs
      val current = _state.value
      if (current is LearnModeState.Listening) {
        _state.value = current.copy(
          capturedCount = capturedEvents.size,
          latestKeyName = PressPatternDetector.plainKeyName(keyCode),
        )
      }
    }

    _state.value = LearnModeState.Listening(
      remainingSeconds = remainingSec,
      capturedCount = 0,
      latestKeyName = null,
    )

    val runnable = object : Runnable {
      override fun run() {
        remainingSec -= 1
        if (remainingSec <= 0) {
          finishCapture()
        } else {
          _state.value = LearnModeState.Listening(
            remainingSeconds = remainingSec,
            capturedCount = capturedEvents.size,
            latestKeyName = capturedEvents.lastOrNull()?.first?.let { PressPatternDetector.plainKeyName(it) },
          )
          handler.postDelayed(this, 1_000L)
        }
      }
    }
    tickRunnable = runnable
    handler.postDelayed(runnable, 1_000L)
  }

  /** Completes capture immediately using presses recorded so far. */
  fun finishNow() {
    finishCapture()
  }

  /** Cancels Learn Mode and returns router to normal pattern detection. */
  fun cancel() {
    stopTimersAndListener()
    capturedEvents.clear()
    _state.value = LearnModeState.Idle
  }

  /** Saves the analyzed candidate pattern mapped to [actionId] and resets Learn Mode to Idle. */
  fun confirmAndSave(context: Context, candidate: LearnedPressCandidate, actionId: String) {
    val prefs = CoreSingletons.prefs(context.applicationContext)
    prefs.saveLearnedPattern(
      LearnedPressPattern(
        keyCode = candidate.keyCode,
        count = candidate.count,
        actionId = actionId,
        keyLabel = candidate.keyLabel,
      ),
    )
    cancel()
  }

  private fun finishCapture() {
    stopTimersAndListener()
    val candidate = PressPatternDetector.analyzeLearnedEvents(capturedEvents.toList())
    _state.value = if (candidate != null) {
      LearnModeState.Captured(candidate)
    } else {
      LearnModeState.TimedOutEmpty
    }
  }

  private fun stopTimersAndListener() {
    tickRunnable?.let { handler.removeCallbacks(it) }
    tickRunnable = null
    activeRouter?.setLearnCaptureListener(null)
    activeRouter = null
  }

  companion object {
    const val LEARN_DURATION_SECONDS = 6
  }
}

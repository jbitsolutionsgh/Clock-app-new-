package com.example.stopwatch

import android.os.SystemClock
import com.example.core.StopwatchController
import com.example.core.StopwatchSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared stopwatch engine driven by SystemClock.elapsedRealtime() for drift-free lap timing.
 */
internal class StopwatchEngine : StopwatchController {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(StopwatchSnapshot())
  override val state: StateFlow<StopwatchSnapshot> = _state.asStateFlow()

  private var tickJob: Job? = null
  private var accumulatedMs: Long = 0L
  private var startElapsedMs: Long = 0L

  /** Starts or pauses the stopwatch. */
  override fun startOrPause() {
    if (_state.value.isRunning) {
      pauseInternal()
    } else {
      startInternal()
    }
  }

  /** Resets elapsed time and clears recorded laps. */
  override fun reset() {
    tickJob?.cancel()
    tickJob = null
    accumulatedMs = 0L
    startElapsedMs = 0L
    _state.value = StopwatchSnapshot()
  }

  /** Records the current elapsed time as a new lap while running. */
  override fun lap() {
    val snap = _state.value
    if (!snap.isRunning && snap.elapsedMs == 0L) return
    val currentTotal = if (snap.isRunning) {
      accumulatedMs + (SystemClock.elapsedRealtime() - startElapsedMs)
    } else {
      accumulatedMs
    }
    _state.value = snap.copy(
      elapsedMs = currentTotal,
      lapsMs = listOf(currentTotal) + snap.lapsMs,
    )
  }

  private fun startInternal() {
    startElapsedMs = SystemClock.elapsedRealtime()
    _state.value = _state.value.copy(isRunning = true)
    tickJob?.cancel()
    tickJob = scope.launch {
      while (isActive) {
        val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - startElapsedMs)
        _state.value = _state.value.copy(elapsedMs = elapsed)
        delay(33L)
      }
    }
  }

  private fun pauseInternal() {
    tickJob?.cancel()
    tickJob = null
    accumulatedMs += (SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(0L)
    _state.value = _state.value.copy(isRunning = false, elapsedMs = accumulatedMs)
  }
}

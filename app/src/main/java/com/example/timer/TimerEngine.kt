package com.example.timer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.core.AuraPrefs
import com.example.core.TimerController
import com.example.core.TimerPhase
import com.example.core.TimerSnapshot
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
 * Single shared countdown timer engine using SystemClock.elapsedRealtime() for exact timing.
 * Supports Rounds auto-restart mode, configurable gap wait, and speaker turn cycling.
 */
class TimerEngine(
  context: Context,
  private val prefs: AuraPrefs,
) : TimerController {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val beepPlayer = TimerBeepPlayer(appContext, prefs)

  private val _state = MutableStateFlow(
    TimerSnapshot(
      phase = TimerPhase.IDLE,
      totalDurationMs = prefs.timerDurationSeconds.value * 1000L,
      remainingMs = prefs.timerDurationSeconds.value * 1000L,
    )
  )
  override val state: StateFlow<TimerSnapshot> = _state.asStateFlow()

  private var tickJob: Job? = null
  private var targetEndElapsedMs: Long = 0L
  private var foregroundServiceRequested: Boolean = false

  /** Sets a new timer duration in seconds and resets to IDLE. */
  override fun setDurationSeconds(seconds: Int) {
    val clamped = seconds.coerceIn(1, 5999)
    prefs.setTimerDurationSeconds(clamped)
    stopLoopAndService()
    beepPlayer.stopBeep()
    val ms = clamped * 1000L
    _state.value = TimerSnapshot(
      phase = TimerPhase.IDLE,
      totalDurationMs = ms,
      remainingMs = ms,
      roundNumber = 1,
      currentSpeaker = 1,
      isRinging = false,
    )
  }

  /** Toggles between running and paused, or restarts from finished/idle. */
  override fun startOrPause() {
    val current = _state.value
    when (current.phase) {
      TimerPhase.RUNNING, TimerPhase.GAP_WAIT -> pauseInternal()
      TimerPhase.PAUSED -> resumeInternal()
      TimerPhase.IDLE, TimerPhase.FINISHED -> startFreshInternal()
    }
  }

  /** Resets the timer back to full duration, Round 1, Speaker 1, and stops ringing. */
  override fun reset() {
    stopLoopAndService()
    beepPlayer.stopBeep()
    val totalMs = prefs.timerDurationSeconds.value * 1000L
    _state.value = TimerSnapshot(
      phase = TimerPhase.IDLE,
      totalDurationMs = totalMs,
      remainingMs = totalMs,
      gapRemainingMs = 0L,
      roundNumber = 1,
      currentSpeaker = 1,
      isRinging = false,
    )
  }

  /** Stops the timer completely (used by foreground notification Stop action or teacher Stop). */
  override fun stop() {
    reset()
  }

  /** Silences any active alarm beep immediately without altering round count. */
  override fun silenceBeep() {
    beepPlayer.stopBeep()
    _state.value = _state.value.copy(isRinging = false)
    if (_state.value.phase == TimerPhase.FINISHED || _state.value.phase == TimerPhase.IDLE) {
      stopServiceIfRunning()
    }
  }

  /** Plays a short sample beep for the Settings "Test sound" button. */
  internal fun playTestBeep() {
    beepPlayer.playBeep()
  }

  private fun startFreshInternal() {
    beepPlayer.stopBeep()
    val totalMs = prefs.timerDurationSeconds.value * 1000L
    targetEndElapsedMs = SystemClock.elapsedRealtime() + totalMs
    _state.value = TimerSnapshot(
      phase = TimerPhase.RUNNING,
      totalDurationMs = totalMs,
      remainingMs = totalMs,
      gapRemainingMs = 0L,
      roundNumber = 1,
      currentSpeaker = 1,
      isRinging = false,
    )
    startForegroundService()
    startTickLoop()
  }

  private fun resumeInternal() {
    beepPlayer.stopBeep()
    val current = _state.value
    val rem = current.remainingMs.coerceAtLeast(100L)
    targetEndElapsedMs = SystemClock.elapsedRealtime() + rem
    _state.value = current.copy(phase = TimerPhase.RUNNING, isRinging = false)
    startForegroundService()
    startTickLoop()
  }

  private fun pauseInternal() {
    tickJob?.cancel()
    tickJob = null
    beepPlayer.stopBeep()
    val current = _state.value
    val now = SystemClock.elapsedRealtime()
    val remaining = if (current.phase == TimerPhase.RUNNING) {
      (targetEndElapsedMs - now).coerceAtLeast(0L)
    } else {
      current.totalDurationMs
    }
    _state.value = current.copy(
      phase = TimerPhase.PAUSED,
      remainingMs = remaining,
      gapRemainingMs = 0L,
      isRinging = false,
    )
    stopServiceIfRunning()
  }

  private fun startTickLoop() {
    tickJob?.cancel()
    tickJob = scope.launch {
      while (isActive) {
        val current = _state.value
        val now = SystemClock.elapsedRealtime()
        when (current.phase) {
          TimerPhase.RUNNING -> {
            val remaining = targetEndElapsedMs - now
            if (remaining <= 0L) {
              onCountdownReachedZero()
            } else {
              _state.value = current.copy(remainingMs = remaining)
            }
          }
          TimerPhase.GAP_WAIT -> {
            val gapRem = targetEndElapsedMs - now
            if (gapRem <= 0L) {
              startNextRoundNow()
            } else {
              _state.value = current.copy(gapRemainingMs = gapRem)
            }
          }
          else -> break
        }
        delay(50L)
      }
    }
  }

  private fun onCountdownReachedZero() {
    val current = _state.value
    _state.value = current.copy(remainingMs = 0L, isRinging = true)
    beepPlayer.playBeep {
      _state.value = _state.value.copy(isRinging = false)
      if (_state.value.phase == TimerPhase.FINISHED) {
        stopServiceIfRunning()
      }
    }

    if (prefs.roundsMode.value) {
      val gapMs = prefs.roundGapSeconds.value * 1000L
      if (gapMs > 0L) {
        targetEndElapsedMs = SystemClock.elapsedRealtime() + gapMs
        _state.value = _state.value.copy(
          phase = TimerPhase.GAP_WAIT,
          remainingMs = 0L,
          gapRemainingMs = gapMs,
        )
      } else {
        startNextRoundNow()
      }
    } else {
      tickJob?.cancel()
      tickJob = null
      _state.value = _state.value.copy(
        phase = TimerPhase.FINISHED,
        remainingMs = 0L,
        gapRemainingMs = 0L,
      )
    }
  }

  private fun startNextRoundNow() {
    val current = _state.value
    val totalMs = prefs.timerDurationSeconds.value * 1000L
    val groupSize = prefs.turnGroupSize.value.coerceIn(2, 10)
    val nextRound = current.roundNumber + 1
    val nextSpeaker = (current.currentSpeaker % groupSize) + 1
    targetEndElapsedMs = SystemClock.elapsedRealtime() + totalMs
    _state.value = current.copy(
      phase = TimerPhase.RUNNING,
      totalDurationMs = totalMs,
      remainingMs = totalMs,
      gapRemainingMs = 0L,
      roundNumber = nextRound,
      currentSpeaker = nextSpeaker,
    )
  }

  private fun stopLoopAndService() {
    tickJob?.cancel()
    tickJob = null
    stopServiceIfRunning()
  }

  private fun startForegroundService() {
    foregroundServiceRequested = true
    runCatching {
      val intent = Intent(appContext, TimerForegroundService::class.java).apply {
        action = TimerForegroundService.ACTION_START
      }
      ContextCompat.startForegroundService(appContext, intent)
    }
  }

  private fun stopServiceIfRunning() {
    if (!foregroundServiceRequested) return
    foregroundServiceRequested = false
    runCatching {
      val intent = Intent(appContext, TimerForegroundService::class.java).apply {
        action = TimerForegroundService.ACTION_STOP_SERVICE
      }
      appContext.startService(intent)
    }
  }
}

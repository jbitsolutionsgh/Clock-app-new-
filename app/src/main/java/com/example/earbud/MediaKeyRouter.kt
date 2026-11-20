package com.example.earbud

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.example.core.AuraPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Routes Bluetooth/wired media key events through [PressPatternDetector], Learn Mode interception,
 * and music-app key forwarding with a 1-second self-send loop guard.
 */
internal class MediaKeyRouter(
  private val context: Context,
  private val prefs: AuraPrefs,
  private val onKeyDiagnostics: (keyLabel: String, timeLabel: String, actionLabel: String) -> Unit,
  private val onPatternDiagnostics: (patternLabel: String, keyLabel: String, timeLabel: String, actionLabel: String) -> Unit = { _, _, _, _ -> },
  private val onForwardResult: (success: Boolean) -> Unit = {},
  private val forwardMediaKey: ((keyCode: Int) -> Boolean)? = null,
  private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
  constructor(
    context: Context,
    prefs: AuraPrefs,
    onKeyDiagnostics: (keyLabel: String, timeLabel: String, actionLabel: String) -> Unit,
  ) : this(
    context = context,
    prefs = prefs,
    onKeyDiagnostics = onKeyDiagnostics,
    onPatternDiagnostics = { _, _, _, _ -> },
    onForwardResult = {},
    forwardMediaKey = null,
    clockMs = { SystemClock.elapsedRealtime() },
  )

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())

  private var detectorState = DetectorState()
  private var pendingTimeoutRunnable: Runnable? = null
  private var lastTransportCallbackMs: Long = -1L

  @Volatile
  private var learnCaptureListener: ((keyCode: Int, timestampMs: Long) -> Unit)? = null

  @Volatile
  private var sessionForwarder: ((keyCode: Int) -> Boolean)? = null

  @Volatile
  private var selfForwardIgnoreUntilMs: Long = -1L

  @Volatile
  private var lastForwardedKeyCode: Int = 0

  /** Attaches or clears the active MediaSession forwarder owned by EarbudListenerService. */
  fun setSessionForwarder(forwarder: ((keyCode: Int) -> Boolean)?) {
    sessionForwarder = forwarder
  }

  /** Attaches or clears an active Learn Mode capture listener that intercepts incoming presses. */
  fun setLearnCaptureListener(listener: ((keyCode: Int, timestampMs: Long) -> Unit)?) {
    if (listener != null) {
      cancelPendingBurst()
    }
    learnCaptureListener = listener
  }

  /** Handles a raw Android media KeyEvent, returning true if consumed. */
  fun handleKeyEvent(
    event: KeyEvent,
    route: String = KeyArrivalRoute.DIRECT.label,
  ): Boolean {
    val nowElapsed = clockMs()
    val isDown = event.action != KeyEvent.ACTION_UP
    if (isSelfSentForwarding(event.keyCode, nowElapsed)) {
      RawKeyLog.record(
        keyCode = event.keyCode,
        isDown = isDown,
        route = route,
        outcome = "Ignored as its own key",
      )
      // Return false so EarbudMediaButtonReceiver never abortBroadcast()s AuraClock's own forwarded key.
      return false
    }

    val learn = learnCaptureListener
    if (learn != null) {
      if (!isMediaCandidateKey(event.keyCode)) {
        RawKeyLog.record(
          keyCode = event.keyCode,
          isDown = isDown,
          route = "${KeyArrivalRoute.LEARN_MODE.label} ($route)",
          outcome = "Ignored as unsupported",
        )
        return false
      }
      if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        RawKeyLog.record(
          keyCode = event.keyCode,
          isDown = true,
          route = "${KeyArrivalRoute.LEARN_MODE.label} ($route)",
          outcome = "Ran action: Captured in Learn mode",
        )
        learn(event.keyCode, nowElapsed)
      } else {
        RawKeyLog.record(
          keyCode = event.keyCode,
          isDown = isDown,
          route = "${KeyArrivalRoute.LEARN_MODE.label} ($route)",
          outcome = if (!isDown) "Ignored UP (Learn mode acts on DOWN)" else "Ignored key repeat",
        )
      }
      return true
    }

    if (!isSupportedKey(event.keyCode)) {
      RawKeyLog.record(
        keyCode = event.keyCode,
        isDown = isDown,
        route = route,
        outcome = "Ignored as unsupported",
      )
      return false
    }
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
      RawKeyLog.record(
        keyCode = event.keyCode,
        isDown = isDown,
        route = route,
        outcome = if (!isDown) "Ignored UP (handled on DOWN)" else "Ignored key repeat",
      )
      return true
    }
    return routeKeyCode(event.keyCode, allowDedup = false, route = route)
  }

  /** Routes a media key code directly (from KeyEvent or MediaSession transport fallback). */
  fun routeKeyCode(
    keyCode: Int,
    allowDedup: Boolean = false,
    route: String = KeyArrivalRoute.DIRECT.label,
  ): Boolean {
    val nowElapsed = clockMs()
    if (isSelfSentForwarding(keyCode, nowElapsed)) {
      RawKeyLog.record(
        keyCode = keyCode,
        isDown = true,
        route = route,
        outcome = "Ignored as its own key",
      )
      return false
    }

    val learn = learnCaptureListener
    if (learn != null) {
      if (!isMediaCandidateKey(keyCode)) {
        RawKeyLog.record(
          keyCode = keyCode,
          isDown = true,
          route = "${KeyArrivalRoute.LEARN_MODE.label} ($route)",
          outcome = "Ignored as unsupported",
        )
        return false
      }
      if (allowDedup && lastTransportCallbackMs >= 0L && nowElapsed - lastTransportCallbackMs in 0L..119L) {
        RawKeyLog.record(
          keyCode = keyCode,
          isDown = true,
          route = "${KeyArrivalRoute.LEARN_MODE.label} ($route)",
          outcome = "Ignored duplicate transport event",
        )
        return true
      }
      lastTransportCallbackMs = nowElapsed
      RawKeyLog.record(
        keyCode = keyCode,
        isDown = true,
        route = "${KeyArrivalRoute.LEARN_MODE.label} ($route)",
        outcome = "Ran action: Captured in Learn mode",
      )
      learn(keyCode, nowElapsed)
      return true
    }

    if (!isSupportedKey(keyCode)) {
      RawKeyLog.record(
        keyCode = keyCode,
        isDown = true,
        route = route,
        outcome = "Ignored as unsupported",
      )
      return false
    }
    if (allowDedup && lastTransportCallbackMs >= 0L && nowElapsed - lastTransportCallbackMs in 0L..119L) {
      RawKeyLog.record(
        keyCode = keyCode,
        isDown = true,
        route = route,
        outcome = "Ignored duplicate transport event",
      )
      return true
    }
    lastTransportCallbackMs = nowElapsed

    val config = currentDetectorConfig()
    val step = PressPatternDetector.onKeyPress(detectorState, keyCode, nowElapsed, config)
    detectorState = step.nextState

    pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingTimeoutRunnable = null

    if (step.emitted.isNotEmpty()) {
      step.emitted.forEach { dispatchDetected(it, route) }
    } else {
      RawKeyLog.record(
        keyCode = keyCode,
        isDown = true,
        route = route,
        outcome = "Waiting for multi-press window (${config.windowMs} ms)",
      )
    }

    val deadline = step.flushDeadlineMs
    if (deadline != null && !detectorState.isEmpty) {
      val delay = (deadline - nowElapsed).coerceAtLeast(1L)
      val runnable = Runnable {
        pendingTimeoutRunnable = null
        val flushStep = PressPatternDetector.onTimeout(detectorState, clockMs(), currentDetectorConfig())
        detectorState = flushStep.nextState
        flushStep.emitted.forEach { dispatchDetected(it, route) }
      }
      pendingTimeoutRunnable = runnable
      mainHandler.postDelayed(runnable, delay)
    }

    return true
  }

  private fun cancelPendingBurst() {
    pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingTimeoutRunnable = null
    detectorState = DetectorState()
  }

  private fun currentDetectorConfig(): DetectorConfig {
    val patterns = prefs.learnedPatterns.value
    val doubleAction = prefs.playPauseDoubleActionId.value
    val tripleAction = prefs.playPauseTripleActionId.value
    var maxPlayPause = 1
    if (doubleAction != AuraPrefs.ACTION_NONE) maxPlayPause = 2
    if (tripleAction != AuraPrefs.ACTION_NONE) maxPlayPause = 3
    val learnedMax = patterns
      .filter { PressPatternDetector.isPlayPauseFamily(it.keyCode) }
      .maxOfOrNull { it.count } ?: 1
    return DetectorConfig(
      windowMs = prefs.earbudPressWindowMs.value.toLong().coerceIn(300L, 600L),
      maxPlayPauseCount = maxOf(maxPlayPause, learnedMax).coerceIn(1, PressPatternDetector.MAX_BURST_COUNT),
      learnedPatterns = patterns,
    )
  }

  private fun dispatchDetected(detected: DetectedPress, route: String) {
    val keyLabel = "${PressPatternDetector.androidKeyName(detected.forwardKeyCode)} (${detected.forwardKeyCode})"
    val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val patternLabel = if (detected.matchedLearnedPattern != null && detected.keyGroup != PressKeyGroup.PLAY_PAUSE_FAMILY) {
      PressPatternDetector.formatPatternLabel(detected.matchedLearnedPattern)
    } else {
      detected.kind.displayLabel
    }

    val actionId = resolveActionId(detected)
    val action = EarbudActionRegistry.findById(actionId)

    if (actionId == AuraPrefs.ACTION_FORWARD_TO_MUSIC || actionId == AuraPrefs.ACTION_NONE) {
      // Ignore bounced copies of AuraClock's own dispatched key for 1 second to avoid a feedback loop.
      lastForwardedKeyCode = detected.forwardKeyCode
      selfForwardIgnoreUntilMs = clockMs() + SELF_FORWARD_IGNORE_WINDOW_MS
      val forwarded = runCatching {
        forwardMediaKey?.invoke(detected.forwardKeyCode)
          ?: sessionForwarder?.invoke(detected.forwardKeyCode)
          ?: defaultDispatchMediaKey(appContext, detected.forwardKeyCode)
      }.getOrDefault(false)
      onForwardResult(forwarded)
      val diagAction = if (forwarded) {
        "${action.label} (Forwarded)"
      } else {
        "${action.label} (Forward failed)"
      }
      RawKeyLog.record(
        keyCode = detected.forwardKeyCode,
        isDown = true,
        route = route,
        outcome = if (forwarded) "Forwarded to the music app (${detected.kind.displayLabel})" else "Forward to music app failed",
      )
      onKeyDiagnostics(keyLabel, timeLabel, diagAction)
      onPatternDiagnostics(patternLabel, keyLabel, timeLabel, diagAction)
    } else {
      RawKeyLog.record(
        keyCode = detected.forwardKeyCode,
        isDown = true,
        route = route,
        outcome = "Ran action: ${action.label} (${detected.kind.displayLabel})",
      )
      onKeyDiagnostics(keyLabel, timeLabel, action.label)
      onPatternDiagnostics(patternLabel, keyLabel, timeLabel, action.label)
      action.execute(appContext)
    }
  }

  private fun resolveActionId(detected: DetectedPress): String {
    detected.matchedLearnedPattern?.let { return it.actionId }
    return when (detected.keyGroup) {
      PressKeyGroup.PLAY_PAUSE_FAMILY -> when (detected.count) {
        1 -> prefs.playPauseActionId.value
        2 -> prefs.playPauseDoubleActionId.value
        3 -> prefs.playPauseTripleActionId.value
        else -> AuraPrefs.ACTION_NONE
      }
      PressKeyGroup.NEXT -> prefs.nextActionId.value
      PressKeyGroup.PREVIOUS -> prefs.previousActionId.value
      PressKeyGroup.CUSTOM -> AuraPrefs.ACTION_NONE
    }
  }

  internal fun isSelfSentForwarding(keyCode: Int, nowElapsedMs: Long): Boolean {
    val until = selfForwardIgnoreUntilMs
    if (until < 0L || nowElapsedMs >= until) return false
    val forwarded = lastForwardedKeyCode
    if (forwarded == 0) return false
    return keyCode == forwarded ||
      (PressPatternDetector.isPlayPauseFamily(keyCode) && PressPatternDetector.isPlayPauseFamily(forwarded))
  }

  private fun isSupportedKey(keyCode: Int): Boolean =
    PressPatternDetector.isSupportedDefaultKey(keyCode) ||
      prefs.learnedPatterns.value.any { it.keyCode == keyCode }

  private fun isMediaCandidateKey(keyCode: Int): Boolean =
    PressPatternDetector.isSupportedDefaultKey(keyCode) ||
      keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
      keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
      keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
      keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ||
      keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD ||
      keyCode == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD ||
      keyCode == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD ||
      keyCode == KeyEvent.KEYCODE_MUTE

  companion object {
    const val SELF_FORWARD_IGNORE_WINDOW_MS = 1_000L

    /** Sends a synthetic ACTION_DOWN + ACTION_UP pair through Android AudioManager. */
    fun defaultDispatchMediaKey(context: Context, keyCode: Int): Boolean =
      runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
          ?: return false
        val nowUptime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(nowUptime, nowUptime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(nowUptime, nowUptime, KeyEvent.ACTION_UP, keyCode, 0))
        true
      }.getOrDefault(false)
  }
}

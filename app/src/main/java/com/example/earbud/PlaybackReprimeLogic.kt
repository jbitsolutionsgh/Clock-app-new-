package com.example.earbud

/**
 * Result of evaluating an AudioPlaybackCallback change against the debounce,
 * self-audio ignore window, user setting, and rate-limit rules.
 */
internal enum class ReprimePlanAction {
  SCHEDULE,
  CANCEL_PENDING,
  IGNORE,
}

/** Planned action and delay returned by [PlaybackReprimeLogic.evaluatePlaybackChange]. */
internal data class ReprimePlan(
  val action: ReprimePlanAction,
  val delayMs: Long = 0L,
  val isExternalPlaying: Boolean,
  val ignoredSelfAudio: Boolean = false,
)

/**
 * Pure state-transition functions for deciding when EarbudListenerService should
 * re-run its silent 30 ms media-button prime during external audio playback.
 */
internal object PlaybackReprimeLogic {
  const val DEBOUNCE_MS = 400L
  const val SELF_AUDIO_IGNORE_WINDOW_MS = 1_000L
  const val RATE_LIMIT_MS = 3_000L

  /**
   * Returns true if [nowMs] falls inside the 1-second ignore window following
   * AuraClock's own silent prime or TTS speech, or while AuraClock is currently speaking.
   */
  fun isWithinSelfAudioIgnoreWindow(
    nowMs: Long,
    lastSelfAudioMs: Long,
    isCurrentlySpeaking: Boolean = false,
  ): Boolean {
    if (isCurrentlySpeaking) return true
    if (lastSelfAudioMs < 0L) return false
    val elapsed = nowMs - lastSelfAudioMs
    return elapsed in 0L until SELF_AUDIO_IGNORE_WINDOW_MS
  }

  /**
   * Decides whether a playback config change should schedule a debounced re-prime (min 400 ms),
   * cancel any pending re-prime, or be ignored (self-audio within 1 s or music-app-keeps mode).
   */
  fun evaluatePlaybackChange(
    nowMs: Long,
    hasExternalAudio: Boolean,
    takeOverEnabled: Boolean,
    lastSelfAudioMs: Long,
    lastReprimeMs: Long,
    isCurrentlySpeaking: Boolean = false,
  ): ReprimePlan {
    if (isWithinSelfAudioIgnoreWindow(nowMs, lastSelfAudioMs, isCurrentlySpeaking)) {
      return ReprimePlan(
        action = ReprimePlanAction.IGNORE,
        isExternalPlaying = hasExternalAudio,
        ignoredSelfAudio = true,
      )
    }
    if (!hasExternalAudio) {
      return ReprimePlan(
        action = ReprimePlanAction.CANCEL_PENDING,
        isExternalPlaying = false,
      )
    }
    if (!takeOverEnabled) {
      return ReprimePlan(
        action = ReprimePlanAction.IGNORE,
        isExternalPlaying = true,
      )
    }
    val minNextAllowedMs = if (lastReprimeMs < 0L) nowMs else lastReprimeMs + RATE_LIMIT_MS
    val cooldownWaitMs = (minNextAllowedMs - nowMs).coerceAtLeast(0L)
    val delayMs = maxOf(DEBOUNCE_MS, cooldownWaitMs)
    return ReprimePlan(
      action = ReprimePlanAction.SCHEDULE,
      delayMs = delayMs,
      isExternalPlaying = true,
    )
  }

  /**
   * Final guard evaluated when the debounced runnable fires to guarantee at most one re-prime
   * every 3 seconds and no re-prime inside AuraClock's 1-second self-audio ignore window.
   */
  fun canExecuteReprimeNow(
    nowMs: Long,
    takeOverEnabled: Boolean,
    lastSelfAudioMs: Long,
    lastReprimeMs: Long,
    isCurrentlySpeaking: Boolean = false,
  ): Boolean {
    if (!takeOverEnabled) return false
    if (isWithinSelfAudioIgnoreWindow(nowMs, lastSelfAudioMs, isCurrentlySpeaking)) return false
    if (lastReprimeMs >= 0L && (nowMs - lastReprimeMs) < RATE_LIMIT_MS) return false
    return true
  }
}

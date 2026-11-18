package com.example.earbud

import android.view.KeyEvent
import com.example.core.LearnedPressPattern
import java.util.Locale

internal enum class PressKeyGroup {
  PLAY_PAUSE_FAMILY,
  NEXT,
  PREVIOUS,
  CUSTOM,
}

internal enum class PressPatternKind(val displayLabel: String) {
  SINGLE("Single press"),
  DOUBLE("Double press"),
  TRIPLE("Triple press"),
  QUADRUPLE("Quadruple press"),
  NEXT("Next track"),
  PREVIOUS("Previous track"),
  CUSTOM("Custom press"),
}

internal data class DetectorConfig(
  val windowMs: Long = 400L,
  val maxPlayPauseCount: Int = PressPatternDetector.MAX_BURST_COUNT,
  val learnedPatterns: List<LearnedPressPattern> = emptyList(),
)

internal data class DetectorState(
  val activeGroup: PressKeyGroup? = null,
  val activeCanonicalKeyCode: Int = 0,
  val keyCodes: List<Int> = emptyList(),
  val count: Int = 0,
  val firstTimestampMs: Long = 0L,
  val lastTimestampMs: Long = 0L,
) {
  val isEmpty: Boolean get() = count <= 0 || activeGroup == null
}

internal data class DetectedPress(
  val keyGroup: PressKeyGroup,
  val primaryKeyCode: Int,
  val forwardKeyCode: Int,
  val keyCodes: List<Int>,
  val count: Int,
  val kind: PressPatternKind,
  val firstTimestampMs: Long,
  val lastTimestampMs: Long,
  val matchedLearnedPattern: LearnedPressPattern?,
)

internal data class DetectorStepResult(
  val nextState: DetectorState,
  val emitted: List<DetectedPress>,
  val flushDeadlineMs: Long?,
)

internal data class LearnedPressCandidate(
  val keyCode: Int,
  val count: Int,
  val spanMs: Long,
  val keyLabel: String,
  val plainSummary: String,
)

/**
 * Pure state-transition functions for counting earbud presses inside a configurable timing window,
 * grouping mixed Play/Pause & HeadsetHook codes, matching learned patterns, and analyzing Learn Mode captures.
 */
internal object PressPatternDetector {

  const val MAX_BURST_COUNT = 4
  private const val LEARN_BURST_GAP_MS = 1_000L

  fun isPlayPauseFamily(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
      keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
      keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
      keyCode == KeyEvent.KEYCODE_HEADSETHOOK

  fun isSupportedDefaultKey(keyCode: Int): Boolean =
    isPlayPauseFamily(keyCode) ||
      keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
      keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

  fun keyGroupFor(keyCode: Int): PressKeyGroup = when {
    isPlayPauseFamily(keyCode) -> PressKeyGroup.PLAY_PAUSE_FAMILY
    keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> PressKeyGroup.NEXT
    keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PressKeyGroup.PREVIOUS
    else -> PressKeyGroup.CUSTOM
  }

  /** Normalizes Play/Pause variants to KEYCODE_MEDIA_PLAY_PAUSE while preserving exact Next/Previous/Other codes. */
  fun canonicalKeyCodeFor(keyCode: Int): Int =
    if (isPlayPauseFamily(keyCode)) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else keyCode

  fun plainKeyName(keyCode: Int): String = when {
    isPlayPauseFamily(keyCode) -> "Play/Pause"
    keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> "Next track"
    keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous track"
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
  }

  fun androidKeyName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
    KeyEvent.KEYCODE_MEDIA_PLAY -> "KEYCODE_MEDIA_PLAY"
    KeyEvent.KEYCODE_MEDIA_PAUSE -> "KEYCODE_MEDIA_PAUSE"
    KeyEvent.KEYCODE_HEADSETHOOK -> "KEYCODE_HEADSETHOOK"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "KEYCODE_MEDIA_NEXT"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
    else -> KeyEvent.keyCodeToString(keyCode)
  }

  fun matchLearnedPattern(
    primaryKeyCode: Int,
    count: Int,
    learnedPatterns: List<LearnedPressPattern>,
  ): LearnedPressPattern? {
    val primaryIsPlayPause = isPlayPauseFamily(primaryKeyCode)
    return learnedPatterns.firstOrNull { pattern ->
      val sameKey = if (primaryIsPlayPause && isPlayPauseFamily(pattern.keyCode)) {
        true
      } else {
        pattern.keyCode == primaryKeyCode
      }
      sameKey && pattern.count == count
    }
  }

  fun maxCollectCount(group: PressKeyGroup, keyCode: Int, config: DetectorConfig): Int {
    val canonical = canonicalKeyCodeFor(keyCode)
    val maxLearnedForKey = config.learnedPatterns
      .filter { p ->
        (group == PressKeyGroup.PLAY_PAUSE_FAMILY && isPlayPauseFamily(p.keyCode)) ||
          canonicalKeyCodeFor(p.keyCode) == canonical
      }
      .maxOfOrNull { it.count }
      ?: 1

    return when (group) {
      PressKeyGroup.PLAY_PAUSE_FAMILY ->
        maxOf(config.maxPlayPauseCount, maxLearnedForKey).coerceIn(1, MAX_BURST_COUNT)
      else -> maxLearnedForKey.coerceIn(1, MAX_BURST_COUNT)
    }
  }

  /** Processes a single incoming press at [timestampMs] against [state] and [config]. */
  fun onKeyPress(
    state: DetectorState,
    keyCode: Int,
    timestampMs: Long,
    config: DetectorConfig,
  ): DetectorStepResult {
    val emitted = mutableListOf<DetectedPress>()
    var working = state

    val incomingGroup = keyGroupFor(keyCode)
    val incomingCanonical = canonicalKeyCodeFor(keyCode)

    if (!working.isEmpty) {
      val elapsed = timestampMs - working.lastTimestampMs
      val compatible = working.activeGroup == incomingGroup &&
        working.activeCanonicalKeyCode == incomingCanonical &&
        elapsed in 0L..config.windowMs

      if (!compatible) {
        emitted += buildDetected(working, config)
        working = DetectorState()
      }
    }

    val updated = if (working.isEmpty) {
      DetectorState(
        activeGroup = incomingGroup,
        activeCanonicalKeyCode = incomingCanonical,
        keyCodes = listOf(keyCode),
        count = 1,
        firstTimestampMs = timestampMs,
        lastTimestampMs = timestampMs,
      )
    } else {
      working.copy(
        keyCodes = working.keyCodes + keyCode,
        count = working.count + 1,
        lastTimestampMs = timestampMs,
      )
    }

    val maxCount = maxCollectCount(incomingGroup, keyCode, config)
    return if (updated.count >= maxCount) {
      emitted += buildDetected(updated, config)
      DetectorStepResult(
        nextState = DetectorState(),
        emitted = emitted,
        flushDeadlineMs = null,
      )
    } else {
      DetectorStepResult(
        nextState = updated,
        emitted = emitted,
        flushDeadlineMs = updated.lastTimestampMs + config.windowMs,
      )
    }
  }

  /** Flushes a pending burst if [timestampMs] has reached its window deadline. */
  fun onTimeout(
    state: DetectorState,
    timestampMs: Long,
    config: DetectorConfig,
  ): DetectorStepResult {
    if (state.isEmpty) {
      return DetectorStepResult(state, emptyList(), null)
    }
    val elapsed = timestampMs - state.lastTimestampMs
    if (elapsed < config.windowMs) {
      return DetectorStepResult(
        nextState = state,
        emitted = emptyList(),
        flushDeadlineMs = state.lastTimestampMs + config.windowMs,
      )
    }
    return DetectorStepResult(
      nextState = DetectorState(),
      emitted = listOf(buildDetected(state, config)),
      flushDeadlineMs = null,
    )
  }

  /** Pure batch convenience runner over a time-ordered list of `(keyCode, timestampMs)` events. */
  fun detectAll(
    events: List<Pair<Int, Long>>,
    config: DetectorConfig,
  ): List<DetectedPress> {
    if (events.isEmpty()) return emptyList()
    val results = mutableListOf<DetectedPress>()
    var state = DetectorState()
    for ((keyCode, ts) in events) {
      val step = onKeyPress(state, keyCode, ts, config)
      results += step.emitted
      state = step.nextState
    }
    if (!state.isEmpty) {
      val finalStep = onTimeout(state, state.lastTimestampMs + config.windowMs, config)
      results += finalStep.emitted
    }
    return results
  }

  /** Summarizes presses recorded during 6-second Learn Mode into a storable candidate + plain-words label. */
  fun analyzeLearnedEvents(events: List<Pair<Int, Long>>): LearnedPressCandidate? {
    if (events.isEmpty()) return null

    data class Burst(
      val group: PressKeyGroup,
      val canonicalKey: Int,
      var count: Int,
      val firstMs: Long,
      var lastMs: Long,
    )

    val bursts = mutableListOf<Burst>()
    for ((rawKey, ts) in events) {
      val group = keyGroupFor(rawKey)
      val canonical = canonicalKeyCodeFor(rawKey)
      val current = bursts.lastOrNull()
      if (current != null &&
        current.group == group &&
        current.canonicalKey == canonical &&
        (ts - current.lastMs) in 0L..LEARN_BURST_GAP_MS
      ) {
        current.count += 1
        current.lastMs = ts
      } else {
        bursts += Burst(group, canonical, 1, ts, ts)
      }
    }

    // Prefer the highest-count burst (most recent if tied).
    val best = bursts.maxWithOrNull(compareBy<Burst> { it.count }.thenBy { it.lastMs }) ?: return null
    val clampedCount = best.count.coerceIn(1, MAX_BURST_COUNT)
    val spanMs = (best.lastMs - best.firstMs).coerceAtLeast(0L)
    val keyLabel = plainKeyName(best.canonicalKey)

    val summary = if (clampedCount == 1) {
      if (best.group == PressKeyGroup.PLAY_PAUSE_FAMILY) {
        "Single press arrives as Play/Pause"
      } else {
        "Arrives as $keyLabel"
      }
    } else {
      val pressWord = when (clampedCount) {
        2 -> "Double press"
        3 -> "Triple press"
        4 -> "Quadruple press"
        else -> "$clampedCount presses"
      }
      val spanSec = String.format(Locale.US, "%.1f s", spanMs / 1000.0)
      "$pressWord arrives as $clampedCount $keyLabel presses within $spanSec"
    }

    return LearnedPressCandidate(
      keyCode = best.canonicalKey,
      count = clampedCount,
      spanMs = spanMs,
      keyLabel = keyLabel,
      plainSummary = summary,
    )
  }

  fun formatPatternLabel(pattern: LearnedPressPattern): String =
    if (pattern.count <= 1) {
      pattern.keyLabel
    } else {
      "${pattern.count}× ${pattern.keyLabel}"
    }

  private fun buildDetected(state: DetectorState, config: DetectorConfig): DetectedPress {
    val group = state.activeGroup ?: PressKeyGroup.CUSTOM
    val count = state.count
    val primary = state.activeCanonicalKeyCode
    val learned = matchLearnedPattern(primary, count, config.learnedPatterns)
    val kind = when (group) {
      PressKeyGroup.PLAY_PAUSE_FAMILY -> when (count) {
        1 -> PressPatternKind.SINGLE
        2 -> PressPatternKind.DOUBLE
        3 -> PressPatternKind.TRIPLE
        else -> PressPatternKind.QUADRUPLE
      }
      PressKeyGroup.NEXT -> PressPatternKind.NEXT
      PressKeyGroup.PREVIOUS -> PressPatternKind.PREVIOUS
      PressKeyGroup.CUSTOM -> PressPatternKind.CUSTOM
    }
    return DetectedPress(
      keyGroup = group,
      primaryKeyCode = primary,
      forwardKeyCode = state.keyCodes.lastOrNull() ?: primary,
      keyCodes = state.keyCodes,
      count = count,
      kind = kind,
      firstTimestampMs = state.firstTimestampMs,
      lastTimestampMs = state.lastTimestampMs,
      matchedLearnedPattern = learned,
    )
  }
}

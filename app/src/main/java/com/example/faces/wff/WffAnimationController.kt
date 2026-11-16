package com.example.faces.wff

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Runtime animation state tracker for Watch Face Format Transforms carrying an Animation spec.
 * Handles both data-change transitions (repeat=0) and repeating time-driven animations (repeat=-1 or N).
 * Pauses cleanly when the screen goes invisible and tracks whether any transition is actively animating.
 */
internal class WffAnimationController {

  private class ChannelState {
    var initialized = false
    var fromVal = 0f
    var targetVal = 0f
    var currentVal = 0f
    var startElapsedMs = 0L
    var finished = true
  }

  private val channels = HashMap<String, ChannelState>()
  private var pausedElapsedMs = 0L
  private var totalPausedOffsetMs = 0L
  private var isPaused = false
  private var lastSampleElapsedMs = 0L

  @Volatile
  var maxActiveFps: Int = 15
    private set

  /** Advances or starts an animated transform channel, returning its interpolated value at [nowMs]. */
  fun sample(
    transform: WffTransform,
    rawTargetValue: Float,
    nowMs: Long,
    isAmbient: Boolean,
  ): Float {
    val anim = transform.animation ?: return rawTargetValue
    if (isAmbient) return rawTargetValue

    val effectiveNow = if (isPaused) pausedElapsedMs else (nowMs - totalPausedOffsetMs)
    lastSampleElapsedMs = effectiveNow
    val state = channels.getOrPut(transform.key) { ChannelState() }

    if (!state.initialized) {
      state.initialized = true
      state.targetVal = rawTargetValue
      if (anim.repeat != 0) {
        state.fromVal = anim.fromValue ?: 0f
        state.startElapsedMs = effectiveNow
        state.finished = false
      } else {
        state.fromVal = rawTargetValue
        state.currentVal = rawTargetValue
        state.finished = true
      }
    } else if (state.targetVal != rawTargetValue) {
      if (anim.repeat == 0) {
        state.fromVal = state.currentVal
        state.targetVal = rawTargetValue
        state.startElapsedMs = effectiveNow
        state.finished = false
      } else {
        state.targetVal = rawTargetValue
      }
    }

    if (anim.repeat == 0) {
      if (state.finished || anim.durationSec <= 0f) {
        state.currentVal = rawTargetValue
        state.finished = true
        return rawTargetValue
      }
      val durationMs = (anim.durationSec * 1000f).coerceAtLeast(1f)
      val progress = ((effectiveNow - state.startElapsedMs) / durationMs).coerceIn(0f, 1f)
      val curved = applyCurve(progress, anim)
      state.currentVal = interpolateValue(state.fromVal, state.targetVal, curved, anim.angleDirection)
      if (progress >= 1f) {
        state.currentVal = state.targetVal
        state.finished = true
      } else {
        maxActiveFps = maxOf(maxActiveFps, anim.fps)
      }
      return state.currentVal
    }

    // Repeating animation (repeat == -1 for infinite or N >= 1 cycles).
    val cycleMs = (anim.durationSec * 1000f).coerceAtLeast(16f)
    val elapsedMs = (effectiveNow - state.startElapsedMs).coerceAtLeast(0L).toFloat()
    val cycleIndex = (elapsedMs / cycleMs).toInt()
    if (anim.repeat > 0 && cycleIndex >= anim.repeat) {
      state.currentVal = state.targetVal
      state.finished = true
      return state.currentVal
    }
    state.finished = false
    maxActiveFps = maxOf(maxActiveFps, anim.fps)
    val cycleFraction = (elapsedMs % cycleMs) / cycleMs
    val curved = applyCurve(cycleFraction, anim)
    state.currentVal = interpolateValue(state.fromVal, state.targetVal, curved, anim.angleDirection)
    return state.currentVal
  }

  /** Returns true while any repeating animation or in-progress data transition is running. */
  fun hasActiveAnimation(): Boolean {
    for (ch in channels.values) {
      if (!ch.finished) return true
    }
    return false
  }

  /** Pauses elapsed animation clock when the WatchFace becomes invisible or stopped. */
  fun pause(nowMs: Long) {
    if (!isPaused) {
      isPaused = true
      pausedElapsedMs = nowMs - totalPausedOffsetMs
    }
  }

  /** Resumes elapsed animation clock seamlessly without jumping over off-screen time. */
  fun resume(nowMs: Long) {
    if (isPaused) {
      totalPausedOffsetMs = nowMs - pausedElapsedMs
      isPaused = false
    }
  }

  private fun interpolateValue(
    from: Float,
    to: Float,
    fraction: Float,
    direction: WffAngleDirection,
  ): Float {
    if (direction == WffAngleDirection.NONE) {
      return from + (to - from) * fraction
    }
    val delta = ((to - from) % 360f + 540f) % 360f - 180f
    val directedDelta = when (direction) {
      WffAngleDirection.CLOCKWISE -> if (delta < 0f) delta + 360f else delta
      WffAngleDirection.COUNTER_CLOCKWISE -> if (delta > 0f) delta - 360f else delta
      WffAngleDirection.NONE -> delta
    }
    return from + directedDelta * fraction
  }

  /** Applies standard WFF easing curves to a normalized [0..1] progress value. */
  internal fun applyCurve(t: Float, anim: WffAnimationSpec): Float {
    val x = t.coerceIn(0f, 1f)
    return when (anim.interpolation) {
      WffInterpolation.LINEAR -> x
      WffInterpolation.EASE_IN -> x * x * x
      WffInterpolation.EASE_OUT -> 1f - (1f - x).pow(3)
      WffInterpolation.EASE_IN_OUT -> (0.5f - 0.5f * cos(x * PI)).toFloat()
      WffInterpolation.OVERSHOOT -> {
        val u = x - 1f
        val tension = 2f
        u * u * ((tension + 1f) * u + tension) + 1f
      }
      WffInterpolation.CUBIC_BEZIER -> {
        // Evaluate y(u) with y0=0, y1=c[1], y2=c[3], y3=1 for responsive lightweight Bezier easing.
        val y1 = anim.controls.getOrElse(1) { 0f }
        val y2 = anim.controls.getOrElse(3) { 1f }
        val inv = 1f - x
        3f * inv * inv * x * y1 + 3f * inv * x * x * y2 + x * x * x
      }
    }
  }
}

package com.example.faces.wff

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.FaceData
import com.example.core.FaceRenderMode
import com.example.core.FaceTimeData
import com.example.core.FaceUpdatePolicy
import com.example.faces.ClockTimeSnapshot
import com.example.faces.WatchFace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Calendar

/**
 * [WatchFace] adapter backed by an imported Watch Face Format [WffStoredFace].
 * Drives updates strictly from declared needs, runs animations only while RESUMED at capped fps,
 * and drops to once-per-minute in ambient mode or when only hour/minute elements are present.
 */
internal class WffFace(
  val storedFace: WffStoredFace,
) : WatchFace {

  override val id: String get() = storedFace.id
  override val displayName: String get() = storedFace.displayName
  override val usesOwnDataLoop: Boolean get() = true

  @Composable
  override fun Thumbnail(modifier: Modifier) {
    WffRenderer.Thumbnail(
      face = storedFace.face,
      imagesDir = storedFace.imagesDir,
      fontsDir = storedFace.fontsDir,
      resourceFileMap = storedFace.resourceFileMap,
      fontFileMap = storedFace.fontFileMap,
      modifier = modifier,
    )
  }

  @Composable
  override fun Render(
    timeProvider: () -> ClockTimeSnapshot,
    accent: AuraThemeColor,
    smoothSeconds: Boolean,
    modifier: Modifier,
    renderMode: FaceRenderMode,
  ) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { CoreSingletons.prefs(context) }
    val dataSource = remember { CoreSingletons.faceDataSource(context) }

    val allWffConfigs by prefs.wffFaceConfigs.collectAsStateWithLifecycle()
    val savedConfig = allWffConfigs[storedFace.id].orEmpty()

    val needs = remember(storedFace.id) { WffExpression.scanDataNeeds(storedFace.face) }
    val basePolicy = remember(storedFace.id, renderMode) {
      WffExpression.updatePolicy(storedFace.face, renderMode, hasActiveAnimation = false)
    }
    val effectivePolicy = remember(basePolicy, smoothSeconds, renderMode) {
      if (basePolicy == FaceUpdatePolicy.SMOOTH_FRAME && (!smoothSeconds || renderMode == FaceRenderMode.AMBIENT)) {
        FaceUpdatePolicy.EVERY_SECOND
      } else {
        basePolicy
      }
    }

    val animController = remember(storedFace.id) { WffAnimationController() }
    val liveData = remember(storedFace.id) { FaceData(time = dataSource.currentTime(context)) }
    val evalCtx = remember(storedFace.id) {
      WffEvalContext(
        data = liveData,
        configRuntime = if (storedFace.face.userConfigs.isNotEmpty()) {
          WffConfigRuntime(storedFace.face.userConfigs, savedConfig)
        } else null,
      ).apply { syncFromData(liveData) }
    }

    val frameVersion = remember(storedFace.id) { mutableLongStateOf(0L) }
    val nowElapsedMs = remember(storedFace.id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val transitionWake = remember(storedFace.id) { MutableSharedFlow<Unit>(extraBufferCapacity = 2) }

    LaunchedEffect(storedFace.id, savedConfig) {
      if (storedFace.face.userConfigs.isNotEmpty()) {
        evalCtx.configRuntime = WffConfigRuntime(storedFace.face.userConfigs, savedConfig)
        evalCtx.configVer++
        nowElapsedMs.longValue = SystemClock.elapsedRealtime()
        frameVersion.longValue++
        transitionWake.tryEmit(Unit)
      }
    }

    LaunchedEffect(lifecycleOwner, storedFace.id, needs, effectivePolicy, renderMode) {
      val hasAnyAnim = hasAnyAnimation(storedFace.face)
      val sweepFps = maxSweepFrequency(storedFace.face)
      val hasTimeMillis = usesSubSecondTime(storedFace.face)

      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        animController.resume(SystemClock.elapsedRealtime())
        try {
          launch {
            dataSource.observe(needs, effectivePolicy).collect { updated ->
              liveData.copyFrom(updated)
              evalCtx.syncFromData(liveData)
              nowElapsedMs.longValue = SystemClock.elapsedRealtime()
              frameVersion.longValue++
              transitionWake.tryEmit(Unit)
            }
          }

          if (renderMode == FaceRenderMode.NORMAL && effectivePolicy == FaceUpdatePolicy.SMOOTH_FRAME) {
            val cal = Calendar.getInstance()
            var lastDayOfYear = cal.get(Calendar.DAY_OF_YEAR)
            var lastYear = cal.get(Calendar.YEAR)
            val targetFps = maxOf(sweepFps, maxRepeatingAnimFps(storedFace.face), 15).coerceIn(1, 60)

            fun stepSmoothFrame() {
              if (hasTimeMillis || sweepFps > 0) {
                cal.timeInMillis = System.currentTimeMillis()
                val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
                val year = cal.get(Calendar.YEAR)
                val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
                val dayChanged = dayOfYear != lastDayOfYear ||
                  year != lastYear ||
                  dayOfMonth != liveData.time.dayOfMonth
                if (dayChanged) {
                  lastDayOfYear = dayOfYear
                  lastYear = year
                }
                updateTimeInPlace(cal, liveData.time, dayChanged)
                evalCtx.syncFromData(liveData)
              }
              nowElapsedMs.longValue = SystemClock.elapsedRealtime()
              frameVersion.longValue++
            }

            if (targetFps >= 30 || usesMillisSource(storedFace.face)) {
              while (isActive) {
                withFrameNanos { stepSmoothFrame() }
              }
            } else {
              val frameDelayMs = (1000L / targetFps).coerceAtLeast(16L)
              while (isActive) {
                stepSmoothFrame()
                delay(frameDelayMs)
              }
            }
          } else if (renderMode == FaceRenderMode.NORMAL && hasAnyAnim) {
            // Wake briefly only when data/config triggers a finite transition animation, then sleep again.
            transitionWake.collectLatest {
              yield()
              while (isActive && animController.hasActiveAnimation()) {
                val fps = animController.maxActiveFps.coerceIn(1, 60)
                delay((1000L / fps).coerceAtLeast(16L))
                nowElapsedMs.longValue = SystemClock.elapsedRealtime()
                frameVersion.longValue++
              }
            }
          }
        } finally {
          animController.pause(SystemClock.elapsedRealtime())
        }
      }
    }

    WffRenderer.Render(
      face = storedFace.face,
      imagesDir = storedFace.imagesDir,
      fontsDir = storedFace.fontsDir,
      resourceFileMap = storedFace.resourceFileMap,
      fontFileMap = storedFace.fontFileMap,
      evalCtxProvider = {
        frameVersion.longValue
        evalCtx
      },
      animController = animController,
      nowElapsedMsProvider = { nowElapsedMs.longValue },
      mode = renderMode,
      modifier = modifier,
    )
  }

  private fun hasAnyAnimation(face: WffWatchFace): Boolean {
    if (face.sceneTransforms.any { it.animation != null }) return true
    fun scan(node: WffSceneNode): Boolean {
      if (node.transforms.any { it.animation != null }) return true
      return when (node) {
        is WffSceneNode.Group -> node.children.any(::scan)
        is WffSceneNode.Condition ->
          node.branches.any { b -> b.children.any(::scan) } || node.defaultChildren.any(::scan)
        is WffSceneNode.AnalogClock ->
          node.hourHands.any { h -> h.transforms.any { it.animation != null } } ||
            node.minuteHands.any { h -> h.transforms.any { it.animation != null } } ||
            node.secondHands.any { h -> h.transforms.any { it.animation != null } }
        is WffSceneNode.DigitalClock -> node.timeTexts.any { tt -> tt.transforms.any { it.animation != null } }
        is WffSceneNode.PartDraw -> node.shapes.any { s -> s is WffDrawShape.Arc && s.transforms.any { it.animation != null } }
        else -> false
      }
    }
    return face.nodes.any(::scan)
  }

  private fun maxRepeatingAnimFps(face: WffWatchFace): Int {
    var best = 0
    face.sceneTransforms.forEach { tr -> if (tr.animation != null && tr.animation.repeat != 0) best = maxOf(best, tr.animation.fps) }
    fun scan(node: WffSceneNode) {
      node.transforms.forEach { tr -> if (tr.animation != null && tr.animation.repeat != 0) best = maxOf(best, tr.animation.fps) }
      when (node) {
        is WffSceneNode.Group -> node.children.forEach(::scan)
        is WffSceneNode.Condition -> {
          node.branches.forEach { it.children.forEach(::scan) }
          node.defaultChildren.forEach(::scan)
        }
        is WffSceneNode.AnalogClock -> {
          (node.hourHands + node.minuteHands + node.secondHands).forEach { h ->
            h.transforms.forEach { tr -> if (tr.animation != null && tr.animation.repeat != 0) best = maxOf(best, tr.animation.fps) }
          }
        }
        is WffSceneNode.DigitalClock -> node.timeTexts.forEach { tt ->
          tt.transforms.forEach { tr -> if (tr.animation != null && tr.animation.repeat != 0) best = maxOf(best, tr.animation.fps) }
        }
        is WffSceneNode.PartDraw -> node.shapes.forEach { s ->
          if (s is WffDrawShape.Arc) {
            s.transforms.forEach { tr -> if (tr.animation != null && tr.animation.repeat != 0) best = maxOf(best, tr.animation.fps) }
          }
        }
        else -> Unit
      }
    }
    face.nodes.forEach(::scan)
    return best
  }

  private fun maxSweepFrequency(face: WffWatchFace): Int {
    var best = 0
    fun scan(node: WffSceneNode) {
      when (node) {
        is WffSceneNode.Group -> node.children.forEach(::scan)
        is WffSceneNode.Condition -> {
          node.branches.forEach { it.children.forEach(::scan) }
          node.defaultChildren.forEach(::scan)
        }
        is WffSceneNode.AnalogClock -> node.secondHands.forEach { h ->
          val mp = h.motionPolicy
          if (mp is WffMotionPolicy.Sweep) best = maxOf(best, mp.frequency)
        }
        else -> Unit
      }
    }
    face.nodes.forEach(::scan)
    return best
  }

  private fun usesSubSecondTime(face: WffWatchFace): Boolean =
    maxSweepFrequency(face) > 0 || usesMillisSource(face)

  private fun usesMillisSource(face: WffWatchFace): Boolean {
    fun hasMillis(mask: Int) = (mask and WffExpression.DEP_MILLIS) != 0
    if (face.backgroundColorExpr?.let { hasMillis(it.inputMask) } == true) return true
    if (face.sceneTransforms.any { hasMillis(it.valueExpr.inputMask) }) return true
    fun scan(node: WffSceneNode): Boolean {
      if (node.transforms.any { hasMillis(it.valueExpr.inputMask) }) return true
      return when (node) {
        is WffSceneNode.Group -> node.children.any(::scan)
        is WffSceneNode.Condition ->
          node.branches.any { hasMillis(it.expression.inputMask) || it.children.any(::scan) } ||
            node.defaultChildren.any(::scan)
        is WffSceneNode.PartText -> node.parameters.any { hasMillis(it.inputMask) }
        else -> false
      }
    }
    return face.nodes.any(::scan)
  }

  private fun updateTimeInPlace(
    cal: Calendar,
    target: FaceTimeData,
    refreshCalendarDay: Boolean,
  ) {
    val h24 = cal.get(Calendar.HOUR_OF_DAY)
    val h12Raw = cal.get(Calendar.HOUR)
    target.hour12 = if (h12Raw == 0) 12 else h12Raw
    target.hour24 = h24
    target.minute = cal.get(Calendar.MINUTE)
    target.second = cal.get(Calendar.SECOND)
    target.millis = cal.get(Calendar.MILLISECOND)
    target.amPm = if (h24 < 12) "AM" else "PM"
    if (refreshCalendarDay) {
      target.dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
      target.dayNameShort = FaceTimeData.dayNameShortFor(cal)
      target.monthNameShort = FaceTimeData.monthNameShortFor(cal)
      target.year = cal.get(Calendar.YEAR)
      target.monthNumber = cal.get(Calendar.MONTH) + 1
      target.dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    }
  }
}

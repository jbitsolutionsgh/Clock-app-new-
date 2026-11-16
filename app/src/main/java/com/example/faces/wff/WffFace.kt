package com.example.faces.wff

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.FaceData
import com.example.core.FaceDataNeed
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
 * Drives updates strictly from declared needs, runs animations & sensors only while RESUMED,
 * handles ComplicationSlot tap routing, user photos, and prompts runtime step permission only if needed.
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
    val sensorSource = remember { CoreSingletons.sensorSource(context) }

    val allWffConfigs by prefs.wffFaceConfigs.collectAsStateWithLifecycle()
    val savedConfig = allWffConfigs[storedFace.id].orEmpty()

    val allCompProviders by prefs.wffComplicationProviders.collectAsStateWithLifecycle()
    val savedCompRaw = allCompProviders[storedFace.id].orEmpty()
    val activeProviders = remember(storedFace.id, savedCompRaw) {
      buildMap {
        savedCompRaw.forEach { (slotId, provId) ->
          AuraComplicationProvider.fromId(provId)?.let { put(slotId, it) }
        }
      }
    }

    val allPhotos by prefs.wffFacePhotos.collectAsStateWithLifecycle()
    val savedPhotos = allPhotos[storedFace.id].orEmpty()
    val showEventTitles by prefs.standByShowEventTitles.collectAsStateWithLifecycle()

    val photoBitmaps = remember(storedFace.id, savedPhotos) {
      WffPhotoManager.decodeFacePhotos(context, storedFace.id, savedPhotos)
    }

    val needs = remember(storedFace.id, activeProviders) {
      WffExpression.scanDataNeeds(storedFace.face, activeProviders)
    }
    val basePolicy = remember(storedFace.id, renderMode, activeProviders) {
      WffExpression.updatePolicy(storedFace.face, renderMode, hasActiveAnimation = false, activeProviders)
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
        strings = storedFace.face.stringResources,
      ).apply { syncFromData(liveData) }
    }

    val frameVersion = remember(storedFace.id) { mutableLongStateOf(0L) }
    val nowElapsedMs = remember(storedFace.id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val transitionWake = remember(storedFace.id) { MutableSharedFlow<Unit>(extraBufferCapacity = 2) }
    val visibleCount = remember(storedFace.id) { mutableIntStateOf(0) }
    val photoTapCounts = remember(storedFace.id) { mutableStateMapOf<String, Int>() }

    var stepPermissionGranted by remember(storedFace.id) { mutableStateOf(sensorSource.hasStepPermission()) }
    var dismissedStepBanner by remember(storedFace.id) { mutableStateOf(false) }

    val stepPermissionLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
      stepPermissionGranted = granted || sensorSource.hasStepPermission()
      sensorSource.refreshDiagnostics()
    }

    DisposableEffect(lifecycleOwner, storedFace.id) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          visibleCount.intValue += 1
          val perm = sensorSource.hasStepPermission()
          if (perm != stepPermissionGranted) {
            stepPermissionGranted = perm
            sensorSource.refreshDiagnostics()
          }
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(storedFace.id, savedConfig, activeProviders) {
      if (storedFace.face.userConfigs.isNotEmpty()) {
        evalCtx.configRuntime = WffConfigRuntime(storedFace.face.userConfigs, savedConfig)
      }
      evalCtx.configVer++
      evalCtx.compVer++
      nowElapsedMs.longValue = SystemClock.elapsedRealtime()
      frameVersion.longValue++
      transitionWake.tryEmit(Unit)
    }

    LaunchedEffect(lifecycleOwner, storedFace.id, needs, effectivePolicy, renderMode, stepPermissionGranted) {
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

    Box(modifier = modifier.fillMaxSize()) {
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
        complicationProviders = activeProviders,
        photoBitmaps = photoBitmaps,
        photoTapCounts = photoTapCounts,
        visibleCount = visibleCount.intValue,
        showEventTitles = showEventTitles,
        onPhotoTap = { configId ->
          photoTapCounts[configId] = (photoTapCounts[configId] ?: 0) + 1
          frameVersion.longValue++
        },
        onComplicationTap = { _, provider ->
          provider.tapTarget?.let { CoreSingletons.requestTab(it) }
        },
        modifier = Modifier.fillMaxSize(),
      )

      val showStepPrompt = renderMode == FaceRenderMode.NORMAL &&
        FaceDataNeed.STEPS in needs &&
        !stepPermissionGranted &&
        !dismissedStepBanner &&
        sensorSource.hasStepHardware() &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

      if (showStepPrompt) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("wff_step_permission_banner"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "Allow step access for live steps on this face",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
              modifier = Modifier.weight(1f),
            )
            Button(
              onClick = { stepPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
              modifier = Modifier.testTag("wff_allow_steps_button"),
            ) {
              Text("Allow", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
              onClick = { dismissedStepBanner = true },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
              Text("Later", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }
    }
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

  companion object {
    /** Collects all ComplicationSlots anywhere inside [face] scene hierarchy. */
    fun collectComplicationSlots(face: WffWatchFace): List<WffSceneNode.ComplicationSlot> {
      val out = mutableListOf<WffSceneNode.ComplicationSlot>()
      fun walk(node: WffSceneNode) {
        when (node) {
          is WffSceneNode.ComplicationSlot -> out += node
          is WffSceneNode.Group -> node.children.forEach(::walk)
          is WffSceneNode.Condition -> {
            node.branches.forEach { it.children.forEach(::walk) }
            node.defaultChildren.forEach(::walk)
          }
          else -> Unit
        }
      }
      face.nodes.forEach(::walk)
      return out
    }

    /** True when the face exposes options, flavors, complications, or photo slots to customize. */
    fun hasCustomizableOptions(face: WffWatchFace): Boolean =
      face.userConfigs.isNotEmpty() ||
        (face.flavors?.flavors?.isNotEmpty() == true) ||
        collectComplicationSlots(face).any { it.isCustomizable }
  }
}

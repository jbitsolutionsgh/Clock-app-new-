package com.example.faces.wff

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import com.example.core.FaceData
import com.example.core.FaceRenderMode
import com.example.core.FaceTimeData
import java.io.File
import kotlin.math.min

/**
 * High-efficiency Compose Canvas renderer for parsed [WffWatchFace] scenes.
 * Supports Scene/Group Conditions, Variants, animated Transforms, UserConfigurations,
 * custom TTF & BitmapFonts, Text Shadow/Outline, and Image blend modes while keeping
 * evaluation strictly inside the Canvas draw block.
 */
internal object WffRenderer {

  private val sampleThumbnailData = FaceData(
    time = FaceTimeData(
      hour12 = 10,
      hour24 = 10,
      minute = 9,
      second = 30,
      millis = 0,
      is24Hour = false,
      amPm = "AM",
      dayOfMonth = 15,
      dayNameShort = "FRI",
      monthNameShort = "NOV",
    ),
  )

  /** Renders [face] proportionally centered inside the available stage bounds. */
  @Composable
  fun Render(
    face: WffWatchFace,
    imagesDir: File,
    fontsDir: File,
    resourceFileMap: Map<String, String>,
    fontFileMap: Map<String, String>,
    evalCtxProvider: () -> WffEvalContext,
    animController: WffAnimationController,
    nowElapsedMsProvider: () -> Long,
    mode: FaceRenderMode,
    modifier: Modifier = Modifier,
  ) {
    val density = LocalDensity.current
    val scratch = remember(face.id) { WffDrawHelpers.DrawScratch() }
    val customFonts = remember(face.id, fontFileMap) {
      WffDrawHelpers.loadCustomFonts(fontsDir, fontFileMap)
    }

    BoxWithConstraints(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val availW = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
      val availH = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
      val stageScale = min(availW / face.width, availH / face.height)

      val bitmaps = remember(face.id, resourceFileMap, stageScale) {
        WffDrawHelpers.decodeNeededBitmaps(
          face = face,
          imagesDir = imagesDir,
          resourceFileMap = resourceFileMap,
          stageScale = stageScale,
        )
      }

      Canvas(modifier = Modifier.fillMaxSize()) {
        val evalCtx = evalCtxProvider()
        val nowMs = nowElapsedMsProvider()
        drawWatchFace(
          face = face,
          bitmaps = bitmaps,
          customFonts = customFonts,
          evalCtx = evalCtx,
          animController = animController,
          nowElapsedMs = nowMs,
          mode = mode,
          scratch = scratch,
        )
      }
    }
  }

  /** Renders a lightweight downsampled static preview of [face] for the watch face picker. */
  @Composable
  fun Thumbnail(
    face: WffWatchFace,
    imagesDir: File,
    fontsDir: File? = null,
    resourceFileMap: Map<String, String>,
    fontFileMap: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
  ) {
    val density = LocalDensity.current
    val scratch = remember(face.id) { WffDrawHelpers.DrawScratch() }
    val customFonts = remember(face.id, fontFileMap) {
      WffDrawHelpers.loadCustomFonts(fontsDir, fontFileMap)
    }
    val thumbCtx = remember(face.id) {
      WffEvalContext(
        data = sampleThumbnailData,
        configRuntime = if (face.userConfigs.isNotEmpty()) WffConfigRuntime(face.userConfigs, emptyMap()) else null,
      )
    }
    val thumbAnim = remember(face.id) { WffAnimationController() }

    BoxWithConstraints(
      modifier = modifier,
      contentAlignment = Alignment.Center,
    ) {
      val sideDp = minOf(maxWidth, maxHeight)
      val maxPx = with(density) { sideDp.toPx().coerceIn(48f, 160f) }
      val stageScale = min(maxPx / face.width, maxPx / face.height)

      val bitmaps = remember(face.id, resourceFileMap, stageScale) {
        WffDrawHelpers.decodeNeededBitmaps(
          face = face,
          imagesDir = imagesDir,
          resourceFileMap = resourceFileMap,
          stageScale = stageScale,
        )
      }

      Box(modifier = Modifier.size(sideDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          drawWatchFace(
            face = face,
            bitmaps = bitmaps,
            customFonts = customFonts,
            evalCtx = thumbCtx,
            animController = thumbAnim,
            nowElapsedMs = 0L,
            mode = FaceRenderMode.NORMAL,
            scratch = scratch,
          )
        }
      }
    }
  }

  private fun DrawScope.drawWatchFace(
    face: WffWatchFace,
    bitmaps: Map<String, Bitmap>,
    customFonts: Map<String, Typeface>,
    evalCtx: WffEvalContext,
    animController: WffAnimationController,
    nowElapsedMs: Long,
    mode: FaceRenderMode,
    scratch: WffDrawHelpers.DrawScratch,
  ) {
    val scale = min(size.width / face.width, size.height / face.height)
    val offsetX = (size.width - face.width * scale) * 0.5f
    val offsetY = (size.height - face.height * scale) * 0.5f
    val isAmbient = mode == FaceRenderMode.AMBIENT

    val canvas = drawContext.canvas.nativeCanvas
    val rootSave = canvas.save()
    canvas.translate(offsetX, offsetY)
    canvas.scale(scale, scale)

    when (face.clipShape) {
      WffClipShape.CIRCLE -> {
        scratch.path.rewind()
        scratch.rectF.set(0f, 0f, face.width, face.height)
        scratch.path.addOval(scratch.rectF, Path.Direction.CW)
        canvas.clipPath(scratch.path)
      }
      WffClipShape.RECTANGLE -> {
        scratch.path.rewind()
        scratch.rectF.set(0f, 0f, face.width, face.height)
        scratch.path.addRoundRect(scratch.rectF, face.cornerRadiusX, face.cornerRadiusY, Path.Direction.CW)
        canvas.clipPath(scratch.path)
      }
      WffClipShape.NONE -> Unit
    }

    var bgColor = face.backgroundColorExpr?.let {
      scratch.evalCached(it, evalCtx).asColorInt(face.backgroundColor)
    } ?: face.backgroundColor

    var sceneAlpha = 255
    var sceneAngle = 0f
    var sceneScaleX = 1f
    var sceneScaleY = 1f
    var sceneX = 0f
    var sceneY = 0f

    if (isAmbient) {
      for (v in face.sceneVariants) {
        if (!v.mode.equals("AMBIENT", ignoreCase = true)) continue
        when (v.target) {
          "backgroundColor" -> bgColor = WffXmlPartParsers.parseColor(v.value, bgColor)
          "alpha" -> sceneAlpha = v.value.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: sceneAlpha
          "angle" -> sceneAngle = v.value.toFloatOrNull() ?: sceneAngle
        }
      }
    }
    for (tr in face.sceneTransforms) {
      val v = animController.sample(tr, scratch.evalCached(tr.valueExpr, evalCtx).asFloat(), nowElapsedMs, isAmbient)
      when (tr.target) {
        "backgroundColor" -> bgColor = scratch.evalCached(tr.valueExpr, evalCtx).asColorInt(bgColor)
        "alpha" -> sceneAlpha = v.toInt().coerceIn(0, 255)
        "angle" -> sceneAngle = v
        "scaleX" -> sceneScaleX = v
        "scaleY" -> sceneScaleY = v
        "x" -> sceneX = v
        "y" -> sceneY = v
      }
    }

    scratch.shapePaint.style = Paint.Style.FILL
    scratch.shapePaint.color = bgColor
    scratch.rectF.set(0f, 0f, face.width, face.height)
    canvas.drawRect(scratch.rectF, scratch.shapePaint)

    if (sceneAlpha > 0) {
      if (sceneX != 0f || sceneY != 0f || sceneAngle != 0f || sceneScaleX != 1f || sceneScaleY != 1f) {
        canvas.translate(sceneX, sceneY)
        if (sceneAngle != 0f) canvas.rotate(sceneAngle, face.width * 0.5f, face.height * 0.5f)
        if (sceneScaleX != 1f || sceneScaleY != 1f) canvas.scale(sceneScaleX, sceneScaleY, face.width * 0.5f, face.height * 0.5f)
      }
      for (node in face.nodes) {
        drawNode(canvas, face, node, bitmaps, customFonts, evalCtx, animController, nowElapsedMs, mode, scratch, sceneAlpha)
      }
    }

    canvas.restoreToCount(rootSave)
  }

  private fun drawNode(
    canvas: android.graphics.Canvas,
    face: WffWatchFace,
    node: WffSceneNode,
    bitmaps: Map<String, Bitmap>,
    customFonts: Map<String, Typeface>,
    evalCtx: WffEvalContext,
    animController: WffAnimationController,
    nowElapsedMs: Long,
    mode: FaceRenderMode,
    scratch: WffDrawHelpers.DrawScratch,
    parentAlpha: Int,
  ) {
    val isAmbient = mode == FaceRenderMode.AMBIENT

    if (node is WffSceneNode.Condition) {
      var matchedChildren: List<WffSceneNode>? = null
      for (branch in node.branches) {
        if (scratch.evalCached(branch.expression, evalCtx).asBoolean()) {
          matchedChildren = branch.children
          break
        }
      }
      val toDraw = matchedChildren ?: node.defaultChildren
      for (child in toDraw) {
        drawNode(canvas, face, child, bitmaps, customFonts, evalCtx, animController, nowElapsedMs, mode, scratch, parentAlpha)
      }
      return
    }

    var x = node.x
    var y = node.y
    var width = node.width
    var height = node.height
    var pivotX = node.pivotX
    var pivotY = node.pivotY
    var angle = node.angle
    var scaleX = node.scaleX
    var scaleY = node.scaleY
    var alpha = node.alpha
    var visible = node.visible
    var colorOverride: Int? = null

    if (isAmbient) {
      for (v in node.variants) {
        if (!v.mode.equals("AMBIENT", ignoreCase = true)) continue
        when (v.target) {
          "alpha" -> alpha = v.value.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: alpha
          "x" -> x = v.value.toFloatOrNull() ?: x
          "y" -> y = v.value.toFloatOrNull() ?: y
          "width" -> width = v.value.toFloatOrNull() ?: width
          "height" -> height = v.value.toFloatOrNull() ?: height
          "angle" -> angle = v.value.toFloatOrNull() ?: angle
          "scaleX" -> scaleX = v.value.toFloatOrNull() ?: scaleX
          "scaleY" -> scaleY = v.value.toFloatOrNull() ?: scaleY
          "pivotX" -> pivotX = v.value.toFloatOrNull() ?: pivotX
          "pivotY" -> pivotY = v.value.toFloatOrNull() ?: pivotY
          "visible" -> visible = !v.value.equals("false", ignoreCase = true)
          "visibility" -> visible = !v.value.equals("INVISIBLE", ignoreCase = true) && !v.value.equals("GONE", ignoreCase = true)
          "color", "tintColor" -> colorOverride = WffXmlPartParsers.parseColorNullable(v.value) ?: colorOverride
        }
      }
    }

    for (tr in node.transforms) {
      val eval = scratch.evalCached(tr.valueExpr, evalCtx)
      val raw = eval.asFloat()
      val v = animController.sample(tr, raw, nowElapsedMs, isAmbient)
      when (tr.target) {
        "x" -> x = v
        "y" -> y = v
        "width" -> width = v
        "height" -> height = v
        "angle" -> angle = v
        "scaleX" -> scaleX = v
        "scaleY" -> scaleY = v
        "pivotX" -> pivotX = v
        "pivotY" -> pivotY = v
        "alpha" -> alpha = v.toInt().coerceIn(0, 255)
        "visible", "visibility" -> visible = eval.asBoolean()
        "color", "tintColor" -> colorOverride = eval.asColorInt(colorOverride ?: 0)
      }
    }

    val effectiveAlpha = (alpha * parentAlpha) / 255
    if (!visible || effectiveAlpha <= 0) return

    val save = canvas.save()
    val px = x + width * pivotX
    val py = y + height * pivotY
    if (angle != 0f) canvas.rotate(angle, px, py)
    if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY, px, py)
    canvas.translate(x, y)

    when (node) {
      is WffSceneNode.Group -> {
        for (child in node.children) {
          drawNode(canvas, face, child, bitmaps, customFonts, evalCtx, animController, nowElapsedMs, mode, scratch, effectiveAlpha)
        }
      }
      is WffSceneNode.Condition -> Unit
      is WffSceneNode.AnalogClock -> {
        for (hand in node.hourHands) {
          drawHand(canvas, hand, bitmaps, evalCtx, animController, nowElapsedMs, mode, scratch, effectiveAlpha)
        }
        for (hand in node.minuteHands) {
          drawHand(canvas, hand, bitmaps, evalCtx, animController, nowElapsedMs, mode, scratch, effectiveAlpha)
        }
        if (!isAmbient) {
          for (hand in node.secondHands) {
            drawHand(canvas, hand, bitmaps, evalCtx, animController, nowElapsedMs, mode, scratch, effectiveAlpha)
          }
        }
      }
      is WffSceneNode.DigitalClock -> {
        for (tt in node.timeTexts) {
          drawTimeText(canvas, face, tt, bitmaps, customFonts, evalCtx, animController, nowElapsedMs, mode, scratch, effectiveAlpha)
        }
      }
      is WffSceneNode.PartImage -> drawPartImage(canvas, node, width, height, bitmaps, evalCtx, nowElapsedMs, isAmbient, colorOverride, scratch, effectiveAlpha)
      is WffSceneNode.PartText -> drawPartText(canvas, face, node, width, height, bitmaps, customFonts, evalCtx, colorOverride, mode, scratch, effectiveAlpha)
      is WffSceneNode.PartDraw -> {
        for (shape in node.shapes) {
          WffDrawHelpers.drawShape(canvas, shape, evalCtx, animController, nowElapsedMs, isAmbient, scratch, effectiveAlpha)
        }
      }
    }

    canvas.restoreToCount(save)
  }

  private fun drawPartImage(
    canvas: android.graphics.Canvas,
    node: WffSceneNode.PartImage,
    width: Float,
    height: Float,
    bitmaps: Map<String, Bitmap>,
    evalCtx: WffEvalContext,
    nowElapsedMs: Long,
    isAmbient: Boolean,
    colorOverride: Int?,
    scratch: WffDrawHelpers.DrawScratch,
    effectiveAlpha: Int,
  ) {
    if (node.resources.isEmpty()) return
    val resKey = if (node.resources.size == 1 || isAmbient) {
      node.resources.first()
    } else {
      val idx = ((nowElapsedMs / 100L) % node.resources.size).toInt().coerceIn(0, node.resources.lastIndex)
      node.resources[idx]
    }
    val bmp = bitmaps[resKey] ?: return

    val resolvedTint = colorOverride ?: node.tintColorExpr?.let {
      scratch.evalCached(it, evalCtx).asColorInt(node.tintColor ?: 0)
    } ?: node.tintColor

    scratch.destRectF.set(0f, 0f, width, height)
    scratch.bitmapPaint.alpha = effectiveAlpha
    scratch.bitmapPaint.colorFilter = resolvedTint?.let { scratch.colorFilterFor(it) }

    if (node.blendMode != WffBlendMode.SRC_OVER) {
      val layerSave = canvas.saveLayer(scratch.destRectF, null)
      scratch.bitmapPaint.xfermode = scratch.xfermodeFor(node.blendMode)
      canvas.drawBitmap(bmp, null, scratch.destRectF, scratch.bitmapPaint)
      scratch.bitmapPaint.xfermode = null
      canvas.restoreToCount(layerSave)
    } else {
      canvas.drawBitmap(bmp, null, scratch.destRectF, scratch.bitmapPaint)
    }
    scratch.bitmapPaint.colorFilter = null
  }

  private fun drawHand(
    canvas: android.graphics.Canvas,
    hand: WffHandNode,
    bitmaps: Map<String, Bitmap>,
    evalCtx: WffEvalContext,
    animController: WffAnimationController,
    nowElapsedMs: Long,
    mode: FaceRenderMode,
    scratch: WffDrawHelpers.DrawScratch,
    parentAlpha: Int,
  ) {
    val bmp = bitmaps[hand.resource] ?: return
    val isAmbient = mode == FaceRenderMode.AMBIENT
    var x = hand.x
    var y = hand.y
    var w = hand.width
    var h = hand.height
    var scaleX = hand.scaleX
    var scaleY = hand.scaleY
    var alpha = hand.alpha
    var tint = hand.tintColorExpr?.let { scratch.evalCached(it, evalCtx).asColorInt(hand.tintColor ?: 0) } ?: hand.tintColor
    var customAngle: Float? = null

    if (isAmbient) {
      for (v in hand.variants) {
        if (!v.mode.equals("AMBIENT", ignoreCase = true)) continue
        when (v.target) {
          "alpha" -> alpha = v.value.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: alpha
          "x" -> x = v.value.toFloatOrNull() ?: x
          "y" -> y = v.value.toFloatOrNull() ?: y
          "tintColor", "color" -> tint = WffXmlPartParsers.parseColorNullable(v.value) ?: tint
        }
      }
    }

    for (tr in hand.transforms) {
      val eval = scratch.evalCached(tr.valueExpr, evalCtx)
      val v = animController.sample(tr, eval.asFloat(), nowElapsedMs, isAmbient)
      when (tr.target) {
        "x" -> x = v
        "y" -> y = v
        "width" -> w = v
        "height" -> h = v
        "scaleX" -> scaleX = v
        "scaleY" -> scaleY = v
        "alpha" -> alpha = v.toInt().coerceIn(0, 255)
        "angle" -> customAngle = v
        "tintColor", "color" -> tint = eval.asColorInt(tint ?: 0)
      }
    }

    val effectiveAlpha = (alpha * parentAlpha) / 255
    if (effectiveAlpha <= 0) return

    val t = evalCtx.data.time
    val angleDeg = customAngle ?: when (hand.role) {
      WffHandRole.HOUR -> ((t.hour24 % 12) + t.minute / 60f + t.second / 3600f) * 30f
      WffHandRole.MINUTE -> (t.minute + t.second / 60f) * 6f
      WffHandRole.SECOND -> {
        val secFraction = if (hand.motionPolicy is WffMotionPolicy.Sweep) t.second + t.millis / 1000f else t.second.toFloat()
        secFraction * 6f
      }
    }

    val save = canvas.save()
    val px = x + w * hand.pivotX
    val py = y + h * hand.pivotY
    canvas.rotate(angleDeg, px, py)
    if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY, px, py)
    scratch.destRectF.set(x, y, x + w, y + h)
    scratch.bitmapPaint.alpha = effectiveAlpha
    scratch.bitmapPaint.colorFilter = tint?.let { scratch.colorFilterFor(it) }
    canvas.drawBitmap(bmp, null, scratch.destRectF, scratch.bitmapPaint)
    scratch.bitmapPaint.colorFilter = null
    canvas.restoreToCount(save)
  }

  private fun drawTimeText(
    canvas: android.graphics.Canvas,
    face: WffWatchFace,
    node: WffTimeTextNode,
    bitmaps: Map<String, Bitmap>,
    customFonts: Map<String, Typeface>,
    evalCtx: WffEvalContext,
    animController: WffAnimationController,
    nowElapsedMs: Long,
    mode: FaceRenderMode,
    scratch: WffDrawHelpers.DrawScratch,
    parentAlpha: Int,
  ) {
    val isAmbient = mode == FaceRenderMode.AMBIENT
    var x = node.x
    var y = node.y
    val w = node.width
    val h = node.height
    var alpha = node.alpha
    var color = node.colorExpr?.let { scratch.evalCached(it, evalCtx).asColorInt(node.color) } ?: node.color

    if (isAmbient) {
      for (v in node.variants) {
        if (!v.mode.equals("AMBIENT", ignoreCase = true)) continue
        when (v.target) {
          "alpha" -> alpha = v.value.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: alpha
          "color" -> color = WffXmlPartParsers.parseColor(v.value, color)
        }
      }
    }

    for (tr in node.transforms) {
      val eval = scratch.evalCached(tr.valueExpr, evalCtx)
      val v = animController.sample(tr, eval.asFloat(), nowElapsedMs, isAmbient)
      when (tr.target) {
        "x" -> x = v
        "y" -> y = v
        "alpha" -> alpha = v.toInt().coerceIn(0, 255)
        "color" -> color = eval.asColorInt(color)
      }
    }

    val effectiveAlpha = (alpha * parentAlpha) / 255
    if (effectiveAlpha <= 0) return

    WffExpression.formatTimeText(node.format, node.hourFormat, evalCtx.data, isAmbient, scratch.textBuilder)
    if (scratch.textBuilder.isEmpty()) return

    val bmFont = node.bitmapFontName?.let { face.bitmapFonts[it] }
    if (bmFont != null) {
      WffDrawHelpers.drawBitmapFontText(canvas, scratch.textBuilder, bmFont, bitmaps, x, y, w, h, node.align, node.fontSize, effectiveAlpha, scratch)
    } else {
      WffDrawHelpers.drawStyledText(
        canvas, scratch.textBuilder, x, y, w, h, node.align, node.fontSize, node.fontFamily,
        node.weight, node.slant, color, effectiveAlpha, node.shadow, node.outline, customFonts, scratch,
      )
    }
  }

  private fun drawPartText(
    canvas: android.graphics.Canvas,
    face: WffWatchFace,
    node: WffSceneNode.PartText,
    width: Float,
    height: Float,
    bitmaps: Map<String, Bitmap>,
    customFonts: Map<String, Typeface>,
    evalCtx: WffEvalContext,
    colorOverride: Int?,
    mode: FaceRenderMode,
    scratch: WffDrawHelpers.DrawScratch,
    effectiveAlpha: Int,
  ) {
    if (mode == FaceRenderMode.AMBIENT && node.variants.none { it.mode.equals("AMBIENT", ignoreCase = true) }) {
      val usesSeconds = node.parameters.any {
        (it.inputMask and (WffExpression.DEP_SECOND or WffExpression.DEP_MILLIS)) != 0
      }
      if (usesSeconds) return
    }

    val color = colorOverride ?: node.colorExpr?.let {
      scratch.evalCached(it, evalCtx).asColorInt(node.color)
    } ?: node.color

    scratch.textBuilder.setLength(0)
    if (node.template != null) {
      WffExpression.formatTemplate(node.template, node.parameters, evalCtx, scratch.textBuilder)
    } else if (node.rawText != null) {
      scratch.textBuilder.append(node.rawText)
    }
    if (scratch.textBuilder.isEmpty()) return

    val bmFont = node.bitmapFontName?.let { face.bitmapFonts[it] }
    if (bmFont != null) {
      WffDrawHelpers.drawBitmapFontText(canvas, scratch.textBuilder, bmFont, bitmaps, 0f, 0f, width, height, node.align, node.fontSize, effectiveAlpha, scratch)
    } else {
      WffDrawHelpers.drawStyledText(
        canvas, scratch.textBuilder, 0f, 0f, width, height, node.align, node.fontSize, node.fontFamily,
        node.weight, node.slant, color, effectiveAlpha, node.shadow, node.outline, customFonts, scratch,
      )
    }
  }
}

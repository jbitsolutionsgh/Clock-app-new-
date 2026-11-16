package com.example.faces.engine

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.core.FaceData
import com.example.core.FaceRenderMode
import com.example.core.FaceTimeData
import com.example.faces.DialThumbnailCache
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Data-driven watch face renderer.
 * Draws all 7 layer types relative to R (half the dial side) from bottom to top while reusing
 * Paint/Typeface objects and decoding dial images at the exact pixel size they are drawn.
 */
internal object FaceEngine {

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

  /**
   * Renders [definition] inside a 94% shorter-side stage reading [dataProvider] strictly inside the
   * Canvas draw block so smooth frame ticks redraw the canvas without recomposing the layout.
   */
  @Composable
  fun Render(
    definition: FaceDefinition,
    dataProvider: () -> FaceData,
    mode: FaceRenderMode,
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme

    val imageDrawableNames = remember(definition) {
      definition.layers
        .filterIsInstance<ImageLayer>()
        .map { it.drawableName }
        .distinct()
    }

    val typefaces = rememberTypefaces(context)
    val textPaint = remember { Paint().apply { isAntiAlias = true } }

    BoxWithConstraints(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val shorterSide = minOf(maxWidth, maxHeight)
      val dialSide = shorterSide * 0.94f
      val dialSidePx = with(density) { dialSide.roundToPx().coerceAtLeast(1) }

      // Decode dial images no larger than the drawn dial's pixel size; GC manages old bitmaps safely.
      val decodedBitmaps = remember(imageDrawableNames, dialSidePx) {
        val map = HashMap<String, ImageBitmap>(imageDrawableNames.size)
        for (name in imageDrawableNames) {
          val resId = resolveDrawableId(context, name)
          if (resId != 0) {
            val bmp = decodeBitmapForDialSize(context.resources, resId, dialSidePx)
            if (bmp != null) {
              map[name] = bmp.asImageBitmap()
            }
          }
        }
        map
      }

      Box(
        modifier = Modifier.size(dialSide),
        contentAlignment = Alignment.Center,
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val data = dataProvider()
          drawFaceLayers(
            definition = definition,
            data = data,
            mode = mode,
            colorScheme = colorScheme,
            bitmaps = decodedBitmaps,
            typefaces = typefaces,
            textPaint = textPaint,
          )
        }
      }
    }
  }

  /**
   * Renders a lightweight picker thumbnail for [definition]: uses the <= 160px cached LRU thumbnail
   * for faces with an image layer, or draws a static vector miniature on Canvas for code-defined faces.
   */
  @Composable
  fun Thumbnail(
    definition: FaceDefinition,
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val firstImageName = remember(definition) {
      definition.layers.filterIsInstance<ImageLayer>().firstOrNull()?.drawableName
    }

    if (firstImageName != null) {
      val resId = remember(firstImageName) { resolveDrawableId(context, firstImageName) }
      val thumb = remember(resId) {
        if (resId != 0) DialThumbnailCache.getOrDecode(context.resources, resId) else null
      }
      if (thumb != null) {
        Image(
          bitmap = thumb,
          contentDescription = "${definition.name} preview",
          contentScale = ContentScale.Fit,
          modifier = modifier,
        )
        return
      }
    }

    val typefaces = rememberTypefaces(context)
    val textPaint = remember { Paint().apply { isAntiAlias = true } }
    Canvas(modifier = modifier) {
      drawFaceLayers(
        definition = definition,
        data = sampleThumbnailData,
        mode = FaceRenderMode.NORMAL,
        colorScheme = colorScheme,
        bitmaps = emptyMap(),
        typefaces = typefaces,
        textPaint = textPaint,
      )
    }
  }

  private fun DrawScope.drawFaceLayers(
    definition: FaceDefinition,
    data: FaceData,
    mode: FaceRenderMode,
    colorScheme: ColorScheme,
    bitmaps: Map<String, ImageBitmap>,
    typefaces: ResolvedTypefaces,
    textPaint: Paint,
  ) {
    val r = size.minDimension / 2f
    if (r <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)

    for (layer in definition.layers) {
      if (!definition.isLayerActiveInMode(layer, mode)) continue
      if (!FaceMath.isConditionMet(layer.showWhen, data, mode)) continue
      if (layer.alpha <= 0.001f) continue

      when (layer) {
        is ImageLayer -> drawImageLayer(layer, bitmaps)
        is ShapeLayer -> drawShapeLayer(layer, center, r, colorScheme)
        is TicksLayer -> drawTicksLayer(layer, center, r, colorScheme)
        is GaugeLayer -> drawGaugeLayer(layer, data, center, r, colorScheme)
        is SlotLayer -> drawSlotLayer(layer, data, center, r, colorScheme, typefaces, textPaint)
        is TextLayer -> drawTextLayer(layer, data, center, r, colorScheme, typefaces, textPaint)
        is HandLayer -> drawHandLayer(layer, data, mode, center, r, colorScheme)
      }
    }
  }

  private fun DrawScope.drawImageLayer(
    layer: ImageLayer,
    bitmaps: Map<String, ImageBitmap>,
  ) {
    val img = bitmaps[layer.drawableName] ?: return
    if (img.width <= 0 || img.height <= 0) return

    val scale = minOf(size.width / img.width.toFloat(), size.height / img.height.toFloat())
    val dstW = (img.width * scale).roundToInt().coerceAtLeast(1)
    val dstH = (img.height * scale).roundToInt().coerceAtLeast(1)
    val offX = ((size.width - dstW) / 2f).roundToInt()
    val offY = ((size.height - dstH) / 2f).roundToInt()
    drawImage(
      image = img,
      dstOffset = IntOffset(offX, offY),
      dstSize = IntSize(dstW, dstH),
      alpha = layer.alpha.coerceIn(0f, 1f),
    )
  }

  private fun DrawScope.drawShapeLayer(
    layer: ShapeLayer,
    center: Offset,
    r: Float,
    colorScheme: ColorScheme,
  ) {
    val pos = Offset(center.x + layer.x * r, center.y + layer.y * r)
    val fill = layer.fillColor?.let { FaceColorResolver.resolve(it, colorScheme, layer.alpha) }
    val stroke = layer.strokeColor?.let { FaceColorResolver.resolve(it, colorScheme, layer.alpha) }
    val strokePx = layer.strokeWidthDp.dp.toPx()

    when (layer.kind) {
      ShapeKind.CIRCLE -> {
        val radPx = resolveRadiusPx(layer, r)
        if (radPx <= 0f) return
        if (fill != null) {
          drawCircle(color = fill, radius = radPx, center = pos)
        }
        if (stroke != null && strokePx > 0f) {
          drawCircle(color = stroke, radius = radPx, center = pos, style = Stroke(width = strokePx))
        }
      }

      ShapeKind.RING -> {
        val radPx = resolveRadiusPx(layer, r)
        if (radPx <= 0f) return
        val ringColor = stroke ?: fill ?: return
        val w = if (strokePx > 0f) strokePx else 2.dp.toPx()
        drawCircle(color = ringColor, radius = radPx, center = pos, style = Stroke(width = w))
      }

      ShapeKind.RECT, ShapeKind.ROUNDED_RECT -> {
        val wPx = (layer.width ?: 1.8f) * r
        val hPx = (layer.height ?: 1.8f) * r
        val topLeft = Offset(pos.x - wPx / 2f, pos.y - hPx / 2f)
        val rectSize = Size(wPx, hPx)
        val crPx = if (layer.kind == ShapeKind.ROUNDED_RECT) layer.cornerRadius * r else 0f

        if (fill != null) {
          if (crPx > 0f) {
            drawRoundRect(color = fill, topLeft = topLeft, size = rectSize, cornerRadius = CornerRadius(crPx, crPx))
          } else {
            drawRect(color = fill, topLeft = topLeft, size = rectSize)
          }
        }
        if (stroke != null && strokePx > 0f) {
          if (crPx > 0f) {
            drawRoundRect(
              color = stroke,
              topLeft = topLeft,
              size = rectSize,
              cornerRadius = CornerRadius(crPx, crPx),
              style = Stroke(width = strokePx),
            )
          } else {
            drawRect(color = stroke, topLeft = topLeft, size = rectSize, style = Stroke(width = strokePx))
          }
        }
      }
    }
  }

  private fun DrawScope.drawTicksLayer(
    layer: TicksLayer,
    center: Offset,
    r: Float,
    colorScheme: ColorScheme,
  ) {
    val baseColor = FaceColorResolver.resolve(layer.color, colorScheme, layer.alpha)
    val majorColor = layer.majorColor?.let { FaceColorResolver.resolve(it, colorScheme, layer.alpha) } ?: baseColor
    val baseWidthPx = layer.widthDp.dp.toPx()
    val majorWidthPx = (layer.majorWidthDp ?: layer.widthDp).dp.toPx()
    val stepDeg = 360f / layer.count.toFloat()

    for (i in 0 until layer.count) {
      val isMajor = layer.majorEvery > 0 && (i % layer.majorEvery == 0)
      val inRad = (if (isMajor) (layer.majorInnerRadius ?: layer.innerRadius) else layer.innerRadius) * r
      val outRad = (if (isMajor) (layer.majorOuterRadius ?: layer.outerRadius) else layer.outerRadius) * r
      val angleRad = (i * stepDeg - 90f) * (PI / 180.0)
      val dx = cos(angleRad).toFloat()
      val dy = sin(angleRad).toFloat()
      drawLine(
        color = if (isMajor) majorColor else baseColor,
        start = Offset(center.x + dx * inRad, center.y + dy * inRad),
        end = Offset(center.x + dx * outRad, center.y + dy * outRad),
        strokeWidth = if (isMajor) majorWidthPx else baseWidthPx,
        cap = layer.cap,
      )
    }
  }

  private fun DrawScope.drawGaugeLayer(
    layer: GaugeLayer,
    data: FaceData,
    center: Offset,
    r: Float,
    colorScheme: ColorScheme,
  ) {
    val cx = center.x + layer.x * r
    val cy = center.y + layer.y * r
    val radPx = layer.radius * r
    if (radPx <= 0f) return
    val topLeft = Offset(cx - radPx, cy - radPx)
    val arcSize = Size(radPx * 2f, radPx * 2f)
    val strokePx = layer.thicknessDp.dp.toPx()
    val style = Stroke(width = strokePx, cap = layer.cap)

    layer.trackColor?.let { trackSpec ->
      drawArc(
        color = FaceColorResolver.resolve(trackSpec, colorScheme, layer.alpha),
        startAngle = layer.startAngle,
        sweepAngle = layer.sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = style,
      )
    }

    val fraction = FaceMath.gaugeFraction(layer.source, data)
    val activeSweep = layer.sweepAngle * fraction
    if (kotlin.math.abs(activeSweep) > 0.1f) {
      drawArc(
        color = FaceColorResolver.resolve(layer.fillColor, colorScheme, layer.alpha),
        startAngle = layer.startAngle,
        sweepAngle = activeSweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = style,
      )
    }
  }

  private fun DrawScope.drawSlotLayer(
    layer: SlotLayer,
    data: FaceData,
    center: Offset,
    r: Float,
    colorScheme: ColorScheme,
    typefaces: ResolvedTypefaces,
    textPaint: Paint,
  ) {
    val cx = center.x + layer.x * r
    val cy = center.y + layer.y * r
    val wPx = layer.width * r
    val hPx = layer.height * r
    val topLeft = Offset(cx - wPx / 2f, cy - hPx / 2f)
    val rectSize = Size(wPx, hPx)
    val crPx = layer.cornerRadius * r

    layer.fillColor?.let { fillSpec ->
      drawRoundRect(
        color = FaceColorResolver.resolve(fillSpec, colorScheme, layer.alpha),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = CornerRadius(crPx, crPx),
      )
    }
    layer.strokeColor?.let { strokeSpec ->
      val swPx = layer.strokeWidthDp.dp.toPx()
      if (swPx > 0f) {
        drawRoundRect(
          color = FaceColorResolver.resolve(strokeSpec, colorScheme, layer.alpha),
          topLeft = topLeft,
          size = rectSize,
          cornerRadius = CornerRadius(crPx, crPx),
          style = Stroke(width = swPx),
        )
      }
    }

    val resolvedText = FaceMath.formatTemplate(layer.template, data)
    if (resolvedText.isEmpty()) return
    val textColor = FaceColorResolver.resolve(layer.textColor, colorScheme, layer.alpha)
    drawAlignedText(
      text = resolvedText,
      anchorX = when (layer.align) {
        TextAlign.LEFT -> cx - wPx * 0.42f
        TextAlign.CENTER -> cx
        TextAlign.RIGHT -> cx + wPx * 0.42f
      },
      anchorY = cy,
      textSizePx = layer.textSize * r,
      color = textColor,
      weight = layer.weight,
      align = layer.align,
      font = layer.font,
      letterSpacing = layer.letterSpacing,
      typefaces = typefaces,
      textPaint = textPaint,
    )
  }

  private fun DrawScope.drawTextLayer(
    layer: TextLayer,
    data: FaceData,
    center: Offset,
    r: Float,
    colorScheme: ColorScheme,
    typefaces: ResolvedTypefaces,
    textPaint: Paint,
  ) {
    val resolvedText = FaceMath.formatTemplate(layer.template, data)
    if (resolvedText.isEmpty()) return
    val color = FaceColorResolver.resolve(layer.color, colorScheme, layer.alpha)
    drawAlignedText(
      text = resolvedText,
      anchorX = center.x + layer.x * r,
      anchorY = center.y + layer.y * r,
      textSizePx = layer.size * r,
      color = color,
      weight = layer.weight,
      align = layer.align,
      font = layer.font,
      letterSpacing = layer.letterSpacing,
      typefaces = typefaces,
      textPaint = textPaint,
    )
  }

  private fun DrawScope.drawHandLayer(
    layer: HandLayer,
    data: FaceData,
    mode: FaceRenderMode,
    center: Offset,
    r: Float,
    colorScheme: ColorScheme,
  ) {
    val pivot = Offset(center.x + layer.pivotX * r, center.y + layer.pivotY * r)
    val angleDeg = FaceMath.handAngleDegrees(layer, data, mode)
    val rad = (angleDeg - 90f) * (PI / 180.0)
    val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())

    val tipPx = layer.length * r
    val tailPx = when {
      layer.tail != null -> layer.tail * r
      layer.tailDp != null -> layer.tailDp.dp.toPx()
      else -> 0f
    }
    val widthPx = layer.widthDp.dp.toPx()
    val start = pivot - dir * tailPx
    val end = pivot + dir * tipPx
    val shadowOffset = Offset(2.dp.toPx(), 2.dp.toPx())
    val shadowColor = Color.Black.copy(alpha = (layer.shadowAlpha * layer.alpha).coerceIn(0f, 1f))
    val bodyColor = FaceColorResolver.resolve(layer.bodyColor, colorScheme, layer.alpha)

    if (layer.shadow) {
      drawLine(
        color = shadowColor,
        start = start + shadowOffset,
        end = end + shadowOffset,
        strokeWidth = widthPx,
        cap = layer.cap,
      )
    }

    drawLine(
      color = bodyColor,
      start = start,
      end = end,
      strokeWidth = widthPx,
      cap = layer.cap,
    )

    val stripeSpec = layer.stripeColor
    if (stripeSpec != null && layer.stripeWidthDp > 0f) {
      val stripeColor = FaceColorResolver.resolve(stripeSpec, colorScheme, layer.alpha)
      val stripeWidthPx = layer.stripeWidthDp.dp.toPx()
      val stripeStart = if (layer.stripeTipOnly) pivot + dir * (tipPx * 0.62f) else start
      val insetPx = if (layer.stripeTipOnly) 2.dp.toPx() else 4.dp.toPx()
      val stripeEnd = pivot + dir * (tipPx - insetPx).coerceAtLeast(0f)
      drawLine(
        color = stripeColor,
        start = stripeStart,
        end = stripeEnd,
        strokeWidth = stripeWidthPx,
        cap = layer.cap,
      )
    }

    layer.counterweight?.let { cw ->
      val cwCenter = when {
        cw.positionRatio != null -> pivot + dir * (tipPx * cw.positionRatio)
        cw.tailOffsetDp != null -> pivot - dir * cw.tailOffsetDp.dp.toPx()
        else -> pivot - dir * (tailPx * 0.72f)
      }
      val cwRadiusPx = cw.radiusDp.dp.toPx()
      if (layer.shadow) {
        drawCircle(color = shadowColor, radius = cwRadiusPx, center = cwCenter + shadowOffset)
      }
      drawCircle(
        color = FaceColorResolver.resolve(cw.ringColor, colorScheme, layer.alpha),
        radius = cwRadiusPx,
        center = cwCenter,
      )
      drawCircle(
        color = FaceColorResolver.resolve(cw.centerColor, colorScheme, layer.alpha),
        radius = cwRadiusPx * 0.48f,
        center = cwCenter,
      )
    }

    if (layer.centerDotRadiusDp > 0f) {
      drawCircle(
        color = bodyColor,
        radius = layer.centerDotRadiusDp.dp.toPx(),
        center = pivot,
      )
    }
  }

  private fun DrawScope.drawAlignedText(
    text: String,
    anchorX: Float,
    anchorY: Float,
    textSizePx: Float,
    color: Color,
    weight: TextWeight,
    align: TextAlign,
    font: TextFont,
    letterSpacing: Float,
    typefaces: ResolvedTypefaces,
    textPaint: Paint,
  ) {
    textPaint.color = color.toArgb()
    textPaint.textSize = textSizePx.coerceAtLeast(1f)
    textPaint.letterSpacing = letterSpacing
    textPaint.textAlign = when (align) {
      TextAlign.LEFT -> Paint.Align.LEFT
      TextAlign.CENTER -> Paint.Align.CENTER
      TextAlign.RIGHT -> Paint.Align.RIGHT
    }
    textPaint.typeface = typefaces.resolve(font, weight)
    val fm = textPaint.fontMetrics
    val baselineY = anchorY - (fm.ascent + fm.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, anchorX, baselineY, textPaint)
  }

  private fun DrawScope.resolveRadiusPx(layer: ShapeLayer, r: Float): Float = when {
    layer.radiusDp != null -> layer.radiusDp.dp.toPx()
    layer.radius != null -> layer.radius * r
    else -> 0f
  }

  private data class ResolvedTypefaces(
    val defaultNormal: Typeface,
    val defaultBold: Typeface,
    val sansNormal: Typeface,
    val sansBold: Typeface,
    val monoNormal: Typeface,
    val monoBold: Typeface,
  ) {
    fun resolve(font: TextFont, weight: TextWeight): Typeface {
      val bold = weight == TextWeight.BOLD
      return when (font) {
        TextFont.DEFAULT -> if (bold) defaultBold else defaultNormal
        TextFont.SANS -> if (bold) sansBold else sansNormal
        TextFont.MONO -> if (bold) monoBold else monoNormal
      }
    }
  }

  @Composable
  private fun rememberTypefaces(context: Context): ResolvedTypefaces = remember(context) {
    val sansBase = runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }.getOrNull()
      ?: Typeface.SANS_SERIF
    val monoBase = runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
      ?: Typeface.MONOSPACE
    ResolvedTypefaces(
      defaultNormal = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
      defaultBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
      sansNormal = Typeface.create(sansBase, Typeface.NORMAL),
      sansBold = Typeface.create(sansBase, Typeface.BOLD),
      monoNormal = Typeface.create(monoBase, Typeface.NORMAL),
      monoBold = Typeface.create(monoBase, Typeface.BOLD),
    )
  }

  /**
   * Decodes [resId] using power-of-2 [BitmapFactory.Options.inSampleSize] and scales down if still
   * larger than [maxDialPx] so the resulting bitmap is never larger than the drawn dial's pixel size.
   */
  internal fun decodeBitmapForDialSize(
    resources: Resources,
    @DrawableRes resId: Int,
    maxDialPx: Int,
  ): Bitmap? {
    val targetMax = maxDialPx.coerceAtLeast(1)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(resources, resId, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while ((bounds.outWidth / (sampleSize * 2)) >= targetMax &&
      (bounds.outHeight / (sampleSize * 2)) >= targetMax
    ) {
      sampleSize *= 2
    }

    val opts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching { BitmapFactory.decodeResource(resources, resId, opts) }.getOrNull()
      ?: return null

    if (decoded.width <= targetMax && decoded.height <= targetMax) {
      return decoded
    }
    val scale = minOf(targetMax.toFloat() / decoded.width, targetMax.toFloat() / decoded.height)
    val dstW = (decoded.width * scale).roundToInt().coerceIn(1, targetMax)
    val dstH = (decoded.height * scale).roundToInt().coerceIn(1, targetMax)
    return Bitmap.createScaledBitmap(decoded, dstW, dstH, true)
  }

  /**
   * Maps known nodpi dial names directly to R.drawable constants so R8 resource shrinking keeps
   * them in release builds, falling back to getIdentifier for dynamically added assets.
   */
  @DrawableRes
  internal fun resolveDrawableId(context: Context, drawableName: String): Int = when (drawableName) {
    "face_motorsport_m" -> R.drawable.face_motorsport_m
    "face_speedmaster_racing" -> R.drawable.face_speedmaster_racing
    "face_nike_dots" -> R.drawable.face_nike_dots
    "face_nike_numerals" -> R.drawable.face_nike_numerals
    "face_yellow_racing" -> R.drawable.face_yellow_racing
    "face_lacoste_green" -> R.drawable.face_lacoste_green
    "face_casio_leather" -> R.drawable.face_casio_leather
    "face_bell_ross" -> R.drawable.face_bell_ross
    "face_seiko_blue" -> R.drawable.face_seiko_blue
    "face_phantom_black" -> R.drawable.face_phantom_black
    "face_wood_art" -> R.drawable.face_wood_art
    else -> context.resources.getIdentifier(drawableName, "drawable", context.packageName)
  }
}

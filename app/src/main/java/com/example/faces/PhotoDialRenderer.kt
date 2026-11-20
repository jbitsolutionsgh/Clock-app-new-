package com.example.faces

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.core.FaceRenderMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reusable zero-allocation drawing engine for Photo Dial hands, lume outlines, centre caps,
 * shadows, and optional live date windows. Shared between the live Clock/StandBy stage and
 * the Photo Face editor preview for 100% WYSIWYG accuracy.
 */
internal object PhotoDialRenderer {

  /** Per-stage reusable scratch buffers avoiding per-frame allocations inside `Canvas`. */
  class HandsScratch {
    val handPath = Path()
    val lumePath = Path()
    val datePaint = Paint().apply {
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val shadowOffsetFactorDp = 2f
  }

  /** Renders all Photo Dial hands, centre cap, and optional date window into [DrawScope]. */
  fun DrawScope.drawPhotoDialOverlay(
    config: PhotoFaceConfig,
    time: ClockTimeSnapshot,
    renderMode: FaceRenderMode,
    dateTypeface: Typeface?,
    scratch: HandsScratch,
  ) {
    val r = size.minDimension * 0.5f
    if (r <= 0f) return
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val isAmbient = renderMode == FaceRenderMode.AMBIENT
    val useSmooth = config.smoothSeconds && !isAmbient

    val secFloat = if (useSmooth) time.second + (time.millis / 1000f) else time.second.toFloat()
    val minFloat = time.minute + (secFloat / 60f)
    val hourFloat = (time.hour12 % 12) + (minFloat / 60f)

    val hourAngleDeg = hourFloat * 30f
    val minuteAngleDeg = minFloat * 6f
    val secondAngleDeg = secFloat * 6f

    val shadowAlpha = if (isAmbient) 0f else config.shadowStrength.coerceIn(0f, 1f) * 0.72f
    val shadowColor = Color.Black.copy(alpha = shadowAlpha)
    val shadowShift = Offset(scratch.shadowOffsetFactorDp.dp.toPx(), scratch.shadowOffsetFactorDp.dp.toPx())

    // Optional live date window placed at the tapped normalized coordinates.
    if (config.dateEnabled) {
      drawDateWindow(
        config = config,
        dayOfMonth = time.dayOfMonth,
        center = center,
        r = r,
        isAmbient = isAmbient,
        dateTypeface = dateTypeface,
        paint = scratch.datePaint,
      )
    }

    val widthScale = config.handWidthScale.coerceIn(0.6f, 1.8f)
    val baseHourHalfW = (r * 0.042f) * widthScale
    val baseMinHalfW = (r * 0.031f) * widthScale
    val hourLen = config.hourLengthRatio.coerceIn(0.30f, 0.65f) * r
    val minLen = config.minuteLengthRatio.coerceIn(0.50f, 0.90f) * r
    val tailLen = r * 0.14f

    val hourColor = applyAmbientTint(Color(config.hourColorArgb), isAmbient)
    val minColor = applyAmbientTint(Color(config.minuteColorArgb), isAmbient)
    val secColor = applyAmbientTint(Color(config.secondColorArgb), isAmbient)
    val lumeColor = Color(config.lumeColorArgb)

    // Hour hand
    drawStyledHand(
      style = config.handStyle,
      center = center,
      angleDeg = hourAngleDeg,
      lengthPx = hourLen,
      tailPx = tailLen,
      halfWidthPx = baseHourHalfW,
      bodyColor = hourColor,
      lumeEnabled = config.lumeOutlineEnabled && !isAmbient,
      lumeColor = lumeColor,
      shadowShift = shadowShift,
      shadowColor = shadowColor,
      scratch = scratch,
    )

    // Minute hand
    drawStyledHand(
      style = config.handStyle,
      center = center,
      angleDeg = minuteAngleDeg,
      lengthPx = minLen,
      tailPx = tailLen * 1.15f,
      halfWidthPx = baseMinHalfW,
      bodyColor = minColor,
      lumeEnabled = config.lumeOutlineEnabled && !isAmbient,
      lumeColor = lumeColor,
      shadowShift = shadowShift,
      shadowColor = shadowColor,
      scratch = scratch,
    )

    // Seconds hand (always hidden in ambient mode)
    if (config.showSecondHand && !isAmbient) {
      drawSecondsHand(
        center = center,
        angleDeg = secondAngleDeg,
        lengthPx = (r * 0.86f),
        tailPx = (r * 0.22f),
        widthPx = (1.9f * widthScale).dp.toPx(),
        color = secColor,
        shadowShift = shadowShift,
        shadowColor = shadowColor,
      )
    }

    // Centre arbor cap
    drawCenterCap(
      style = config.centerCapStyle,
      center = center,
      r = r,
      hourColor = hourColor,
      secColor = secColor,
      shadowShift = shadowShift,
      shadowColor = shadowColor,
    )
  }

  private fun DrawScope.drawDateWindow(
    config: PhotoFaceConfig,
    dayOfMonth: Int,
    center: Offset,
    r: Float,
    isAmbient: Boolean,
    dateTypeface: Typeface?,
    paint: Paint,
  ) {
    val px = center.x + config.dateXRatio.coerceIn(-0.78f, 0.78f) * r
    val py = center.y + config.dateYRatio.coerceIn(-0.78f, 0.78f) * r
    val textSizePx = (r * config.dateSizeRatio.coerceIn(0.05f, 0.14f) * 2f).coerceAtLeast(10f)

    if (!isAmbient) {
      val boxW = textSizePx * 1.65f
      val boxH = textSizePx * 1.25f
      drawRoundRect(
        color = Color(0xAA0B0F19),
        topLeft = Offset(px - boxW * 0.5f, py - boxH * 0.5f),
        size = androidx.compose.ui.geometry.Size(boxW, boxH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxH * 0.22f, boxH * 0.22f),
      )
      drawRoundRect(
        color = Color(config.dateColorArgb).copy(alpha = 0.45f),
        topLeft = Offset(px - boxW * 0.5f, py - boxH * 0.5f),
        size = androidx.compose.ui.geometry.Size(boxW, boxH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxH * 0.22f, boxH * 0.22f),
        style = Stroke(width = 1.dp.toPx()),
      )
    }

    paint.color = config.dateColorArgb
    paint.alpha = if (isAmbient) 210 else 255
    paint.textSize = textSizePx
    paint.typeface = if (dateTypeface != null) {
      Typeface.create(dateTypeface, Typeface.BOLD)
    } else {
      Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val fm = paint.fontMetrics
    val baselineY = py - (fm.ascent + fm.descent) * 0.5f
    drawContext.canvas.nativeCanvas.drawText("%02d".format(dayOfMonth), px, baselineY, paint)
  }

  private fun DrawScope.drawStyledHand(
    style: PhotoHandStyle,
    center: Offset,
    angleDeg: Float,
    lengthPx: Float,
    tailPx: Float,
    halfWidthPx: Float,
    bodyColor: Color,
    lumeEnabled: Boolean,
    lumeColor: Color,
    shadowShift: Offset,
    shadowColor: Color,
    scratch: HandsScratch,
  ) {
    val handPath = scratch.handPath
    val lumePath = scratch.lumePath
    handPath.reset()
    lumePath.reset()

    when (style) {
      PhotoHandStyle.SWORD -> {
        val shoulderY = center.y - lengthPx * 0.22f
        val tipY = center.y - lengthPx
        val baseY = center.y + tailPx * 0.65f
        handPath.moveTo(center.x, baseY)
        handPath.lineTo(center.x - halfWidthPx * 0.65f, center.y)
        handPath.lineTo(center.x - halfWidthPx * 1.15f, shoulderY)
        handPath.lineTo(center.x, tipY)
        handPath.lineTo(center.x + halfWidthPx * 1.15f, shoulderY)
        handPath.lineTo(center.x + halfWidthPx * 0.65f, center.y)
        handPath.close()

        if (lumeEnabled) {
          lumePath.moveTo(center.x, center.y - lengthPx * 0.18f)
          lumePath.lineTo(center.x - halfWidthPx * 0.52f, shoulderY)
          lumePath.lineTo(center.x, center.y - lengthPx * 0.86f)
          lumePath.lineTo(center.x + halfWidthPx * 0.52f, shoulderY)
          lumePath.close()
        }
      }

      PhotoHandStyle.BATON -> {
        val chamferY = center.y - lengthPx * 0.88f
        val tipY = center.y - lengthPx
        val baseY = center.y + tailPx
        handPath.moveTo(center.x - halfWidthPx, baseY)
        handPath.lineTo(center.x - halfWidthPx, chamferY)
        handPath.lineTo(center.x, tipY)
        handPath.lineTo(center.x + halfWidthPx, chamferY)
        handPath.lineTo(center.x + halfWidthPx, baseY)
        handPath.close()

        if (lumeEnabled) {
          val iw = halfWidthPx * 0.45f
          lumePath.moveTo(center.x - iw, center.y - lengthPx * 0.16f)
          lumePath.lineTo(center.x - iw, center.y - lengthPx * 0.84f)
          lumePath.lineTo(center.x + iw, center.y - lengthPx * 0.84f)
          lumePath.lineTo(center.x + iw, center.y - lengthPx * 0.16f)
          lumePath.close()
        }
      }

      PhotoHandStyle.DAUPHINE -> {
        val baseY = center.y + tailPx * 0.7f
        val tipY = center.y - lengthPx
        handPath.moveTo(center.x, baseY)
        handPath.lineTo(center.x - halfWidthPx * 1.45f, center.y)
        handPath.lineTo(center.x, tipY)
        handPath.lineTo(center.x + halfWidthPx * 1.45f, center.y)
        handPath.close()

        if (lumeEnabled) {
          lumePath.moveTo(center.x, center.y - lengthPx * 0.08f)
          lumePath.lineTo(center.x - halfWidthPx * 0.55f, center.y - lengthPx * 0.14f)
          lumePath.lineTo(center.x, center.y - lengthPx * 0.84f)
          lumePath.lineTo(center.x + halfWidthPx * 0.55f, center.y - lengthPx * 0.14f)
          lumePath.close()
        }
      }

      PhotoHandStyle.PENCIL -> {
        val collarY = center.y - lengthPx * 0.72f
        val tipY = center.y - lengthPx
        val baseY = center.y + tailPx
        handPath.moveTo(center.x - halfWidthPx * 0.85f, baseY)
        handPath.lineTo(center.x - halfWidthPx * 0.85f, collarY)
        handPath.lineTo(center.x, tipY)
        handPath.lineTo(center.x + halfWidthPx * 0.85f, collarY)
        handPath.lineTo(center.x + halfWidthPx * 0.85f, baseY)
        handPath.close()

        if (lumeEnabled) {
          val iw = halfWidthPx * 0.42f
          lumePath.moveTo(center.x - iw, center.y - lengthPx * 0.16f)
          lumePath.lineTo(center.x - iw, collarY)
          lumePath.lineTo(center.x, center.y - lengthPx * 0.88f)
          lumePath.lineTo(center.x + iw, collarY)
          lumePath.lineTo(center.x + iw, center.y - lengthPx * 0.16f)
          lumePath.close()
        }
      }

      PhotoHandStyle.ARROW -> {
        val shaftW = halfWidthPx * 0.58f
        val headBaseY = center.y - lengthPx * 0.68f
        val headW = halfWidthPx * 1.85f
        val tipY = center.y - lengthPx
        val baseY = center.y + tailPx
        handPath.moveTo(center.x - shaftW, baseY)
        handPath.lineTo(center.x - shaftW, headBaseY)
        handPath.lineTo(center.x - headW, headBaseY)
        handPath.lineTo(center.x, tipY)
        handPath.lineTo(center.x + headW, headBaseY)
        handPath.lineTo(center.x + shaftW, headBaseY)
        handPath.lineTo(center.x + shaftW, baseY)
        handPath.close()

        if (lumeEnabled) {
          lumePath.moveTo(center.x - headW * 0.48f, center.y - lengthPx * 0.72f)
          lumePath.lineTo(center.x, center.y - lengthPx * 0.92f)
          lumePath.lineTo(center.x + headW * 0.48f, center.y - lengthPx * 0.72f)
          lumePath.close()
        }
      }

      PhotoHandStyle.PLAIN_BAR -> {
        val tipY = center.y - lengthPx
        val baseY = center.y + tailPx
        handPath.addRoundRect(
          androidx.compose.ui.geometry.RoundRect(
            left = center.x - halfWidthPx * 0.9f,
            top = tipY,
            right = center.x + halfWidthPx * 0.9f,
            bottom = baseY,
            radiusX = halfWidthPx * 0.9f,
            radiusY = halfWidthPx * 0.9f,
          ),
        )
        if (lumeEnabled) {
          val iw = halfWidthPx * 0.38f
          lumePath.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
              left = center.x - iw,
              top = center.y - lengthPx * 0.88f,
              right = center.x + iw,
              bottom = center.y - lengthPx * 0.14f,
              radiusX = iw,
              radiusY = iw,
            ),
          )
        }
      }
    }

    if (shadowColor.alpha > 0.01f) {
      drawContext.canvas.save()
      drawContext.canvas.translate(shadowShift.x, shadowShift.y)
      rotate(degrees = angleDeg, pivot = center) {
        drawPath(path = handPath, color = shadowColor, style = Fill)
      }
      drawContext.canvas.restore()
    }

    rotate(degrees = angleDeg, pivot = center) {
      if (lumeEnabled) {
        drawPath(
          path = handPath,
          color = lumeColor.copy(alpha = 0.85f),
          style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round),
        )
      }
      drawPath(path = handPath, color = bodyColor, style = Fill)
      if (lumeEnabled && !lumePath.isEmpty) {
        drawPath(path = lumePath, color = lumeColor, style = Fill)
      }
    }
  }

  private fun DrawScope.drawSecondsHand(
    center: Offset,
    angleDeg: Float,
    lengthPx: Float,
    tailPx: Float,
    widthPx: Float,
    color: Color,
    shadowShift: Offset,
    shadowColor: Color,
  ) {
    val rad = (angleDeg - 90f) * (PI / 180.0)
    val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    val start = center - dir * tailPx
    val end = center + dir * lengthPx

    if (shadowColor.alpha > 0.01f) {
      drawLine(
        color = shadowColor,
        start = start + shadowShift,
        end = end + shadowShift,
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
      )
    }
    drawLine(
      color = color,
      start = start,
      end = end,
      strokeWidth = widthPx,
      cap = StrokeCap.Round,
    )
  }

  private fun DrawScope.drawCenterCap(
    style: PhotoCenterCapStyle,
    center: Offset,
    r: Float,
    hourColor: Color,
    secColor: Color,
    shadowShift: Offset,
    shadowColor: Color,
  ) {
    val baseRadius = (r * 0.036f).coerceIn(4.dp.toPx(), 9.dp.toPx())
    when (style) {
      PhotoCenterCapStyle.DOUBLE_RING -> {
        if (shadowColor.alpha > 0.01f) {
          drawCircle(color = shadowColor, radius = baseRadius, center = center + shadowShift)
        }
        drawCircle(color = hourColor, radius = baseRadius, center = center)
        drawCircle(color = secColor, radius = baseRadius * 0.48f, center = center)
      }
      PhotoCenterCapStyle.SOLID_DISC -> {
        if (shadowColor.alpha > 0.01f) {
          drawCircle(color = shadowColor, radius = baseRadius * 0.95f, center = center + shadowShift)
        }
        drawCircle(color = hourColor, radius = baseRadius * 0.95f, center = center)
      }
      PhotoCenterCapStyle.OPEN_HUB -> {
        if (shadowColor.alpha > 0.01f) {
          drawCircle(
            color = shadowColor,
            radius = baseRadius * 0.9f,
            center = center + shadowShift,
            style = Stroke(width = baseRadius * 0.42f),
          )
        }
        drawCircle(
          color = hourColor,
          radius = baseRadius * 0.9f,
          center = center,
          style = Stroke(width = baseRadius * 0.42f),
        )
      }
      PhotoCenterCapStyle.MINIMAL_DOT -> {
        if (shadowColor.alpha > 0.01f) {
          drawCircle(color = shadowColor, radius = baseRadius * 0.5f, center = center + shadowShift)
        }
        drawCircle(color = secColor, radius = baseRadius * 0.5f, center = center)
      }
    }
  }

  private fun applyAmbientTint(color: Color, isAmbient: Boolean): Color {
    if (!isAmbient) return color
    return color.copy(alpha = 0.85f)
  }
}

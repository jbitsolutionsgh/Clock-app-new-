package com.example.faces

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.AuraThemeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class SubDialSpec(
  val offsetXRatio: Float,
  val lengthRatio: Float,
  val widthDp: Float,
  val color: Color,
  val isSecondsSubDial: Boolean,
)

internal data class CounterweightSpec(
  val radiusDp: Float,
  val ringColor: Color,
  val centerColor: Color,
  /** Positive ratio of radius R along the pointer, or negative dp offset along the tail. */
  val positionAlongPointerRatio: Float? = null,
  val tailOffsetDp: Float? = null,
)

internal data class DialConfig(
  val hourLengthRatio: Float,
  val hourColor: Color,
  val hourWidthDp: Float,
  val hourCap: StrokeCap,
  val hourStripeColor: Color,
  val hourStripeWidthDp: Float,
  val minuteLengthRatio: Float,
  val minuteColor: Color,
  val minuteWidthDp: Float,
  val minuteCap: StrokeCap,
  val minuteStripeColor: Color,
  val minuteStripeWidthDp: Float,
  val secondLengthRatio: Float,
  val secondColor: Color,
  val secondWidthDp: Float,
  val secondTailDp: Float,
  val counterweight: CounterweightSpec,
  val centerCapOuterDp: Float,
  val centerCapOuterColor: Color,
  val centerCapInnerDp: Float,
  val centerCapInnerColor: Color,
  val leftSubDial: SubDialSpec? = null,
  val rightSubDial: SubDialSpec? = null,
  val dateOffsetYRatio: Float? = null,
  val dateTextSizeRatio: Float = 0.065f,
)

/**
 * Reusable WatchFace that renders a supplied nodpi dial image unmodified in a 94% circle
 * and draws precision-centred shadow, hands, sub-dials, and date window over it.
 */
internal class ImageDialFace(
  override val id: String,
  override val displayName: String,
  @DrawableRes private val dialResId: Int,
  private val config: DialConfig,
) : WatchFace {

  @Composable
  override fun Render(
    timeProvider: () -> ClockTimeSnapshot,
    accent: AuraThemeColor,
    smoothSeconds: Boolean,
    modifier: Modifier,
  ) {
    val context = LocalContext.current
    // Decode only the active dial bitmap inside remember so inactive dials stay unloaded.
    val dialBitmap = remember(dialResId) {
      BitmapFactory.decodeResource(context.resources, dialResId)?.asImageBitmap()
    }
    val datePaint = remember {
      Paint().apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
      }
    }

    BoxWithConstraints(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val shorterSide = minOf(maxWidth, maxHeight)
      val dialDiameter = shorterSide * 0.94f

      Box(
        modifier = Modifier
          .size(dialDiameter)
          .clip(CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (dialBitmap != null) {
          Image(
            bitmap = dialBitmap,
            contentDescription = "$displayName watch dial",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
          val t = timeProvider()
          val r = size.minDimension / 2f
          val center = Offset(size.width / 2f, size.height / 2f)
          val shadowOffset = Offset(2.dp.toPx(), 2.dp.toPx())
          val shadowColor = Color.Black.copy(alpha = 0.45f)

          val secFloat = if (smoothSeconds) t.second + (t.millis / 1000f) else t.second.toFloat()
          val minFloat = t.minute + (secFloat / 60f)
          val hourFloat = (t.hour12 % 12) + (minFloat / 60f)

          val secondAngleDeg = secFloat * 6f
          val minuteAngleDeg = minFloat * 6f
          val hourAngleDeg = hourFloat * 30f

          // Optional date window (e.g. Speedmaster Racing at 0.52R below center).
          config.dateOffsetYRatio?.let { offsetY ->
            datePaint.textSize = r * config.dateTextSizeRatio
            val fm = datePaint.fontMetrics
            val baselineOffset = -(fm.ascent + fm.descent) / 2f
            drawContext.canvas.nativeCanvas.drawText(
              "%02d".format(t.dayOfMonth),
              center.x,
              center.y + r * offsetY + baselineOffset,
              datePaint,
            )
          }

          // Optional sub-dial needles (running seconds on left, chrono minutes on right).
          config.leftSubDial?.let { sub ->
            val subCenter = Offset(center.x + sub.offsetXRatio * r, center.y)
            val angle = if (sub.isSecondsSubDial) secondAngleDeg else t.minute * 6f
            drawSubDialNeedle(subCenter, angle, sub.lengthRatio * r, sub.widthDp.dp.toPx(), sub.color, shadowOffset, shadowColor)
          }
          config.rightSubDial?.let { sub ->
            val subCenter = Offset(center.x + sub.offsetXRatio * r, center.y)
            val angle = if (sub.isSecondsSubDial) secondAngleDeg else t.minute * 6f
            drawSubDialNeedle(subCenter, angle, sub.lengthRatio * r, sub.widthDp.dp.toPx(), sub.color, shadowOffset, shadowColor)
          }

          val backTailPx = 8.dp.toPx()
          val tipInsetPx = 4.dp.toPx()

          // Hour hand
          val hourLenPx = config.hourLengthRatio * r
          drawMainHand(
            center = center,
            angleDeg = hourAngleDeg,
            tailLengthPx = backTailPx,
            tipLengthPx = hourLenPx,
            widthPx = config.hourWidthDp.dp.toPx(),
            cap = config.hourCap,
            bodyColor = config.hourColor,
            stripeColor = config.hourStripeColor,
            stripeWidthPx = config.hourStripeWidthDp.dp.toPx(),
            stripeTipInsetPx = tipInsetPx,
            shadowOffset = shadowOffset,
            shadowColor = shadowColor,
          )

          // Minute hand
          val minLenPx = config.minuteLengthRatio * r
          drawMainHand(
            center = center,
            angleDeg = minuteAngleDeg,
            tailLengthPx = backTailPx,
            tipLengthPx = minLenPx,
            widthPx = config.minuteWidthDp.dp.toPx(),
            cap = config.minuteCap,
            bodyColor = config.minuteColor,
            stripeColor = config.minuteStripeColor,
            stripeWidthPx = config.minuteStripeWidthDp.dp.toPx(),
            stripeTipInsetPx = tipInsetPx,
            shadowOffset = shadowOffset,
            shadowColor = shadowColor,
          )

          // Seconds hand + counterweight
          val secLenPx = config.secondLengthRatio * r
          val secTailPx = config.secondTailDp.dp.toPx()
          val rad = (secondAngleDeg - 90f) * (PI / 180.0)
          val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
          val secStart = center - dir * secTailPx
          val secEnd = center + dir * secLenPx

          drawLine(
            color = shadowColor,
            start = secStart + shadowOffset,
            end = secEnd + shadowOffset,
            strokeWidth = config.secondWidthDp.dp.toPx(),
            cap = StrokeCap.Round,
          )
          drawLine(
            color = config.secondColor,
            start = secStart,
            end = secEnd,
            strokeWidth = config.secondWidthDp.dp.toPx(),
            cap = StrokeCap.Round,
          )

          val cw = config.counterweight
          val cwCenter = when {
            cw.positionAlongPointerRatio != null -> center + dir * (secLenPx * cw.positionAlongPointerRatio)
            cw.tailOffsetDp != null -> center - dir * cw.tailOffsetDp.dp.toPx()
            else -> center - dir * (secTailPx * 0.72f)
          }
          val cwRadiusPx = cw.radiusDp.dp.toPx()
          drawCircle(color = shadowColor, radius = cwRadiusPx, center = cwCenter + shadowOffset)
          drawCircle(color = cw.ringColor, radius = cwRadiusPx, center = cwCenter)
          drawCircle(color = cw.centerColor, radius = cwRadiusPx * 0.48f, center = cwCenter)

          // Center cap
          val outerCapPx = config.centerCapOuterDp.dp.toPx()
          val innerCapPx = config.centerCapInnerDp.dp.toPx()
          drawCircle(color = shadowColor, radius = outerCapPx, center = center + shadowOffset)
          drawCircle(color = config.centerCapOuterColor, radius = outerCapPx, center = center)
          drawCircle(color = config.centerCapInnerColor, radius = innerCapPx, center = center)
        }
      }
    }
  }

  private fun DrawScope.drawMainHand(
    center: Offset,
    angleDeg: Float,
    tailLengthPx: Float,
    tipLengthPx: Float,
    widthPx: Float,
    cap: StrokeCap,
    bodyColor: Color,
    stripeColor: Color,
    stripeWidthPx: Float,
    stripeTipInsetPx: Float,
    shadowOffset: Offset,
    shadowColor: Color,
  ) {
    val rad = (angleDeg - 90f) * (PI / 180.0)
    val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    val start = center - dir * tailLengthPx
    val end = center + dir * tipLengthPx

    drawLine(
      color = shadowColor,
      start = start + shadowOffset,
      end = end + shadowOffset,
      strokeWidth = widthPx,
      cap = cap,
    )
    drawLine(
      color = bodyColor,
      start = start,
      end = end,
      strokeWidth = widthPx,
      cap = cap,
    )

    val stripeEnd = center + dir * (tipLengthPx - stripeTipInsetPx).coerceAtLeast(0f)
    drawLine(
      color = stripeColor,
      start = start,
      end = stripeEnd,
      strokeWidth = stripeWidthPx,
      cap = cap,
    )
  }

  private fun DrawScope.drawSubDialNeedle(
    subCenter: Offset,
    angleDeg: Float,
    lengthPx: Float,
    widthPx: Float,
    color: Color,
    shadowOffset: Offset,
    shadowColor: Color,
  ) {
    val rad = (angleDeg - 90f) * (PI / 180.0)
    val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    val tip = subCenter + dir * lengthPx

    drawLine(
      color = shadowColor,
      start = subCenter + shadowOffset,
      end = tip + shadowOffset,
      strokeWidth = widthPx,
      cap = StrokeCap.Round,
    )
    drawLine(
      color = color,
      start = subCenter,
      end = tip,
      strokeWidth = widthPx,
      cap = StrokeCap.Round,
    )
    drawCircle(color = color, radius = widthPx * 1.15f, center = subCenter)
  }
}

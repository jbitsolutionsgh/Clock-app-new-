package com.example.faces

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.AuraThemeColor
import com.example.core.FaceRenderMode
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
  val hourStripeColor: Color? = null,
  val hourStripeWidthDp: Float = 0f,
  val hourStripeTipOnly: Boolean = false,
  val minuteLengthRatio: Float,
  val minuteColor: Color,
  val minuteWidthDp: Float,
  val minuteCap: StrokeCap,
  val minuteStripeColor: Color? = null,
  val minuteStripeWidthDp: Float = 0f,
  val minuteStripeTipOnly: Boolean = false,
  val secondLengthRatio: Float,
  val secondColor: Color,
  val secondWidthDp: Float,
  val secondTailDp: Float,
  val counterweight: CounterweightSpec? = null,
  val centerCapOuterDp: Float,
  val centerCapOuterColor: Color,
  val centerCapInnerDp: Float,
  val centerCapInnerColor: Color,
  val handShadowAlpha: Float = 0.45f,
  val leftSubDial: SubDialSpec? = null,
  val rightSubDial: SubDialSpec? = null,
  val dateOffsetXRatio: Float = 0f,
  val dateOffsetYRatio: Float? = null,
  val dateTextSizeRatio: Float = 0.065f,
  val dateColor: Color = Color.White,
)

/**
 * Bounded in-memory cache of downsampled (<= 160px) dial thumbnails for smooth picker scrolling
 * on low-RAM devices without ever keeping full-resolution bitmaps for inactive faces.
 */
internal object DialThumbnailCache {
  private const val MAX_THUMBNAIL_PX = 160
  private val cache = LruCache<Int, ImageBitmap>(16)

  /** Returns a cached thumbnail ImageBitmap, decoding at <= 160px with power-of-2 downsampling if missing. */
  fun getOrDecode(resources: Resources, @DrawableRes resId: Int): ImageBitmap? {
    cache.get(resId)?.let { return it }
    val decoded = decodeDownsampled(resources, resId, MAX_THUMBNAIL_PX) ?: return null
    val imageBitmap = decoded.asImageBitmap()
    cache.put(resId, imageBitmap)
    return imageBitmap
  }

  /** Decodes [resId] with [BitmapFactory.Options.inSampleSize] so width and height stay <= [maxPx]. */
  internal fun decodeDownsampled(
    resources: Resources,
    @DrawableRes resId: Int,
    maxPx: Int = MAX_THUMBNAIL_PX,
  ): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(resources, resId, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while ((bounds.outWidth / sampleSize) > maxPx || (bounds.outHeight / sampleSize) > maxPx) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeResource(resources, resId, decodeOpts)
  }
}

/**
 * Reusable WatchFace that renders a supplied nodpi dial image unclipped in a 94% square stage
 * and draws precision-centred shadow, hands, sub-dials, and live date window over it.
 * Decodes only the active face at full size and recycles it when switching away.
 */
internal class ImageDialFace(
  override val id: String,
  override val displayName: String,
  @DrawableRes private val dialResId: Int,
  private val config: DialConfig,
) : WatchFace {

  @Composable
  override fun Thumbnail(modifier: Modifier) {
    val context = LocalContext.current
    val thumb = remember(dialResId) {
      DialThumbnailCache.getOrDecode(context.resources, dialResId)
    }
    if (thumb != null) {
      Image(
        bitmap = thumb,
        contentDescription = "$displayName preview",
        contentScale = ContentScale.Fit,
        modifier = modifier,
      )
    }
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
    // Decode only the active dial bitmap and recycle it as soon as the active face changes.
    val dialRawBitmap = remember(dialResId) {
      BitmapFactory.decodeResource(context.resources, dialResId)
    }
    val dialBitmap = remember(dialRawBitmap) {
      dialRawBitmap?.asImageBitmap()
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
        modifier = Modifier.size(dialDiameter),
        contentAlignment = Alignment.Center,
      ) {
        if (dialBitmap != null && dialRawBitmap != null && !dialRawBitmap.isRecycled) {
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
          val shadowColor = Color.Black.copy(alpha = config.handShadowAlpha.coerceIn(0f, 1f))

          val secFloat = if (smoothSeconds) t.second + (t.millis / 1000f) else t.second.toFloat()
          val minFloat = t.minute + (secFloat / 60f)
          val hourFloat = (t.hour12 % 12) + (minFloat / 60f)

          val secondAngleDeg = secFloat * 6f
          val minuteAngleDeg = minFloat * 6f
          val hourAngleDeg = hourFloat * 30f

          // Optional live date window positioned relative to R from the dial center.
          if (config.dateOffsetYRatio != null || config.dateOffsetXRatio != 0f) {
            val offsetY = config.dateOffsetYRatio ?: 0f
            datePaint.color = config.dateColor.toArgb()
            datePaint.textSize = r * config.dateTextSizeRatio
            val fm = datePaint.fontMetrics
            val baselineOffset = -(fm.ascent + fm.descent) / 2f
            drawContext.canvas.nativeCanvas.drawText(
              "%02d".format(t.dayOfMonth),
              center.x + r * config.dateOffsetXRatio,
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
            stripeTipOnly = config.hourStripeTipOnly,
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
            stripeTipOnly = config.minuteStripeTipOnly,
            stripeTipInsetPx = tipInsetPx,
            shadowOffset = shadowOffset,
            shadowColor = shadowColor,
          )

          // Seconds hand + optional counterweight
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

          config.counterweight?.let { cw ->
            val cwCenter = when {
              cw.positionAlongPointerRatio != null -> center + dir * (secLenPx * cw.positionAlongPointerRatio)
              cw.tailOffsetDp != null -> center - dir * cw.tailOffsetDp.dp.toPx()
              else -> center - dir * (secTailPx * 0.72f)
            }
            val cwRadiusPx = cw.radiusDp.dp.toPx()
            drawCircle(color = shadowColor, radius = cwRadiusPx, center = cwCenter + shadowOffset)
            drawCircle(color = cw.ringColor, radius = cwRadiusPx, center = cwCenter)
            drawCircle(color = cw.centerColor, radius = cwRadiusPx * 0.48f, center = cwCenter)
          }

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
    stripeColor: Color?,
    stripeWidthPx: Float,
    stripeTipOnly: Boolean,
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

    if (stripeColor != null && stripeWidthPx > 0f) {
      val stripeStart = if (stripeTipOnly) center + dir * (tipLengthPx * 0.62f) else start
      val inset = if (stripeTipOnly) 2.dp.toPx() else stripeTipInsetPx
      val stripeEnd = center + dir * (tipLengthPx - inset).coerceAtLeast(0f)
      drawLine(
        color = stripeColor,
        start = stripeStart,
        end = stripeEnd,
        strokeWidth = stripeWidthPx,
        cap = cap,
      )
    }
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

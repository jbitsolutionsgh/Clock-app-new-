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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraThemeColor
import com.example.core.CustomFontStorage
import com.example.core.FaceRenderMode
import com.example.faces.PhotoDialRenderer.drawPhotoDialOverlay
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal sealed interface DialSource {
  data class Resource(@DrawableRes val resId: Int) : DialSource
  data class ImageFile(val file: File) : DialSource
}

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
 * Bounded in-memory cache of downsampled (<= 160px) dial thumbnails supporting both drawable resources
 * and saved photo dial files without keeping full-resolution bitmaps for inactive faces.
 */
internal object DialThumbnailCache {
  private const val MAX_THUMBNAIL_PX = 160
  private val cache = LruCache<String, ImageBitmap>(16)

  /** Returns a cached thumbnail ImageBitmap for [resId], decoding at <= 160px if missing. */
  fun getOrDecode(resources: Resources, @DrawableRes resId: Int): ImageBitmap? {
    val key = "res:$resId"
    cache.get(key)?.let { return it }
    val decoded = decodeDownsampled(resources, resId, MAX_THUMBNAIL_PX) ?: return null
    val imageBitmap = decoded.asImageBitmap()
    cache.put(key, imageBitmap)
    return imageBitmap
  }

  /** Returns a cached thumbnail ImageBitmap for a file-backed dial, decoding at <= 160px if missing. */
  fun getOrDecodeFile(file: File): ImageBitmap? {
    if (!file.exists() || !file.isFile) return null
    val key = "file:${file.absolutePath}:${file.lastModified()}"
    cache.get(key)?.let { return it }
    val decoded = decodeFileDownsampled(file, MAX_THUMBNAIL_PX) ?: return null
    val imageBitmap = decoded.asImageBitmap()
    cache.put(key, imageBitmap)
    return imageBitmap
  }

  /** Evicts any cached thumbnail for [file] after editing or replacing the dial image. */
  fun invalidateFile(file: File) {
    val prefix = "file:${file.absolutePath}:"
    for (k in cache.snapshot().keys) {
      if (k.startsWith(prefix)) {
        cache.remove(k)
      }
    }
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
    return runCatching { BitmapFactory.decodeResource(resources, resId, decodeOpts) }.getOrNull()
  }

  /** Decodes [file] at no larger than [maxPx] on the longest side using power-of-2 subsampling. */
  internal fun decodeFileDownsampled(
    file: File,
    maxPx: Int,
  ): Bitmap? {
    if (!file.exists() || !file.isFile) return null
    val targetMax = maxPx.coerceAtLeast(16)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while ((bounds.outWidth / (sampleSize * 2)) >= targetMax &&
      (bounds.outHeight / (sampleSize * 2)) >= targetMax
    ) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, decodeOpts) }.getOrNull()
      ?: return null

    if (decoded.width <= targetMax && decoded.height <= targetMax) {
      return decoded
    }
    val scale = minOf(targetMax.toFloat() / decoded.width, targetMax.toFloat() / decoded.height)
    val dstW = (decoded.width * scale).roundToInt().coerceIn(1, targetMax)
    val dstH = (decoded.height * scale).roundToInt().coerceIn(1, targetMax)
    val scaled = Bitmap.createScaledBitmap(decoded, dstW, dstH, true)
    if (scaled !== decoded) {
      decoded.recycle()
    }
    return scaled
  }
}

/**
 * Reusable WatchFace that renders a supplied dial image (either `@DrawableRes` or `File`)
 * unclipped in a 94% square stage and draws hands, sub-dials, shadows, and live date windows.
 * Decodes only the active face at the drawn dial size.
 */
internal class ImageDialFace(
  override val id: String,
  override val displayName: String,
  private val dialSource: DialSource,
  private val config: DialConfig? = null,
  val photoConfig: PhotoFaceConfig? = null,
) : WatchFace {

  constructor(
    id: String,
    displayName: String,
    @DrawableRes dialResId: Int,
    config: DialConfig,
  ) : this(
    id = id,
    displayName = displayName,
    dialSource = DialSource.Resource(dialResId),
    config = config,
    photoConfig = null,
  )

  @Composable
  override fun Thumbnail(modifier: Modifier) {
    val context = LocalContext.current
    val thumb = remember(dialSource, photoConfig?.updatedAtMs) {
      when (dialSource) {
        is DialSource.Resource -> DialThumbnailCache.getOrDecode(context.resources, dialSource.resId)
        is DialSource.ImageFile -> DialThumbnailCache.getOrDecodeFile(dialSource.file)
      }
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
    val density = LocalDensity.current

    val legacyDatePaint = remember {
      Paint().apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
      }
    }
    val photoScratch = remember { PhotoDialRenderer.HandsScratch() }
    val importedFonts by CustomFontStorage.fontsFlow.collectAsStateWithLifecycle()
    val resolvedDateTypeface = remember(photoConfig?.dateFontId, importedFonts) {
      photoConfig?.dateFontId?.let { CustomFontStorage.resolveTypeface(context, it) }
    }

    BoxWithConstraints(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val shorterSide = minOf(maxWidth, maxHeight)
      val dialDiameter = shorterSide * 0.94f
      val dialSidePx = with(density) { dialDiameter.roundToPx().coerceIn(64, PhotoDialBitmapProcessor.MAX_SAVED_DIAL_PX) }

      // Decode only the active dial bitmap sized cleanly for the screen stage.
      val dialRawBitmap = remember(dialSource, dialSidePx, photoConfig?.updatedAtMs) {
        when (dialSource) {
          is DialSource.Resource -> BitmapFactory.decodeResource(context.resources, dialSource.resId)
          is DialSource.ImageFile -> DialThumbnailCache.decodeFileDownsampled(dialSource.file, dialSidePx)
        }
      }
      val dialBitmap = remember(dialRawBitmap) {
        dialRawBitmap?.asImageBitmap()
      }

      Box(
        modifier = Modifier.size(dialDiameter),
        contentAlignment = Alignment.Center,
      ) {
        if (dialBitmap != null && dialRawBitmap != null && !dialRawBitmap.isRecycled) {
          val imageAlpha = if (photoConfig != null && renderMode == FaceRenderMode.AMBIENT) 0.65f else 1f
          Image(
            bitmap = dialBitmap,
            contentDescription = "$displayName watch dial",
            contentScale = ContentScale.Fit,
            alpha = imageAlpha,
            modifier = Modifier.fillMaxSize(),
          )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
          val t = timeProvider()
          if (photoConfig != null) {
            drawPhotoDialOverlay(
              config = photoConfig,
              time = t,
              renderMode = renderMode,
              dateTypeface = resolvedDateTypeface,
              scratch = photoScratch,
            )
            return@Canvas
          }

          val cfg = config ?: return@Canvas
          val r = size.minDimension / 2f
          val center = Offset(size.width / 2f, size.height / 2f)
          val shadowOffset = Offset(2.dp.toPx(), 2.dp.toPx())
          val shadowColor = Color.Black.copy(alpha = cfg.handShadowAlpha.coerceIn(0f, 1f))

          val secFloat = if (smoothSeconds) t.second + (t.millis / 1000f) else t.second.toFloat()
          val minFloat = t.minute + (secFloat / 60f)
          val hourFloat = (t.hour12 % 12) + (minFloat / 60f)

          val secondAngleDeg = secFloat * 6f
          val minuteAngleDeg = minFloat * 6f
          val hourAngleDeg = hourFloat * 30f

          // Optional live date window positioned relative to R from the dial center.
          if (cfg.dateOffsetYRatio != null || cfg.dateOffsetXRatio != 0f) {
            val offsetY = cfg.dateOffsetYRatio ?: 0f
            legacyDatePaint.color = cfg.dateColor.toArgb()
            legacyDatePaint.textSize = r * cfg.dateTextSizeRatio
            val fm = legacyDatePaint.fontMetrics
            val baselineOffset = -(fm.ascent + fm.descent) / 2f
            drawContext.canvas.nativeCanvas.drawText(
              "%02d".format(t.dayOfMonth),
              center.x + r * cfg.dateOffsetXRatio,
              center.y + r * offsetY + baselineOffset,
              legacyDatePaint,
            )
          }

          // Optional sub-dial needles (running seconds on left, chrono minutes on right).
          cfg.leftSubDial?.let { sub ->
            val subCenter = Offset(center.x + sub.offsetXRatio * r, center.y)
            val angle = if (sub.isSecondsSubDial) secondAngleDeg else t.minute * 6f
            drawSubDialNeedle(subCenter, angle, sub.lengthRatio * r, sub.widthDp.dp.toPx(), sub.color, shadowOffset, shadowColor)
          }
          cfg.rightSubDial?.let { sub ->
            val subCenter = Offset(center.x + sub.offsetXRatio * r, center.y)
            val angle = if (sub.isSecondsSubDial) secondAngleDeg else t.minute * 6f
            drawSubDialNeedle(subCenter, angle, sub.lengthRatio * r, sub.widthDp.dp.toPx(), sub.color, shadowOffset, shadowColor)
          }

          val backTailPx = 8.dp.toPx()
          val tipInsetPx = 4.dp.toPx()

          // Hour hand
          val hourLenPx = cfg.hourLengthRatio * r
          drawMainHand(
            center = center,
            angleDeg = hourAngleDeg,
            tailLengthPx = backTailPx,
            tipLengthPx = hourLenPx,
            widthPx = cfg.hourWidthDp.dp.toPx(),
            cap = cfg.hourCap,
            bodyColor = cfg.hourColor,
            stripeColor = cfg.hourStripeColor,
            stripeWidthPx = cfg.hourStripeWidthDp.dp.toPx(),
            stripeTipOnly = cfg.hourStripeTipOnly,
            stripeTipInsetPx = tipInsetPx,
            shadowOffset = shadowOffset,
            shadowColor = shadowColor,
          )

          // Minute hand
          val minLenPx = cfg.minuteLengthRatio * r
          drawMainHand(
            center = center,
            angleDeg = minuteAngleDeg,
            tailLengthPx = backTailPx,
            tipLengthPx = minLenPx,
            widthPx = cfg.minuteWidthDp.dp.toPx(),
            cap = cfg.minuteCap,
            bodyColor = cfg.minuteColor,
            stripeColor = cfg.minuteStripeColor,
            stripeWidthPx = cfg.minuteStripeWidthDp.dp.toPx(),
            stripeTipOnly = cfg.minuteStripeTipOnly,
            stripeTipInsetPx = tipInsetPx,
            shadowOffset = shadowOffset,
            shadowColor = shadowColor,
          )

          // Seconds hand + optional counterweight
          val secLenPx = cfg.secondLengthRatio * r
          val secTailPx = cfg.secondTailDp.dp.toPx()
          val rad = (secondAngleDeg - 90f) * (PI / 180.0)
          val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
          val secStart = center - dir * secTailPx
          val secEnd = center + dir * secLenPx

          drawLine(
            color = shadowColor,
            start = secStart + shadowOffset,
            end = secEnd + shadowOffset,
            strokeWidth = cfg.secondWidthDp.dp.toPx(),
            cap = StrokeCap.Round,
          )
          drawLine(
            color = cfg.secondColor,
            start = secStart,
            end = secEnd,
            strokeWidth = cfg.secondWidthDp.dp.toPx(),
            cap = StrokeCap.Round,
          )

          cfg.counterweight?.let { cw ->
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
          val outerCapPx = cfg.centerCapOuterDp.dp.toPx()
          val innerCapPx = cfg.centerCapInnerDp.dp.toPx()
          drawCircle(color = shadowColor, radius = outerCapPx, center = center + shadowOffset)
          drawCircle(color = cfg.centerCapOuterColor, radius = outerCapPx, center = center)
          drawCircle(color = cfg.centerCapInnerColor, radius = innerCapPx, center = center)
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

  companion object {
    /** Creates an [ImageDialFace] backed by a persisted [PhotoStoredFace]. */
    fun fromPhotoStored(stored: PhotoStoredFace): ImageDialFace = ImageDialFace(
      id = stored.id,
      displayName = stored.displayName,
      dialSource = DialSource.ImageFile(stored.dialFile),
      config = null,
      photoConfig = stored.config,
    )
  }
}

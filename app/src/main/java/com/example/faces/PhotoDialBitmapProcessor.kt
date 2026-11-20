package com.example.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.example.faces.wff.WffPhotoManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

/** Result of safely validating and decoding a user-picked photo into working memory. */
internal sealed interface PhotoLoadResult {
  data class Success(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
  ) : PhotoLoadResult

  data class Error(val reason: String) : PhotoLoadResult
}

/**
 * Memory-safe photo decoding, EXIF upright rotation, tone adjustment, and circular baking
 * for Photo Dial watch faces. Refuses files > 25 MB or > 40 MP before decoding pixels.
 */
internal object PhotoDialBitmapProcessor {

  const val MAX_PICKED_BYTES = 25L * 1024L * 1024L
  const val MAX_PICKED_PIXELS = 40_000_000L
  const val MAX_WORKING_SIDE_PX = 1600
  const val MAX_SAVED_DIAL_PX = 1080

  /**
   * Validates file size (<= 25 MB) and image resolution (<= 40 MP), downsamples during decode,
   * and applies EXIF orientation upright from [uri].
   */
  fun loadPickedPhotoSafely(context: Context, uri: Uri): PhotoLoadResult {
    val appCtx = context.applicationContext
    val knownSize = queryUriSizeBytes(appCtx, uri)
    if (knownSize != null && knownSize > MAX_PICKED_BYTES) {
      return PhotoLoadResult.Error("Photo is over the 25 MB limit. Please pick a smaller image.")
    }

    val tempFile = File.createTempFile("photo_pick_", ".tmp", appCtx.cacheDir)
    return try {
      val streamOk = appCtx.contentResolver.openInputStream(uri)?.use { input ->
        copyStreamWithLimit(input, tempFile, MAX_PICKED_BYTES)
      } ?: return PhotoLoadResult.Error("Could not open the selected photo.")

      if (streamOk == null) {
        return PhotoLoadResult.Error("Photo is over the 25 MB limit. Please pick a smaller image.")
      }
      loadPhotoFileSafely(tempFile)
    } catch (err: Exception) {
      PhotoLoadResult.Error("Could not read photo: ${err.message ?: err.javaClass.simpleName}")
    } finally {
      runCatching { tempFile.delete() }
    }
  }

  /**
   * Validates size/megapixels, downsamples during decode, and applies EXIF rotation upright
   * from an already-buffered local [file].
   */
  fun loadPhotoFileSafely(file: File): PhotoLoadResult {
    if (!file.exists() || !file.isFile) {
      return PhotoLoadResult.Error("Photo file could not be found.")
    }
    if (file.length() <= 0L) {
      return PhotoLoadResult.Error("Selected photo file is empty.")
    }
    if (file.length() > MAX_PICKED_BYTES) {
      return PhotoLoadResult.Error("Photo is over the 25 MB limit. Please pick a smaller image.")
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    validateBounds(srcW, srcH)?.let { reason ->
      return PhotoLoadResult.Error(reason)
    }

    var sampleSize = 1
    while ((srcW / (sampleSize * 2)) >= MAX_WORKING_SIDE_PX &&
      (srcH / (sampleSize * 2)) >= MAX_WORKING_SIDE_PX
    ) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, decodeOpts) }.getOrNull()
      ?: return PhotoLoadResult.Error("Photo could not be decoded. The file may be corrupt.")

    val orientation = runCatching {
      ExifInterface(file.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val longest = maxOf(decoded.width, decoded.height)
    val scale = if (longest > MAX_WORKING_SIDE_PX) MAX_WORKING_SIDE_PX.toFloat() / longest.toFloat() else 1f
    val matrix = WffPhotoManager.buildExifMatrix(orientation, scale)
    val upright = if (matrix.isIdentity) {
      decoded
    } else {
      val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
      if (rotated !== decoded) {
        decoded.recycle()
      }
      rotated
    }
    return PhotoLoadResult.Success(
      bitmap = upright,
      originalWidth = srcW,
      originalHeight = srcH,
    )
  }

  /** Pure validation check for image bounds before allocating pixel buffers. */
  internal fun validateBounds(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) {
      return "Selected file is not a supported image."
    }
    val pixels = width.toLong() * height.toLong()
    if (pixels > MAX_PICKED_PIXELS) {
      val mp = ((pixels / 100_000L) / 10.0)
      return "Photo is too large ($mp MP). Images above 40 megapixels are refused to protect memory."
    }
    return null
  }

  /** Builds a combined brightness (-0.5..0.5), contrast (0.5..1.8), and saturation (0..2) ColorMatrix. */
  fun buildToneColorMatrix(
    brightness: Float,
    contrast: Float,
    saturation: Float,
  ): ColorMatrix {
    val satMatrix = ColorMatrix().apply { setSaturation(saturation.coerceIn(0f, 2f)) }
    val c = contrast.coerceIn(0.5f, 1.8f)
    val translate = (-0.5f * c + 0.5f + brightness.coerceIn(-0.5f, 0.5f)) * 255f
    val bcMatrix = ColorMatrix(
      floatArrayOf(
        c, 0f, 0f, 0f, translate,
        0f, c, 0f, 0f, translate,
        0f, 0f, c, 0f, translate,
        0f, 0f, 0f, 1f, 0f,
      ),
    )
    bcMatrix.preConcat(satMatrix)
    return bcMatrix
  }

  /**
   * Builds the exact bitmap->viewport transformation matrix centered inside a [viewportDiameterPx]
   * square dial stage. Shared between live crop preview and baked circular output.
   */
  fun buildCropMatrix(
    srcWidth: Int,
    srcHeight: Int,
    viewportDiameterPx: Float,
    zoom: Float,
    panXRatio: Float,
    panYRatio: Float,
    rotationDeg: Float,
  ): Matrix {
    val half = viewportDiameterPx * 0.5f
    val requestedPanX = panXRatio * half
    val requestedPanY = panYRatio * half
    val (clampedPanX, clampedPanY) = PhotoDialCropMath.clampScreenPan(
      panX = requestedPanX,
      panY = requestedPanY,
      srcWidth = srcWidth,
      srcHeight = srcHeight,
      targetDiameterPx = viewportDiameterPx,
      zoom = zoom,
      rotationDeg = rotationDeg,
    )
    val scale = PhotoDialCropMath.totalScale(srcWidth, srcHeight, viewportDiameterPx, zoom)
    return Matrix().apply {
      postTranslate(-srcWidth * 0.5f, -srcHeight * 0.5f)
      postScale(scale, scale)
      postRotate(PhotoDialCropMath.normalizeAngleDeg(rotationDeg))
      postTranslate(half + clampedPanX, half + clampedPanY)
    }
  }

  /**
   * Bakes the cropped, rotated, tone-adjusted circular watch dial image up to [MAX_SAVED_DIAL_PX]
   * (1080 px) with completely transparent pixels outside the circle.
   */
  fun bakeCircularDialBitmap(
    source: Bitmap,
    targetSidePx: Int = MAX_SAVED_DIAL_PX,
    zoom: Float,
    panXRatio: Float,
    panYRatio: Float,
    rotationDeg: Float,
    brightness: Float,
    contrast: Float,
    saturation: Float,
  ): Bitmap {
    val minSrc = minOf(source.width, source.height).coerceAtLeast(1)
    val idealSide = (minSrc / PhotoDialCropMath.clampZoom(zoom)).roundToInt().coerceIn(360, MAX_SAVED_DIAL_PX)
    val size = targetSidePx.coerceIn(128, MAX_SAVED_DIAL_PX).coerceAtMost(maxOf(idealSide, 720))

    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val clipPath = Path().apply {
      addCircle(size * 0.5f, size * 0.5f, size * 0.5f, Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(clipPath)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(buildToneColorMatrix(brightness, contrast, saturation))
    }
    val matrix = buildCropMatrix(
      srcWidth = source.width,
      srcHeight = source.height,
      viewportDiameterPx = size.toFloat(),
      zoom = zoom,
      panXRatio = panXRatio,
      panYRatio = panYRatio,
      rotationDeg = rotationDeg,
    )
    canvas.drawBitmap(source, matrix, paint)
    canvas.restore()
    return out
  }

  private fun queryUriSizeBytes(context: Context, uri: Uri): Long? =
    runCatching {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        afd.length.takeIf { it >= 0L }
      }
    }.getOrNull() ?: runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.SIZE)
          if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
        } else null
      }
    }.getOrNull()

  private fun copyStreamWithLimit(input: InputStream, dest: File, maxBytes: Long): Long? {
    val buf = ByteArray(16 * 1024)
    var total = 0L
    FileOutputStream(dest).use { out ->
      while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buf, 0, read)
      }
    }
    return total
  }
}

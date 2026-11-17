package com.example.faces.wff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Manages user-selected Watch Face Format photos picked via the zero-permission Android Photo Picker.
 * Applies EXIF orientation tags upright on import and decodes at slot pixel dimensions up to 1080px
 * so 4 GB RAM phones never hold full camera-resolution bitmaps or retain external URI permissions.
 */
internal object WffPhotoManager {

  private const val PHOTOS_DIR_NAME = "photos"
  const val MAX_PHOTO_SIDE_PX = 1080
  private const val FALLBACK_SLOT_SIDE_PX = 450

  /** Returns the private directory storing imported user photos for [faceId] and [configId]. */
  fun photosDir(context: Context, faceId: String, configId: String): File =
    File(File(File(File(context.filesDir, "wff"), faceId), PHOTOS_DIR_NAME), sanitizeId(configId))

  /**
   * Decodes, rotates upright per EXIF orientation, downscales longest side to [maxSidePx] (max 1080px),
   * and saves the picked [uris] for [configId], returning the saved private filenames in selection order.
   */
  fun importPhotos(
    context: Context,
    faceId: String,
    configId: String,
    uris: List<Uri>,
    maxSidePx: Int = MAX_PHOTO_SIDE_PX,
  ): List<String> {
    if (uris.isEmpty()) return emptyList()
    val clampedMax = maxSidePx.coerceIn(64, MAX_PHOTO_SIDE_PX)
    val dir = photosDir(context, faceId, configId).apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }

    val saved = ArrayList<String>(uris.size)
    val ts = System.currentTimeMillis()
    uris.forEachIndexed { idx, uri ->
      val bmp = decodeUprightDownscaledFromUri(context, uri, clampedMax) ?: return@forEachIndexed
      val outName = "photo_${ts}_$idx.jpg"
      val outFile = File(dir, outName)
      try {
        FileOutputStream(outFile).use { fos ->
          bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
        }
        saved += outName
      } finally {
        bmp.recycle()
      }
    }
    return saved
  }

  /**
   * Decodes persisted photo filenames at each `<Photos>` slot's rendered pixel size
   * (capped at [MAX_PHOTO_SIDE_PX] = 1080px on the longest side), keyed by PhotosConfiguration ID.
   */
  fun decodeFacePhotos(
    context: Context,
    faceId: String,
    savedPhotosByConfig: Map<String, List<String>>,
    slotSizesByConfigId: Map<String, Pair<Int, Int>> = emptyMap(),
  ): Map<String, List<Bitmap>> {
    if (savedPhotosByConfig.isEmpty()) return emptyMap()
    val result = HashMap<String, List<Bitmap>>(savedPhotosByConfig.size)
    for ((configId, names) in savedPhotosByConfig) {
      if (names.isEmpty()) continue
      val dir = photosDir(context, faceId, configId)
      if (!dir.exists()) continue
      val rawTarget = slotSizesByConfigId[configId] ?: (FALLBACK_SLOT_SIDE_PX to FALLBACK_SLOT_SIDE_PX)
      val (targetW, targetH) = clampDimsLongestSide(rawTarget.first, rawTarget.second, MAX_PHOTO_SIDE_PX)
      val list = ArrayList<Bitmap>(names.size)
      for (name in names) {
        val file = File(dir, name)
        if (!file.exists()) continue
        WffDrawHelpers.decodeBitmapFileForSize(file, targetW, targetH)?.let { list += it }
      }
      if (list.isNotEmpty()) {
        result[configId] = list
      }
    }
    return result
  }

  /** Clamps [w] and [h] proportionally so the longest side never exceeds [maxLongestSidePx]. */
  fun clampDimsLongestSide(
    w: Int,
    h: Int,
    maxLongestSidePx: Int = MAX_PHOTO_SIDE_PX,
  ): Pair<Int, Int> {
    val safeW = w.coerceAtLeast(16)
    val safeH = h.coerceAtLeast(16)
    val longest = maxOf(safeW, safeH)
    if (longest <= maxLongestSidePx) return safeW to safeH
    val ratio = maxLongestSidePx.toFloat() / longest.toFloat()
    return (safeW * ratio).toInt().coerceAtLeast(1) to (safeH * ratio).toInt().coerceAtLeast(1)
  }

  /** Deletes all copied user photos for [configId] on [faceId]. */
  fun clearConfigPhotos(context: Context, faceId: String, configId: String) {
    val dir = photosDir(context, faceId, configId)
    if (dir.exists()) {
      dir.deleteRecursively()
    }
  }

  /** Reads the EXIF orientation tag from [uri], defaulting to [ExifInterface.ORIENTATION_NORMAL]. */
  fun readExifOrientation(context: Context, uri: Uri): Int =
    runCatching {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
        )
      }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

  /** Decodes [uri] using subsampling, applies its EXIF rotation upright, and limits longest side to [maxSidePx]. */
  fun decodeUprightDownscaledFromUri(
    context: Context,
    uri: Uri,
    maxSidePx: Int = MAX_PHOTO_SIDE_PX,
  ): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    var sampleSize = 1
    while ((srcW / (sampleSize * 2)) >= maxSidePx && (srcH / (sampleSize * 2)) >= maxSidePx) {
      sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return null

    val orientation = readExifOrientation(context, uri)
    val longest = maxOf(decoded.width, decoded.height)
    val scale = if (longest > maxSidePx) maxSidePx.toFloat() / longest.toFloat() else 1f
    val matrix = buildExifMatrix(orientation, scale)
    if (matrix.isIdentity) {
      return decoded
    }
    val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (transformed !== decoded) {
      decoded.recycle()
    }
    return transformed
  }

  /** Builds a combined orientation + uniform-scale Matrix from an EXIF orientation constant. */
  internal fun buildExifMatrix(orientation: Int, scale: Float): Matrix {
    val m = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        m.postRotate(90f)
        m.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        m.postRotate(270f)
        m.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
    }
    if (scale < 0.999f) {
      m.postScale(scale, scale)
    }
    return m
  }

  private fun sanitizeId(raw: String): String =
    raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
}

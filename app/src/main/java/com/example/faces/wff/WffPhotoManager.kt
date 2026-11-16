package com.example.faces.wff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages user-selected Watch Face Format photos picked via the zero-permission Android Photo Picker.
 * Downscales and copies images into `files/wff/<faceId>/photos/<configId>/` so 4 GB RAM phones
 * never hold full camera-resolution bitmaps or retain external URI permissions.
 */
internal object WffPhotoManager {

  private const val PHOTOS_DIR_NAME = "photos"
  private const val DEFAULT_MAX_SIDE_PX = 450

  /** Returns the private directory storing imported user photos for [faceId] and [configId]. */
  fun photosDir(context: Context, faceId: String, configId: String): File =
    File(File(File(File(context.filesDir, "wff"), faceId), PHOTOS_DIR_NAME), sanitizeId(configId))

  /**
   * Decodes, downscales to [maxSidePx], and saves the picked [uris] for [configId],
   * returning the saved private filenames in selection order.
   */
  fun importPhotos(
    context: Context,
    faceId: String,
    configId: String,
    uris: List<Uri>,
    maxSidePx: Int = DEFAULT_MAX_SIDE_PX,
  ): List<String> {
    if (uris.isEmpty()) return emptyList()
    val dir = photosDir(context, faceId, configId).apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }

    val saved = ArrayList<String>(uris.size)
    val ts = System.currentTimeMillis()
    uris.forEachIndexed { idx, uri ->
      val bmp = decodeDownscaledFromUri(context, uri, maxSidePx) ?: return@forEachIndexed
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

  /** Decodes persisted photo filenames into downsampled Bitmaps keyed by PhotosConfiguration ID. */
  fun decodeFacePhotos(
    context: Context,
    faceId: String,
    savedPhotosByConfig: Map<String, List<String>>,
    targetPx: Int = DEFAULT_MAX_SIDE_PX,
  ): Map<String, List<Bitmap>> {
    if (savedPhotosByConfig.isEmpty()) return emptyMap()
    val result = HashMap<String, List<Bitmap>>(savedPhotosByConfig.size)
    for ((configId, names) in savedPhotosByConfig) {
      if (names.isEmpty()) continue
      val dir = photosDir(context, faceId, configId)
      if (!dir.exists()) continue
      val list = ArrayList<Bitmap>(names.size)
      for (name in names) {
        val file = File(dir, name)
        if (!file.exists()) continue
        WffDrawHelpers.decodeBitmapFileForSize(file, targetPx, targetPx)?.let { list += it }
      }
      if (list.isNotEmpty()) {
        result[configId] = list
      }
    }
    return result
  }

  /** Deletes all copied user photos for [configId] on [faceId]. */
  fun clearConfigPhotos(context: Context, faceId: String, configId: String) {
    val dir = photosDir(context, faceId, configId)
    if (dir.exists()) {
      dir.deleteRecursively()
    }
  }

  private fun decodeDownscaledFromUri(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
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

    if (decoded.width <= maxSidePx && decoded.height <= maxSidePx) {
      return decoded
    }
    val scale = min(maxSidePx.toFloat() / decoded.width, maxSidePx.toFloat() / decoded.height)
    val finalW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
    val finalH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(decoded, finalW, finalH, true)
    if (scaled !== decoded) {
      decoded.recycle()
    }
    return scaled
  }

  private fun sanitizeId(raw: String): String =
    raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
}

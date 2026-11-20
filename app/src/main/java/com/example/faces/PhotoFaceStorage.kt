package com.example.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Persisted user-created Photo Dial face stored in `files/photo_faces/<id>/`. */
internal data class PhotoStoredFace(
  val config: PhotoFaceConfig,
  val dir: File,
  val dialFile: File,
) {
  val id: String get() = config.id
  val displayName: String get() = config.name
}

/** Outcome of importing a Photo Dial `.zip` exported from AuraClock. */
internal sealed interface PhotoZipImportResult {
  data class Success(val stored: PhotoStoredFace) : PhotoZipImportResult
  data object NotAPhotoFaceZip : PhotoZipImportResult
  data class Error(val reason: String) : PhotoZipImportResult
}

/**
 * Manages offline per-folder storage (`files/photo_faces/<id>/dial.png` + `face.json`),
 * canonical-path safety, zip export, and zip re-import of Photo Dial watch faces.
 */
internal object PhotoFaceStorage {

  private const val PHOTO_FACES_DIR = "photo_faces"
  const val FACE_JSON_NAME = "face.json"
  const val MAX_FACE_JSON_BYTES = 2 * 1024 * 1024
  const val MAX_DIAL_IMAGE_BYTES = 8L * 1024L * 1024L
  const val MAX_EXPORT_ZIP_BYTES = 64L * 1024L * 1024L

  /** Loads all valid saved Photo Dial faces from `files/photo_faces/`. */
  fun loadAll(context: Context): List<PhotoStoredFace> {
    val root = rootDir(context.applicationContext)
    if (!root.exists() || !root.isDirectory) return emptyList()
    val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()

    val loaded = mutableListOf<PhotoStoredFace>()
    for (dir in dirs) {
      if (!isSafeChildDir(root, dir)) continue
      val jsonFile = safeChildFile(dir, FACE_JSON_NAME) ?: continue
      if (!jsonFile.exists() || !jsonFile.isFile || jsonFile.length() > MAX_FACE_JSON_BYTES) continue
      val raw = runCatching { jsonFile.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
      val parsed = PhotoFaceConfig.fromJson(raw) as? PhotoFaceConfig.ParseResult.Success ?: continue
      val dialFile = safeChildFile(dir, parsed.config.dialImageFile) ?: continue
      if (!dialFile.exists() || !dialFile.isFile || dialFile.length() <= 0L || dialFile.length() > MAX_DIAL_IMAGE_BYTES) {
        continue
      }
      loaded += PhotoStoredFace(config = parsed.config, dir = dir, dialFile = dialFile)
    }
    return loaded.sortedBy { it.config.updatedAtMs }
  }

  /** Saves a new or updated Photo Dial face with circular PNG dial image and `face.json`. */
  fun savePhotoFace(
    context: Context,
    config: PhotoFaceConfig,
    dialBitmap: Bitmap,
  ): PhotoStoredFace {
    val appCtx = context.applicationContext
    val root = rootDir(appCtx).apply { mkdirs() }
    val finalId = if (PhotoFaceConfig.isSafeIdentifier(config.id)) {
      config.id
    } else {
      generateUniqueId(appCtx, config.name)
    }
    val faceDir = safeChildFile(root, finalId)?.apply { mkdirs() }
      ?: throw IllegalArgumentException("Unsafe photo face id.")

    val dialFileName = PhotoFaceConfig.DEFAULT_DIAL_FILE
    val dialFile = safeChildFile(faceDir, dialFileName)
      ?: throw IllegalArgumentException("Unsafe dial image filename.")
    val tmpDial = File(faceDir, "$dialFileName.tmp")
    FileOutputStream(tmpDial).use { fos ->
      check(dialBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
        "Could not compress dial PNG."
      }
    }
    if (!tmpDial.renameTo(dialFile)) {
      tmpDial.copyTo(dialFile, overwrite = true)
      tmpDial.delete()
    }
    DialThumbnailCache.invalidateFile(dialFile)

    val finalConfig = config.copy(
      id = finalId,
      dialImageFile = dialFileName,
      updatedAtMs = System.currentTimeMillis(),
    )
    writeConfigAtomic(faceDir, finalConfig)
    return PhotoStoredFace(config = finalConfig, dir = faceDir, dialFile = dialFile)
  }

  /** Updates only `face.json` (e.g. hand styles, colors, date, name) while keeping the existing dial image. */
  fun updateConfigOnly(context: Context, config: PhotoFaceConfig): PhotoStoredFace? {
    val appCtx = context.applicationContext
    if (!PhotoFaceConfig.isSafeIdentifier(config.id)) return null
    val root = rootDir(appCtx)
    val faceDir = safeChildFile(root, config.id) ?: return null
    if (!faceDir.exists() || !faceDir.isDirectory) return null
    val dialFile = safeChildFile(faceDir, config.dialImageFile) ?: return null
    if (!dialFile.exists() || !dialFile.isFile) return null
    val updated = config.copy(updatedAtMs = System.currentTimeMillis())
    writeConfigAtomic(faceDir, updated)
    return PhotoStoredFace(config = updated, dir = faceDir, dialFile = dialFile)
  }

  /** Deletes a saved Photo Dial folder safely. */
  fun deleteFace(context: Context, faceId: String): Boolean {
    if (!PhotoFaceConfig.isSafeIdentifier(faceId)) return false
    val dir = safeChildFile(rootDir(context.applicationContext), faceId) ?: return false
    return if (dir.exists()) dir.deleteRecursively() else false
  }

  /** Generates a safe unique folder/face ID for a newly created photo dial face. */
  fun generateUniqueId(context: Context, name: String): String {
    val root = rootDir(context.applicationContext)
    val slug = name.lowercase(Locale.US)
      .replace(Regex("[^a-z0-9]+"), "_")
      .trim('_')
      .take(24)
      .ifBlank { "photo" }
    val now = System.currentTimeMillis()
    var candidate = "photo_${slug}_$now"
    var suffix = 1
    while (File(root, candidate).exists()) {
      candidate = "photo_${slug}_${now}_${suffix++}"
    }
    return candidate
  }

  /** Writes a self-contained `.zip` archive (`face.json` + dial image) to [outputStream]. */
  fun exportFaceToZipStream(stored: PhotoStoredFace, outputStream: OutputStream) {
    ZipOutputStream(outputStream).use { zip ->
      zip.putNextEntry(ZipEntry(FACE_JSON_NAME))
      val jsonBytes = stored.config.toJsonString().toByteArray(StandardCharsets.UTF_8)
      zip.write(jsonBytes)
      zip.closeEntry()

      zip.putNextEntry(ZipEntry(stored.config.dialImageFile))
      stored.dialFile.inputStream().use { input ->
        input.copyTo(zip)
      }
      zip.closeEntry()
    }
  }

  /** Exports [stored] to a user-selected document [uri] created via `CreateDocument`. */
  fun exportFaceToUri(context: Context, stored: PhotoStoredFace, uri: Uri): Result<Unit> =
    runCatching {
      context.contentResolver.openOutputStream(uri)?.use { out ->
        exportFaceToZipStream(stored, out)
      } ?: throw IllegalStateException("Could not open export destination.")
      Unit
    }

  /**
   * Attempts to import an exported Photo Dial `.zip` from [zipFile].
   * Returns [PhotoZipImportResult.NotAPhotoFaceZip] when the zip does not contain a `face.json`
   * (so callers can proceed with regular WFF `.zip` / `.apk` handling).
   */
  fun importFromZipFile(context: Context, zipFile: File): PhotoZipImportResult {
    if (!zipFile.exists() || zipFile.length() > MAX_EXPORT_ZIP_BYTES) {
      return PhotoZipImportResult.Error("Archive exceeds the 64 MB limit.")
    }
    return try {
      ZipFile(zipFile).use { zip ->
        val entries = zip.entries().asSequence()
          .filter { !it.isDirectory && isSafeZipPath(it.name) }
          .toList()
        val jsonEntry = entries.firstOrNull {
          it.name.equals(FACE_JSON_NAME, ignoreCase = true) ||
            it.name.lowercase(Locale.US).endsWith("/$FACE_JSON_NAME")
        } ?: return PhotoZipImportResult.NotAPhotoFaceZip

        if (jsonEntry.size > MAX_FACE_JSON_BYTES) {
          return PhotoZipImportResult.Error("face.json inside archive exceeds 2 MB.")
        }
        val jsonBytes = zip.getInputStream(jsonEntry).use { readBytesWithLimit(it, MAX_FACE_JSON_BYTES) }
          ?: return PhotoZipImportResult.Error("face.json inside archive is corrupted or oversized.")
        val parsed = PhotoFaceConfig.fromJson(String(jsonBytes, StandardCharsets.UTF_8))
        if (parsed is PhotoFaceConfig.ParseResult.Error) {
          return PhotoZipImportResult.Error(parsed.reason)
        }
        val config = (parsed as PhotoFaceConfig.ParseResult.Success).config
        val imgEntry = entries.firstOrNull { entry ->
          val fileName = entry.name.substringAfterLast('/')
          fileName.equals(config.dialImageFile, ignoreCase = true)
        } ?: return PhotoZipImportResult.Error("Dial image '${config.dialImageFile}' is missing from archive.")

        if (imgEntry.size > MAX_DIAL_IMAGE_BYTES) {
          return PhotoZipImportResult.Error("Dial image exceeds 8 MB size limit.")
        }

        val appCtx = context.applicationContext
        val tempImg = File.createTempFile("photo_zip_img_", ".tmp", appCtx.cacheDir)
        try {
          val written = zip.getInputStream(imgEntry).use { input ->
            copyStreamWithLimit(input, tempImg, MAX_DIAL_IMAGE_BYTES)
          } ?: return PhotoZipImportResult.Error("Dial image exceeds 8 MB size limit.")

          if (written <= 0L) {
            return PhotoZipImportResult.Error("Dial image inside archive is empty.")
          }

          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeFile(tempImg.absolutePath, bounds)
          PhotoDialBitmapProcessor.validateBounds(bounds.outWidth, bounds.outHeight)?.let { err ->
            return PhotoZipImportResult.Error(err)
          }

          val root = rootDir(appCtx).apply { mkdirs() }
          val targetId = if (!File(root, config.id).exists()) {
            config.id
          } else {
            generateUniqueId(appCtx, config.name)
          }
          val faceDir = safeChildFile(root, targetId)?.apply { mkdirs() }
            ?: return PhotoZipImportResult.Error("Unsafe destination directory.")
          val destImg = safeChildFile(faceDir, config.dialImageFile)
            ?: return PhotoZipImportResult.Error("Unsafe dial image filename.")
          tempImg.copyTo(destImg, overwrite = true)

          val savedConfig = config.copy(
            id = targetId,
            updatedAtMs = System.currentTimeMillis(),
          )
          writeConfigAtomic(faceDir, savedConfig)
          return PhotoZipImportResult.Success(
            PhotoStoredFace(config = savedConfig, dir = faceDir, dialFile = destImg),
          )
        } finally {
          runCatching { tempImg.delete() }
        }
      }
    } catch (err: Exception) {
      PhotoZipImportResult.Error("Could not read archive: ${err.message ?: err.javaClass.simpleName}")
    }
  }

  private fun writeConfigAtomic(dir: File, config: PhotoFaceConfig) {
    val outFile = safeChildFile(dir, FACE_JSON_NAME) ?: return
    val tmp = File(dir, "$FACE_JSON_NAME.tmp")
    tmp.writeText(config.toJsonString(), StandardCharsets.UTF_8)
    if (!tmp.renameTo(outFile)) {
      tmp.copyTo(outFile, overwrite = true)
      tmp.delete()
    }
  }

  private fun rootDir(context: Context): File = File(context.filesDir, PHOTO_FACES_DIR)

  private fun isSafeZipPath(entryName: String): Boolean {
    if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains('\\')) return false
    return !entryName.split('/').any { it == ".." }
  }

  private fun isSafeChildDir(root: File, dir: File): Boolean {
    val rootCanonical = root.canonicalPath + File.separator
    return dir.canonicalPath.startsWith(rootCanonical)
  }

  private fun safeChildFile(dir: File, childName: String): File? {
    if (childName.isBlank() || childName.contains('/') || childName.contains('\\') || childName.contains("..")) {
      return null
    }
    val dirCanonical = dir.canonicalPath + File.separator
    val target = File(dir, childName)
    return if (target.canonicalPath.startsWith(dirCanonical)) target else null
  }

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

  private fun readBytesWithLimit(input: InputStream, maxBytes: Int): ByteArray? {
    val buf = ByteArray(16 * 1024)
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    while (true) {
      val read = input.read(buf)
      if (read <= 0) break
      total += read
      if (total > maxBytes) return null
      chunks += buf.copyOf(read)
    }
    val out = ByteArray(total)
    var pos = 0
    for (chunk in chunks) {
      System.arraycopy(chunk, 0, out, pos, chunk.size)
      pos += chunk.size
    }
    return out
  }
}

package com.example.core

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Metadata for a user-imported `.ttf` / `.otf` font stored in app-private `files/fonts/`. */
data class ImportedFont(
  val id: String,
  val displayName: String,
  val fileName: String,
  val fileSizeBytes: Long,
  val importedAtMs: Long,
)

/** Outcome of importing and validating a custom `.ttf` or `.otf` font. */
sealed interface FontImportResult {
  data class Success(val font: ImportedFont) : FontImportResult
  data class Error(val reason: String) : FontImportResult
}

/**
 * Shared offline storage and validation helper for user-imported `.ttf` / `.otf` fonts.
 * Copies fonts into private `files/fonts/`, enforces the 5 MB limit, validates with
 * [Typeface.createFromFile], and caches decoded [Typeface] objects safely.
 */
object CustomFontStorage {

  const val MAX_FONT_BYTES = 5L * 1024L * 1024L
  private const val MIN_FONT_BYTES = 64L
  private const val FONTS_DIR_NAME = "fonts"
  private const val INDEX_FILE_NAME = "fonts_index.json"

  private val validExtensions = setOf("ttf", "otf")
  private val typefaceCache = LruCache<String, Typeface>(16)

  private val _fontsFlow = MutableStateFlow<List<ImportedFont>>(emptyList())
  val fontsFlow: StateFlow<List<ImportedFont>> = _fontsFlow.asStateFlow()

  @Volatile
  private var initialized = false

  /** Loads all valid saved custom fonts from `files/fonts/` and refreshes [fontsFlow]. */
  fun loadAll(context: Context): List<ImportedFont> {
    val appCtx = context.applicationContext
    val dir = fontsRootDir(appCtx)
    val loaded = readIndexSafe(dir).filter { entry ->
      val f = safeChildFile(dir, entry.fileName)
      f != null && f.exists() && f.isFile && f.length() in MIN_FONT_BYTES..MAX_FONT_BYTES
    }
    _fontsFlow.value = loaded
    initialized = true
    return loaded
  }

  /** Ensures [fontsFlow] has been populated at least once. */
  fun ensureInitialized(context: Context): List<ImportedFont> {
    if (!initialized) {
      synchronized(this) {
        if (!initialized) {
          return loadAll(context)
        }
      }
    }
    return _fontsFlow.value
  }

  /** Imports a `.ttf` or `.otf` font from [uri] without retaining external URI permissions. */
  fun importFromUri(context: Context, uri: Uri): FontImportResult {
    val appCtx = context.applicationContext
    val displayName = resolveDisplayName(appCtx, uri) ?: "custom_font.ttf"
    return runCatching {
      appCtx.contentResolver.openInputStream(uri)?.use { stream ->
        importFromStream(appCtx, displayName, stream)
      } ?: FontImportResult.Error("Could not open the selected font file.")
    }.getOrElse { err ->
      FontImportResult.Error("Font import failed: ${err.message ?: err.javaClass.simpleName}")
    }
  }

  /** Copies [inputStream] safely, validates size/sfnt header/Typeface loading, and persists it. */
  fun importFromStream(
    context: Context,
    sourceFileName: String,
    inputStream: InputStream,
  ): FontImportResult {
    val appCtx = context.applicationContext
    ensureInitialized(appCtx)

    val cleanSourceName = sourceFileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "custom_font.ttf" }
    val ext = cleanSourceName.substringAfterLast('.', "").lowercase(Locale.US)
    if (ext.isNotEmpty() && ext !in validExtensions) {
      return FontImportResult.Error("Only .ttf and .otf font files are supported.")
    }
    val finalExt = if (ext in validExtensions) ext else "ttf"

    val root = fontsRootDir(appCtx).apply { mkdirs() }
    val tempFile = File.createTempFile("font_import_", ".$finalExt", appCtx.cacheDir)
    try {
      val bytesWritten = copyStreamWithLimit(inputStream, tempFile, MAX_FONT_BYTES)
        ?: return FontImportResult.Error("Font file exceeds the 5 MB size limit.")

      if (bytesWritten < MIN_FONT_BYTES) {
        return FontImportResult.Error("Font file is empty or too small to be a valid font.")
      }

      val validation = validateFontFile(tempFile)
      if (validation is FontValidation.Invalid) {
        return FontImportResult.Error(validation.reason)
      }

      val baseLabel = cleanSourceName.substringBeforeLast('.').ifBlank { "Custom Font" }
      val id = generateFontId(baseLabel, _fontsFlow.value)
      val storedFileName = "$id.$finalExt"
      val destFile = safeChildFile(root, storedFileName)
        ?: return FontImportResult.Error("Invalid font destination path.")

      tempFile.copyTo(destFile, overwrite = true)

      val entry = ImportedFont(
        id = id,
        displayName = baseLabel.take(48),
        fileName = storedFileName,
        fileSizeBytes = destFile.length(),
        importedAtMs = System.currentTimeMillis(),
      )
      val updated = (_fontsFlow.value + entry).sortedBy { it.importedAtMs }
      writeIndexSafe(root, updated)
      _fontsFlow.value = updated
      typefaceCache.remove(id)
      return FontImportResult.Success(entry)
    } catch (err: Exception) {
      return FontImportResult.Error("Failed to read font file: ${err.message ?: err.javaClass.simpleName}")
    } finally {
      runCatching { tempFile.delete() }
    }
  }

  /** Resolves a loaded [Typeface] for [fontId], returning null if missing, corrupt, or Default. */
  fun resolveTypeface(context: Context, fontId: String?): Typeface? {
    val id = fontId?.trim()?.takeIf { it.isNotEmpty() && it != AuraPrefs.FONT_DEFAULT_ID } ?: return null
    typefaceCache.get(id)?.let { return it }

    val appCtx = context.applicationContext
    val list = ensureInitialized(appCtx)
    val entry = list.firstOrNull { it.id == id } ?: return null
    val dir = fontsRootDir(appCtx)
    val file = safeChildFile(dir, entry.fileName) ?: return null
    if (!file.exists() || file.length() !in MIN_FONT_BYTES..MAX_FONT_BYTES) return null

    val loaded = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
    typefaceCache.put(id, loaded)
    return loaded
  }

  /** Deletes an imported font by [fontId] and clears any face font references pointing to it. */
  fun deleteFont(context: Context, fontId: String): Boolean {
    val appCtx = context.applicationContext
    ensureInitialized(appCtx)
    val current = _fontsFlow.value
    val target = current.firstOrNull { it.id == fontId } ?: return false
    val dir = fontsRootDir(appCtx)
    safeChildFile(dir, target.fileName)?.let { runCatching { it.delete() } }
    typefaceCache.remove(fontId)

    val updated = current.filterNot { it.id == fontId }
    writeIndexSafe(dir, updated)
    _fontsFlow.value = updated
    CoreSingletons.prefs(appCtx).clearFontReferences(fontId)
    return true
  }

  /** Clears the in-memory font cache and resets initialized state (for unit tests). */
  internal fun resetForTest() {
    typefaceCache.evictAll()
    _fontsFlow.value = emptyList()
    initialized = false
  }

  internal sealed interface FontValidation {
    data object Valid : FontValidation
    data class Invalid(val reason: String) : FontValidation
  }

  internal fun validateFontFile(file: File): FontValidation {
    if (!file.exists() || file.length() > MAX_FONT_BYTES) {
      return FontValidation.Invalid("Font file exceeds the 5 MB size limit.")
    }
    if (file.length() < MIN_FONT_BYTES) {
      return FontValidation.Invalid("Font file is corrupted or incomplete.")
    }
    val header = ByteArray(4)
    val readCount = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
    if (readCount < 4 || !isSupportedSfntHeader(header)) {
      return FontValidation.Invalid("File is not a valid .ttf or .otf font.")
    }
    return try {
      val tf = Typeface.createFromFile(file)
      val paint = Paint().apply {
        typeface = tf
        textSize = 24f
      }
      val measured = paint.measureText("12:45")
      if (!measured.isFinite()) {
        FontValidation.Invalid("Font failed rendering validation.")
      } else {
        FontValidation.Valid
      }
    } catch (_: Throwable) {
      FontValidation.Invalid("Could not load font. The file may be corrupt.")
    }
  }

  internal fun isSupportedSfntHeader(header: ByteArray): Boolean {
    if (header.size < 4) return false
    val b0 = header[0].toInt() and 0xFF
    val b1 = header[1].toInt() and 0xFF
    val b2 = header[2].toInt() and 0xFF
    val b3 = header[3].toInt() and 0xFF
    val isTrueType = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
    val isOpenTypeCff = b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F // OTTO
    val isAppleTrue = b0 == 0x74 && b1 == 0x72 && b2 == 0x75 && b3 == 0x65 // true
    val isTyp1 = b0 == 0x74 && b1 == 0x79 && b2 == 0x70 && b3 == 0x31 // typ1
    return isTrueType || isOpenTypeCff || isAppleTrue || isTyp1
  }

  private fun fontsRootDir(context: Context): File = File(context.filesDir, FONTS_DIR_NAME)

  private fun safeChildFile(dir: File, childName: String): File? {
    if (childName.isBlank() || childName.contains('/') || childName.contains('\\') || childName.contains("..")) {
      return null
    }
    val dirCanonical = dir.canonicalPath + File.separator
    val target = File(dir, childName)
    return if (target.canonicalPath.startsWith(dirCanonical)) target else null
  }

  private fun generateFontId(baseLabel: String, existing: List<ImportedFont>): String {
    val slug = baseLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
      .take(24).ifBlank { "font" }
    val existingIds = existing.map { it.id }.toSet()
    var candidate = "font_${slug}_${System.currentTimeMillis()}"
    var suffix = 1
    while (candidate in existingIds) {
      candidate = "font_${slug}_${System.currentTimeMillis()}_${suffix++}"
    }
    return candidate
  }

  private fun readIndexSafe(dir: File): List<ImportedFont> {
    val indexFile = File(dir, INDEX_FILE_NAME)
    if (!indexFile.exists() || !indexFile.isFile || indexFile.length() > MAX_FONT_BYTES) return emptyList()
    return runCatching {
      val arr = JSONArray(indexFile.readText(StandardCharsets.UTF_8))
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val id = obj.optString("id", "").trim()
          val displayName = obj.optString("displayName", "").trim()
          val fileName = obj.optString("fileName", "").trim()
          val sizeBytes = obj.optLong("fileSizeBytes", 0L)
          val importedAtMs = obj.optLong("importedAtMs", 0L)
          if (id.isNotEmpty() && displayName.isNotEmpty() && fileName.isNotEmpty() &&
            !id.contains("..") && !id.contains('/') && safeChildFile(dir, fileName) != null
          ) {
            add(ImportedFont(id, displayName, fileName, sizeBytes, importedAtMs))
          }
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun writeIndexSafe(dir: File, list: List<ImportedFont>) {
    dir.mkdirs()
    val arr = JSONArray()
    for (item in list) {
      arr.put(
        JSONObject().apply {
          put("id", item.id)
          put("displayName", item.displayName)
          put("fileName", item.fileName)
          put("fileSizeBytes", item.fileSizeBytes)
          put("importedAtMs", item.importedAtMs)
        },
      )
    }
    val tmp = File(dir, "$INDEX_FILE_NAME.tmp")
    tmp.writeText(arr.toString(2), StandardCharsets.UTF_8)
    tmp.renameTo(File(dir, INDEX_FILE_NAME))
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

  private fun resolveDisplayName(context: Context, uri: Uri): String? =
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) cursor.getString(idx) else null
        } else null
      }
    }.getOrNull()
}

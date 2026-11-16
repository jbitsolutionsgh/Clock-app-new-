package com.example.faces.wff

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Result of importing a WFF watch face from .apk, .zip, or standalone watchface.xml. */
internal sealed class WffImportResult {
  /** Import succeeded and persisted the watch face into private app storage. */
  data class Success(val entry: WffStoredFace) : WffImportResult()

  /** Import failed with a clear human-readable explanation. */
  data class Error(val reason: String) : WffImportResult()
}

/** Persisted WFF watch face loaded from files/wff/<id>/ with resolved image & font files. */
internal data class WffStoredFace(
  val id: String,
  val displayName: String,
  val face: WffWatchFace,
  val imagesDir: File,
  val fontsDir: File,
  val resourceFileMap: Map<String, String>,
  val fontFileMap: Map<String, String>,
  val report: WffCompatibilityReport,
  val sourceFileName: String,
  val importedAtMs: Long,
)

/**
 * Manages offline import, extraction, caching, loading, and deletion of WFF watch faces under
 * app-private storage (files/wff/<id>/). Never persists document URI permissions or executes APK code.
 */
internal object WffStorageManager {

  private const val WFF_DIR_NAME = "wff"
  private const val WATCHFACE_XML_NAME = "watchface.xml"
  private const val META_JSON_NAME = "meta.json"
  private const val IMAGES_DIR_NAME = "images"
  private const val FONTS_DIR_NAME = "fonts"
  private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
  private const val MAX_XML_BYTES = 2 * 1024 * 1024
  private const val MAX_ARSC_BYTES = 8 * 1024 * 1024
  private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024L

  private val rasterExtensions = listOf(".webp", ".png", ".jpg", ".jpeg", ".bmp")
  private val fontExtensions = listOf(".ttf", ".otf", ".ttc")

  /** Imports a WFF .apk, .zip, or plain watchface.xml from [uri] without taking URI permissions. */
  fun importFromUri(context: Context, uri: Uri): WffImportResult {
    val fileName = resolveDisplayName(context, uri) ?: "imported_watchface"
    return runCatching {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        importFromStream(context, fileName, stream)
      } ?: WffImportResult.Error("Could not open the selected file.")
    }.getOrElse { err ->
      WffImportResult.Error("Import failed: ${err.message ?: err.javaClass.simpleName}")
    }
  }

  /** Imports a WFF .apk, .zip, or plain watchface.xml directly from [inputStream]. */
  fun importFromStream(
    context: Context,
    sourceFileName: String,
    inputStream: InputStream,
  ): WffImportResult {
    val tempFile = File.createTempFile("wff_import_", ".tmp", context.cacheDir)
    return try {
      copyStreamWithLimit(inputStream, tempFile, MAX_ARCHIVE_BYTES)
        ?: return WffImportResult.Error("Selected file exceeds the 64 MB size limit.")

      if (tempFile.length() < 4L) return WffImportResult.Error("Selected file is empty.")

      val header = ByteArray(4)
      tempFile.inputStream().use { it.read(header) }
      if (WffXmlParser.isBinaryXml(header)) {
        return WffImportResult.Error(
          "Selected XML is compiled binary XML. Provide plain-text watchface.xml or a .apk/.zip package.",
        )
      }

      val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())

      if (isZip) importZipArchive(context, sourceFileName, tempFile)
      else importStandaloneXml(context, sourceFileName, tempFile)
    } catch (err: Exception) {
      WffImportResult.Error("Failed to read file: ${err.message ?: err.javaClass.simpleName}")
    } finally {
      tempFile.delete()
    }
  }

  /** Loads all previously imported WFF watch faces from app-private files/wff storage. */
  fun loadAll(context: Context): List<WffStoredFace> {
    val root = wffRootDir(context)
    if (!root.exists() || !root.isDirectory) return emptyList()
    val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()

    val loaded = mutableListOf<WffStoredFace>()
    for (dir in dirs) {
      val metaFile = File(dir, META_JSON_NAME)
      val xmlFile = File(dir, WATCHFACE_XML_NAME)
      if (!xmlFile.exists()) continue

      val meta = runCatching { JSONObject(metaFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
      val faceId = meta?.optString("id")?.ifBlank { null } ?: dir.name
      val sourceFileName = meta?.optString("sourceFileName")?.ifBlank { null } ?: dir.name
      val importedAtMs = meta?.optLong("importedAtMs") ?: dir.lastModified()
      val savedResourceMap = readStringMap(meta?.optJSONObject("resourceFileMap"))
      val savedFontMap = readStringMap(meta?.optJSONObject("fontFileMap"))
      val savedMissing = readStringList(meta?.optJSONArray("missingResources"))

      val xmlText = runCatching { xmlFile.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
      val parsed = WffXmlParser.parse(xmlText, faceId, baseNameWithoutExt(sourceFileName))
      if (parsed !is WffParseResult.Success) continue

      val imagesDir = File(dir, IMAGES_DIR_NAME)
      val fontsDir = File(dir, FONTS_DIR_NAME)
      val mergedReport = appendMissingToReport(parsed.face.report, savedMissing)
      val finalFace = parsed.face.copy(report = mergedReport)
      loaded += WffStoredFace(
        id = faceId,
        displayName = finalFace.name,
        face = finalFace,
        imagesDir = imagesDir,
        fontsDir = fontsDir,
        resourceFileMap = savedResourceMap,
        fontFileMap = savedFontMap,
        report = mergedReport,
        sourceFileName = sourceFileName,
        importedAtMs = importedAtMs,
      )
    }
    return loaded.sortedBy { it.importedAtMs }
  }

  /** Permanently deletes an imported WFF watch face folder by [faceId]. */
  fun deleteFace(context: Context, faceId: String): Boolean {
    if (faceId.isBlank() || faceId.contains('/') || faceId.contains("..")) return false
    val dir = File(wffRootDir(context), faceId)
    return if (dir.exists()) dir.deleteRecursively() else false
  }

  private fun importStandaloneXml(
    context: Context,
    sourceFileName: String,
    xmlTempFile: File,
  ): WffImportResult {
    if (xmlTempFile.length() > MAX_XML_BYTES) return WffImportResult.Error("watchface.xml exceeds 2 MB limit.")
    val rawBytes = xmlTempFile.readBytes()
    if (WffXmlParser.isBinaryXml(rawBytes)) {
      return WffImportResult.Error("watchface.xml is compiled binary XML, which is not supported.")
    }
    val xmlText = String(rawBytes, StandardCharsets.UTF_8)
    val id = generateFaceId(context, sourceFileName)
    val parsed = WffXmlParser.parse(xmlText, id, baseNameWithoutExt(sourceFileName))
    if (parsed is WffParseResult.Error) return WffImportResult.Error(parsed.reason)
    val parsedFace = (parsed as WffParseResult.Success).face

    val missing = parsedFace.referencedResources.toList()
    val mergedReport = appendMissingToReport(parsedFace.report, missing)
    val finalFace = parsedFace.copy(report = mergedReport)

    val faceDir = File(wffRootDir(context), id).apply { mkdirs() }
    val imagesDir = File(faceDir, IMAGES_DIR_NAME).apply { mkdirs() }
    val fontsDir = File(faceDir, FONTS_DIR_NAME).apply { mkdirs() }
    File(faceDir, WATCHFACE_XML_NAME).writeText(xmlText, StandardCharsets.UTF_8)

    val nowMs = System.currentTimeMillis()
    writeMetaJson(
      file = File(faceDir, META_JSON_NAME),
      id = id,
      sourceFileName = sourceFileName,
      importedAtMs = nowMs,
      resourceFileMap = emptyMap(),
      fontFileMap = emptyMap(),
      missingResources = missing,
    )

    return WffImportResult.Success(
      WffStoredFace(
        id = id,
        displayName = finalFace.name,
        face = finalFace,
        imagesDir = imagesDir,
        fontsDir = fontsDir,
        resourceFileMap = emptyMap(),
        fontFileMap = emptyMap(),
        report = mergedReport,
        sourceFileName = sourceFileName,
        importedAtMs = nowMs,
      ),
    )
  }

  private fun importZipArchive(
    context: Context,
    sourceFileName: String,
    zipTempFile: File,
  ): WffImportResult {
    ZipFile(zipTempFile).use { zip ->
      val allEntries = zip.entries().asSequence()
        .filter { !it.isDirectory && isSafeZipPath(it.name) }
        .toList()

      val xmlEntry = allEntries.firstOrNull { it.name.equals("res/raw/watchface.xml", ignoreCase = true) }
        ?: allEntries.firstOrNull {
          it.name.lowercase(Locale.US).startsWith("res/raw") &&
            it.name.lowercase(Locale.US).endsWith("/watchface.xml")
        }
        ?: allEntries.firstOrNull { it.name.equals("watchface.xml", ignoreCase = true) }
        ?: return WffImportResult.Error("No res/raw/watchface.xml or watchface.xml found in archive.")

      if (xmlEntry.size > MAX_XML_BYTES) return WffImportResult.Error("watchface.xml exceeds 2 MB limit.")
      val xmlBytes = zip.getInputStream(xmlEntry).use { readBytesWithLimit(it, MAX_XML_BYTES) }
        ?: return WffImportResult.Error("Failed to read watchface.xml from archive.")

      if (WffXmlParser.isBinaryXml(xmlBytes)) {
        return WffImportResult.Error(
          "res/raw/watchface.xml is compiled binary XML. WFF watch faces must contain plain-text XML.",
        )
      }

      val xmlText = String(xmlBytes, StandardCharsets.UTF_8)
      val id = generateFaceId(context, sourceFileName)
      val parsed = WffXmlParser.parse(xmlText, id, baseNameWithoutExt(sourceFileName))
      if (parsed is WffParseResult.Error) return WffImportResult.Error(parsed.reason)
      val parsedFace = (parsed as WffParseResult.Success).face

      val entryByLowerPath = allEntries.associateBy { it.name.lowercase(Locale.US) }
      var arscMap: Map<String, String>? = null
      fun resolveArsc(): Map<String, String> {
        arscMap?.let { return it }
        val arscEntry = entryByLowerPath["resources.arsc"]
        val loaded = if (arscEntry != null && arscEntry.size <= MAX_ARSC_BYTES) {
          zip.getInputStream(arscEntry).use { readBytesWithLimit(it, MAX_ARSC_BYTES) }
            ?.let { ArscResourceTableReader.readDrawableResourcePaths(it) }
            ?: emptyMap()
        } else emptyMap()
        arscMap = loaded
        return loaded
      }

      val faceDir = File(wffRootDir(context), id).apply { mkdirs() }
      val imagesDir = File(faceDir, IMAGES_DIR_NAME).apply { mkdirs() }
      val fontsDir = File(faceDir, FONTS_DIR_NAME).apply { mkdirs() }

      val resourceFileMap = LinkedHashMap<String, String>()
      val unresolved = mutableListOf<String>()

      for (logicalRes in parsedFace.referencedResources) {
        val cleanKey = cleanResourceKey(logicalRes)
        val matched = findEntryByKey(allEntries, entryByLowerPath, cleanKey, logicalRes, rasterExtensions, ::resolveArsc)
        if (matched != null) {
          val ext = extensionOf(matched.name, ".png")
          val outName = "${sanitizeSlug(cleanKey)}_${resourceFileMap.size}$ext"
          val outFile = safeChildFile(imagesDir, outName)
          if (outFile != null && zip.getInputStream(matched).use { copyStreamWithLimit(it, outFile, MAX_RESOURCE_BYTES) != null }) {
            resourceFileMap[logicalRes] = outName
            continue
          }
        }
        unresolved += logicalRes
      }

      val fontFileMap = LinkedHashMap<String, String>()
      for (fontName in parsedFace.referencedFonts) {
        val cleanKey = cleanResourceKey(fontName).removePrefix("@font/")
        val matched = findEntryByKey(allEntries, entryByLowerPath, cleanKey, fontName, fontExtensions, ::resolveArsc)
        if (matched != null) {
          val ext = extensionOf(matched.name, ".ttf")
          val outName = "${sanitizeSlug(cleanKey)}_${fontFileMap.size}$ext"
          val outFile = safeChildFile(fontsDir, outName)
          if (outFile != null && zip.getInputStream(matched).use { copyStreamWithLimit(it, outFile, MAX_RESOURCE_BYTES) != null }) {
            fontFileMap[fontName] = outName
          }
        }
      }

      val mergedReport = appendMissingToReport(parsedFace.report, unresolved)
      val finalFace = parsedFace.copy(report = mergedReport)
      File(faceDir, WATCHFACE_XML_NAME).writeText(xmlText, StandardCharsets.UTF_8)

      val nowMs = System.currentTimeMillis()
      writeMetaJson(
        file = File(faceDir, META_JSON_NAME),
        id = id,
        sourceFileName = sourceFileName,
        importedAtMs = nowMs,
        resourceFileMap = resourceFileMap,
        fontFileMap = fontFileMap,
        missingResources = unresolved,
      )

      return WffImportResult.Success(
        WffStoredFace(
          id = id,
          displayName = finalFace.name,
          face = finalFace,
          imagesDir = imagesDir,
          fontsDir = fontsDir,
          resourceFileMap = resourceFileMap,
          fontFileMap = fontFileMap,
          report = mergedReport,
          sourceFileName = sourceFileName,
          importedAtMs = nowMs,
        ),
      )
    }
  }

  private fun appendMissingToReport(
    base: WffCompatibilityReport,
    missing: List<String>,
  ): WffCompatibilityReport {
    if (missing.isEmpty()) return base
    val items = base.items.toMutableList()
    for (res in missing) {
      items += WffReportItem(
        element = "Resource ($res)",
        status = WffElementStatus.SKIPPED,
        detail = "File '$res' could not be found in archive.",
      )
    }
    return base.copy(items = items, missingResources = missing)
  }

  private fun findEntryByKey(
    allEntries: List<ZipEntry>,
    byLowerPath: Map<String, ZipEntry>,
    cleanKey: String,
    rawRef: String,
    allowedExtensions: List<String>,
    arscProvider: () -> Map<String, String>,
  ): ZipEntry? {
    val targetLower = cleanKey.lowercase(Locale.US)
    val byName = allEntries.filter { entry ->
      val lower = entry.name.lowercase(Locale.US)
      if (!allowedExtensions.any { lower.endsWith(it) }) return@filter false
      val fileOnly = lower.substringAfterLast('/')
      val baseOnly = fileOnly.substringBeforeLast('.')
      baseOnly == targetLower || fileOnly == targetLower || lower == targetLower || lower.endsWith("/$targetLower")
    }.maxByOrNull { scoreArchiveCandidate(it.name) }

    if (byName != null) return byName

    val arsc = arscProvider()
    val mapped = arsc[cleanKey] ?: arsc[rawRef] ?: return null
    val mappedEntry = byLowerPath[mapped.lowercase(Locale.US)]
      ?: allEntries.firstOrNull { it.name.equals(mapped, ignoreCase = true) }
    return mappedEntry?.takeIf { e -> allowedExtensions.any { e.name.lowercase(Locale.US).endsWith(it) } }
  }

  private fun scoreArchiveCandidate(path: String): Int {
    val lower = path.lowercase(Locale.US)
    var score = 0
    if ("res/drawable" in lower || "res/mipmap" in lower || "res/font" in lower) score += 20
    if ("nodpi" in lower) score += 10
    if ("xxxhdpi" in lower) score += 9
    else if ("xxhdpi" in lower) score += 8
    else if ("xhdpi" in lower) score += 7
    else if ("hdpi" in lower) score += 6
    if (lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".ttf")) score += 5
    return score
  }

  private fun cleanResourceKey(raw: String): String =
    raw.trim()
      .removePrefix("@drawable/")
      .removePrefix("@mipmap/")
      .removePrefix("@font/")
      .substringAfterLast('/')
      .substringBeforeLast('.')

  private fun extensionOf(path: String, fallback: String): String {
    val idx = path.lastIndexOf('.')
    return if (idx >= 0) path.substring(idx).lowercase(Locale.US) else fallback
  }

  private fun isSafeZipPath(entryName: String): Boolean {
    if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains('\\')) return false
    return !entryName.split('/').any { it == ".." }
  }

  private fun safeChildFile(dir: File, childName: String): File? {
    val target = File(dir, childName)
    val dirCanonical = dir.canonicalPath + File.separator
    return if (target.canonicalPath.startsWith(dirCanonical)) target else null
  }

  private fun wffRootDir(context: Context): File = File(context.filesDir, WFF_DIR_NAME)

  private fun generateFaceId(context: Context, sourceFileName: String): String {
    val slug = sanitizeSlug(baseNameWithoutExt(sourceFileName)).take(24).ifBlank { "face" }
    val root = wffRootDir(context)
    var candidate = "wff_${slug}_${System.currentTimeMillis()}"
    var suffix = 1
    while (File(root, candidate).exists()) {
      candidate = "wff_${slug}_${System.currentTimeMillis()}_${suffix++}"
    }
    return candidate
  }

  private fun sanitizeSlug(raw: String): String =
    raw.lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { "res" }

  private fun baseNameWithoutExt(name: String): String =
    name.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }

  private fun writeMetaJson(
    file: File,
    id: String,
    sourceFileName: String,
    importedAtMs: Long,
    resourceFileMap: Map<String, String>,
    fontFileMap: Map<String, String>,
    missingResources: List<String>,
  ) {
    val root = JSONObject().apply {
      put("id", id)
      put("sourceFileName", sourceFileName)
      put("importedAtMs", importedAtMs)
      put("resourceFileMap", JSONObject(resourceFileMap))
      put("fontFileMap", JSONObject(fontFileMap))
      put("missingResources", JSONArray(missingResources))
    }
    file.writeText(root.toString(2), StandardCharsets.UTF_8)
  }

  private fun readStringMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
      val k = keys.next()
      out[k] = obj.optString(k)
    }
    return out
  }

  private fun readStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
      val v = arr.optString(i)
      if (v.isNotEmpty()) out += v
    }
    return out
  }

  private fun copyStreamWithLimit(input: InputStream, dest: File, maxBytes: Long): File? {
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
    return dest
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

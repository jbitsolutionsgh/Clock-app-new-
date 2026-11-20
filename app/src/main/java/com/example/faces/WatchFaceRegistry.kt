package com.example.faces

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.example.R
import com.example.core.CoreSingletons
import com.example.core.WffCompatibilitySummary
import com.example.core.WffSkippedDiagnostic
import com.example.faces.engine.DefinitionFace
import com.example.faces.engine.FaceAssetRepository
import com.example.faces.wff.WffFace
import com.example.faces.wff.WffImportResult
import com.example.faces.wff.WffStorageManager
import com.example.faces.wff.WffStoredFace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single registry list of all available watch faces, combining data-driven [DefinitionFace]
 * assets, the existing [ImageDialFace] collection, and user-imported [WffFace] packages.
 */
internal object WatchFaceRegistry {

  private val imageDialFaces: List<WatchFace> = listOf(
    ImageDialFace(
      id = "face_nike_dots",
      displayName = "Volt Sport",
      dialResId = R.drawable.face_nike_dots,
      config = DialConfig(
        hourLengthRatio = 0.46f,
        hourColor = Color(0xFFF5F5F5),
        hourWidthDp = 6f,
        hourCap = StrokeCap.Round,
        hourStripeColor = Color(0xFFCCFF00),
        hourStripeWidthDp = 3f,
        hourStripeTipOnly = true,
        minuteLengthRatio = 0.68f,
        minuteColor = Color(0xFFF5F5F5),
        minuteWidthDp = 4.5f,
        minuteCap = StrokeCap.Round,
        minuteStripeColor = Color(0xFFCCFF00),
        minuteStripeWidthDp = 3f,
        minuteStripeTipOnly = true,
        secondLengthRatio = 0.82f,
        secondColor = Color(0xFFFF2A48),
        secondWidthDp = 2f,
        secondTailDp = 20f,
        counterweight = CounterweightSpec(
          radiusDp = 3.5f,
          ringColor = Color(0xFFCCFF00),
          centerColor = Color(0xFFCCFF00),
          positionAlongPointerRatio = 0.7f,
        ),
        centerCapOuterDp = 5.5f,
        centerCapOuterColor = Color(0xFF0F172A),
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFCCFF00),
      ),
    ),
    ImageDialFace(
      id = "face_nike_numerals",
      displayName = "Bold Grey",
      dialResId = R.drawable.face_nike_numerals,
      config = DialConfig(
        hourLengthRatio = 0.40f,
        hourColor = Color.White,
        hourWidthDp = 7f,
        hourCap = StrokeCap.Round,
        minuteLengthRatio = 0.78f,
        minuteColor = Color.White,
        minuteWidthDp = 5f,
        minuteCap = StrokeCap.Round,
        secondLengthRatio = 0.85f,
        secondColor = Color(0xFFFF5A1F),
        secondWidthDp = 2f,
        secondTailDp = 20f,
        centerCapOuterDp = 6f,
        centerCapOuterColor = Color.White,
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFFF5A1F),
        handShadowAlpha = 0.6f,
      ),
    ),
    ImageDialFace(
      id = "face_yellow_racing",
      displayName = "Racing Yellow",
      dialResId = R.drawable.face_yellow_racing,
      config = DialConfig(
        hourLengthRatio = 0.48f,
        hourColor = Color(0xFF111111),
        hourWidthDp = 6.5f,
        hourCap = StrokeCap.Square,
        hourStripeColor = Color(0xFFFFFFFF),
        hourStripeWidthDp = 2.5f,
        minuteLengthRatio = 0.78f,
        minuteColor = Color(0xFF111111),
        minuteWidthDp = 5f,
        minuteCap = StrokeCap.Square,
        minuteStripeColor = Color(0xFFFFFFFF),
        minuteStripeWidthDp = 2f,
        secondLengthRatio = 0.86f,
        secondColor = Color(0xFFE00424),
        secondWidthDp = 1.8f,
        secondTailDp = 22f,
        counterweight = CounterweightSpec(
          radiusDp = 4f,
          ringColor = Color(0xFFE00424),
          centerColor = Color(0xFF111111),
          tailOffsetDp = 16f,
        ),
        centerCapOuterDp = 6.5f,
        centerCapOuterColor = Color(0xFF111111),
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFE00424),
      ),
    ),
    ImageDialFace(
      id = "face_lacoste_green",
      displayName = "Court Green",
      dialResId = R.drawable.face_lacoste_green,
      config = DialConfig(
        hourLengthRatio = 0.42f,
        hourColor = Color(0xFF33383A),
        hourWidthDp = 6f,
        hourCap = StrokeCap.Square,
        hourStripeColor = Color(0xFFEDEDED),
        hourStripeWidthDp = 2.2f,
        minuteLengthRatio = 0.70f,
        minuteColor = Color(0xFF33383A),
        minuteWidthDp = 4.5f,
        minuteCap = StrokeCap.Square,
        minuteStripeColor = Color(0xFFEDEDED),
        minuteStripeWidthDp = 1.8f,
        secondLengthRatio = 0.78f,
        secondColor = Color(0xFFC8372D),
        secondWidthDp = 1.6f,
        secondTailDp = 18f,
        centerCapOuterDp = 6f,
        centerCapOuterColor = Color(0xFF33383A),
        centerCapInnerDp = 2.5f,
        centerCapInnerColor = Color(0xFFC8372D),
        dateOffsetXRatio = 0.52f,
        dateOffsetYRatio = 0.0f,
        dateTextSizeRatio = 0.09f,
        dateColor = Color(0xFF3E5A47),
      ),
    ),
    ImageDialFace(
      id = "face_casio_leather",
      displayName = "Field Black",
      dialResId = R.drawable.face_casio_leather,
      config = DialConfig(
        hourLengthRatio = 0.45f,
        hourColor = Color.White,
        hourWidthDp = 7f,
        hourCap = StrokeCap.Square,
        hourStripeColor = Color(0xFF222222),
        hourStripeWidthDp = 2.5f,
        minuteLengthRatio = 0.78f,
        minuteColor = Color.White,
        minuteWidthDp = 5f,
        minuteCap = StrokeCap.Square,
        minuteStripeColor = Color(0xFF222222),
        minuteStripeWidthDp = 2f,
        secondLengthRatio = 0.86f,
        secondColor = Color(0xFFFF3B30),
        secondWidthDp = 1.8f,
        secondTailDp = 24f,
        centerCapOuterDp = 6f,
        centerCapOuterColor = Color.White,
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFFF3B30),
      ),
    ),
    ImageDialFace(
      id = "face_bell_ross",
      displayName = "Pilot White",
      dialResId = R.drawable.face_bell_ross,
      config = DialConfig(
        hourLengthRatio = 0.48f,
        hourColor = Color(0xFF111111),
        hourWidthDp = 7f,
        hourCap = StrokeCap.Square,
        hourStripeColor = Color(0xFFE8E8E0),
        hourStripeWidthDp = 3f,
        minuteLengthRatio = 0.78f,
        minuteColor = Color(0xFF111111),
        minuteWidthDp = 5f,
        minuteCap = StrokeCap.Square,
        minuteStripeColor = Color(0xFFE8E8E0),
        minuteStripeWidthDp = 2.2f,
        secondLengthRatio = 0.86f,
        secondColor = Color(0xFFE10600),
        secondWidthDp = 1.8f,
        secondTailDp = 22f,
        centerCapOuterDp = 6.5f,
        centerCapOuterColor = Color(0xFF111111),
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFE10600),
        dateOffsetXRatio = 0.58f,
        dateOffsetYRatio = 0.0f,
        dateTextSizeRatio = 0.07f,
        dateColor = Color.Black,
      ),
    ),
    ImageDialFace(
      id = "face_seiko_blue",
      displayName = "Ocean Blue",
      dialResId = R.drawable.face_seiko_blue,
      config = DialConfig(
        hourLengthRatio = 0.48f,
        hourColor = Color(0xFFE6EAF0),
        hourWidthDp = 6.5f,
        hourCap = StrokeCap.Butt,
        hourStripeColor = Color(0xFF6B7A90),
        hourStripeWidthDp = 2.5f,
        minuteLengthRatio = 0.76f,
        minuteColor = Color(0xFFE6EAF0),
        minuteWidthDp = 4.8f,
        minuteCap = StrokeCap.Butt,
        minuteStripeColor = Color(0xFF6B7A90),
        minuteStripeWidthDp = 2f,
        secondLengthRatio = 0.84f,
        secondColor = Color(0xFFF2B84B),
        secondWidthDp = 1.6f,
        secondTailDp = 20f,
        centerCapOuterDp = 5.5f,
        centerCapOuterColor = Color(0xFFE6EAF0),
        centerCapInnerDp = 2.5f,
        centerCapInnerColor = Color(0xFFF2B84B),
      ),
    ),
    ImageDialFace(
      id = "face_phantom_black",
      displayName = "Phantom Black",
      dialResId = R.drawable.face_phantom_black,
      config = DialConfig(
        hourLengthRatio = 0.48f,
        hourColor = Color.White,
        hourWidthDp = 6.5f,
        hourCap = StrokeCap.Square,
        hourStripeColor = Color(0xFF303030),
        hourStripeWidthDp = 2.5f,
        minuteLengthRatio = 0.78f,
        minuteColor = Color.White,
        minuteWidthDp = 4.8f,
        minuteCap = StrokeCap.Square,
        minuteStripeColor = Color(0xFF303030),
        minuteStripeWidthDp = 2f,
        secondLengthRatio = 0.86f,
        secondColor = Color(0xFFFF6A00),
        secondWidthDp = 1.6f,
        secondTailDp = 22f,
        centerCapOuterDp = 6f,
        centerCapOuterColor = Color.White,
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFFF6A00),
      ),
    ),
    ImageDialFace(
      id = "face_wood_art",
      displayName = "Workshop Wood",
      dialResId = R.drawable.face_wood_art,
      config = DialConfig(
        hourLengthRatio = 0.40f,
        hourColor = Color(0xFF1A1208),
        hourWidthDp = 7f,
        hourCap = StrokeCap.Round,
        hourStripeColor = Color(0xFFF3E3B5),
        hourStripeWidthDp = 2.5f,
        minuteLengthRatio = 0.72f,
        minuteColor = Color(0xFF1A1208),
        minuteWidthDp = 5f,
        minuteCap = StrokeCap.Round,
        minuteStripeColor = Color(0xFFF3E3B5),
        minuteStripeWidthDp = 2f,
        secondLengthRatio = 0.80f,
        secondColor = Color(0xFFB3261E),
        secondWidthDp = 1.8f,
        secondTailDp = 16f,
        centerCapOuterDp = 6f,
        centerCapOuterColor = Color(0xFF1A1208),
        centerCapInnerDp = 3f,
        centerCapInnerColor = Color(0xFFF3E3B5),
      ),
    ),
  )

  private val systemFaces: List<WatchFace> = listOf(
    StandByDigitalFace(),
    MinimalAnalogFace(),
  )

  private val _wffFaces = MutableStateFlow<List<WffStoredFace>>(emptyList())
  val wffFacesFlow: StateFlow<List<WffStoredFace>> = _wffFaces.asStateFlow()

  private val _photoFaces = MutableStateFlow<List<PhotoStoredFace>>(emptyList())
  val photoFacesFlow: StateFlow<List<PhotoStoredFace>> = _photoFaces.asStateFlow()

  private val _facesFlow = MutableStateFlow(buildFaceList(emptyList(), emptyList()))
  val facesFlow: StateFlow<List<WatchFace>> = _facesFlow.asStateFlow()

  /** Loads JSON definitions, custom fonts, photo dial faces, and imported WFF packages. */
  fun init(context: Context) {
    val appCtx = context.applicationContext
    FaceAssetRepository.ensureInitialized(appCtx)
    com.example.core.CustomFontStorage.ensureInitialized(appCtx)
    reloadWff(appCtx)
  }

  /** Reloads imported WFF packages and Photo Dial faces from private storage, syncing Diagnostics. */
  fun reloadWff(context: Context) {
    val appCtx = context.applicationContext
    FaceAssetRepository.ensureInitialized(appCtx)
    com.example.core.CustomFontStorage.ensureInitialized(appCtx)
    val safeMode = com.example.core.StartupSafetyNet.isSafeModeActive
    val storedWff = if (safeMode) emptyList() else WffStorageManager.loadAll(appCtx)
    val storedPhoto = if (safeMode) emptyList() else PhotoFaceStorage.loadAll(appCtx)
    _wffFaces.value = storedWff
    _photoFaces.value = storedPhoto
    _facesFlow.value = buildFaceList(storedPhoto, storedWff)
    FaceAssetRepository.updateWffDiagnostics(
      storedWff.map { entry ->
        val sensorDiag = CoreSingletons.sensorSource(appCtx).diagnostics.value
        val compMap = CoreSingletons.prefs(appCtx).getWffComplicationProviders(entry.id)
        val provenance = com.example.faces.wff.WffExpressionEvaluator.buildDataSourceProvenance(
          face = entry.face,
          sensorDiag = sensorDiag,
          savedComplicationIds = compMap,
        )
        WffCompatibilitySummary(
          id = entry.id,
          name = entry.displayName,
          scorePercent = entry.report.scorePercent,
          drawnCount = entry.report.drawnCount,
          partlyDrawnCount = entry.report.partlyDrawnCount,
          skippedCount = entry.report.skippedCount,
          partlyDrawnItems = entry.report.partlyDrawnItems.map { WffSkippedDiagnostic(it.element, it.reason) },
          skippedItems = entry.report.skippedItems.map { WffSkippedDiagnostic(it.element, it.reason) },
          dataSources = provenance,
        )
      },
    )
  }

  /** Persists a newly baked Photo Dial face and activates it. */
  fun savePhotoFace(
    context: Context,
    config: PhotoFaceConfig,
    dialBitmap: android.graphics.Bitmap,
  ): PhotoStoredFace {
    val appCtx = context.applicationContext
    val saved = PhotoFaceStorage.savePhotoFace(appCtx, config, dialBitmap)
    reloadWff(appCtx)
    CoreSingletons.prefs(appCtx).setSelectedFaceId(saved.id)
    return saved
  }

  /** Updates an existing saved Photo Dial face's hands/date/name settings and reloads registry. */
  fun updatePhotoFaceConfig(context: Context, config: PhotoFaceConfig): PhotoStoredFace? {
    val appCtx = context.applicationContext
    val updated = PhotoFaceStorage.updateConfigOnly(appCtx, config) ?: return null
    reloadWff(appCtx)
    return updated
  }

  /** Deletes a saved Photo Dial face by [id], falling back cleanly if active. */
  fun deletePhotoFace(context: Context, id: String): Boolean {
    val appCtx = context.applicationContext
    val deleted = PhotoFaceStorage.deleteFace(appCtx, id)
    if (deleted) {
      val prefs = CoreSingletons.prefs(appCtx)
      prefs.setFaceDigitalFontId(id, null)
      reloadWff(appCtx)
      if (prefs.selectedFaceId.value == id) {
        faces.firstOrNull()?.let { prefs.setSelectedFaceId(it.id) }
      }
    }
    return deleted
  }

  /** Sealed result for unified archive import (.apk, WFF .zip, Photo Dial .zip, or watchface.xml). */
  sealed interface UnifiedImportResult {
    data class Wff(val result: WffImportResult) : UnifiedImportResult
    data class PhotoSuccess(val stored: PhotoStoredFace) : UnifiedImportResult
    data class PhotoError(val reason: String) : UnifiedImportResult
  }

  /**
   * Imports either an AuraClock exported Photo Dial `.zip` (`face.json` + dial image) or
   * a Watch Face Format `.apk`/`.zip`/`watchface.xml` via the single Watch face importer.
   */
  fun importAnyArchive(context: Context, uri: android.net.Uri): UnifiedImportResult {
    val appCtx = context.applicationContext
    val tempFile = java.io.File.createTempFile("face_peek_", ".tmp", appCtx.cacheDir)
    try {
      val copied = appCtx.contentResolver.openInputStream(uri)?.use { input ->
        var total = 0L
        val buf = ByteArray(16 * 1024)
        java.io.FileOutputStream(tempFile).use { out ->
          while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > PhotoFaceStorage.MAX_EXPORT_ZIP_BYTES) return UnifiedImportResult.PhotoError("Selected file exceeds the 64 MB size limit.")
            out.write(buf, 0, read)
          }
        }
        true
      } ?: false

      if (copied && tempFile.length() >= 4L) {
        val header = ByteArray(4)
        tempFile.inputStream().use { it.read(header) }
        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
          (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
        if (isZip) {
          when (val photoRes = PhotoFaceStorage.importFromZipFile(appCtx, tempFile)) {
            is PhotoZipImportResult.Success -> {
              reloadWff(appCtx)
              CoreSingletons.prefs(appCtx).setSelectedFaceId(photoRes.stored.id)
              return UnifiedImportResult.PhotoSuccess(photoRes.stored)
            }
            is PhotoZipImportResult.Error -> return UnifiedImportResult.PhotoError(photoRes.reason)
            is PhotoZipImportResult.NotAPhotoFaceZip -> Unit
          }
        }
      }
    } finally {
      runCatching { tempFile.delete() }
    }
    return UnifiedImportResult.Wff(importWff(appCtx, uri))
  }

  /** Imports a WFF file or archive and updates the registry when successful. */
  fun importWff(context: Context, uri: android.net.Uri): WffImportResult {
    val result = WffStorageManager.importFromUri(context.applicationContext, uri)
    if (result is WffImportResult.Success) {
      reloadWff(context)
    }
    return result
  }

  /** Replaces an existing duplicate WFF watch face from a staged file and reloads the registry. */
  fun replaceWff(
    context: Context,
    stagedFile: java.io.File,
    sourceFileName: String,
    targetFaceId: String,
  ): WffImportResult {
    val result = WffStorageManager.replaceImportedFace(context.applicationContext, stagedFile, sourceFileName, targetFaceId)
    if (result is WffImportResult.Success) {
      reloadWff(context)
    }
    return result
  }

  /** Deletes an imported WFF watch face by [id] and falls back cleanly if it was active. */
  fun deleteWff(context: Context, id: String): Boolean {
    val appCtx = context.applicationContext
    val deleted = WffStorageManager.deleteFace(appCtx, id)
    if (deleted) {
      val prefs = CoreSingletons.prefs(appCtx)
      prefs.deleteWffFaceConfigs(id)
      reloadWff(appCtx)
      if (prefs.selectedFaceId.value == id) {
        faces.firstOrNull()?.let { prefs.setSelectedFaceId(it.id) }
      }
    }
    return deleted
  }

  /** Returns all picker watch faces in display order. */
  val faces: List<WatchFace>
    get() = _facesFlow.value

  /** Looks up a face by ID across picker faces and Stand By system faces, defaulting to the first registered face. */
  fun findById(id: String?): WatchFace =
    faces.firstOrNull { it.id == id }
      ?: systemFaces.firstOrNull { it.id == id }
      ?: faces.first()

  private fun buildFaceList(
    photoStored: List<PhotoStoredFace>,
    wffStored: List<WffStoredFace>,
  ): List<WatchFace> {
    val result = ArrayList<WatchFace>(16 + photoStored.size + wffStored.size)
    val usedIds = HashSet<String>()

    fun addDefinitionOrFallback(id: String, fallback: (() -> WatchFace)? = null) {
      val def = FaceAssetRepository.getById(id)
      if (def != null) {
        result += DefinitionFace(def)
        usedIds += def.id
      } else if (fallback != null) {
        val fb = fallback()
        result += fb
        usedIds += fb.id
      }
    }

    // Ported flagship faces first (same IDs as before so user selection persists).
    addDefinitionOrFallback("motorsport_m") { LegacyDialFaces.motorsportM() }
    addDefinitionOrFallback("speedmaster_racing") { LegacyDialFaces.speedmasterRacing() }

    // Existing 9 image dial faces untouched.
    for (dial in imageDialFaces) {
      if (usedIds.add(dial.id)) {
        result += dial
      }
    }

    // New data-driven faces.
    addDefinitionOrFallback("big_digital")
    addDefinitionOrFallback("minimal_plus")

    // Any extra valid JSON face dropped into assets/faces/ is registered automatically.
    for (def in FaceAssetRepository.allDefinitions()) {
      if (usedIds.add(def.id)) {
        result += DefinitionFace(def)
      }
    }

    // User-created Photo Dial faces.
    for (photo in photoStored) {
      if (usedIds.add(photo.id)) {
        result += ImageDialFace.fromPhotoStored(photo)
      }
    }

    // Append imported Watch Face Format packages.
    for (stored in wffStored) {
      if (usedIds.add(stored.id)) {
        result += WffFace(stored)
      }
    }

    return result
  }
}

package com.example.faces

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.example.R
import com.example.faces.engine.DefinitionFace
import com.example.faces.engine.FaceAssetRepository

/**
 * Single registry list of all available watch faces, combining data-driven [DefinitionFace]
 * assets from assets/faces/ alongside the existing [ImageDialFace] collection.
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

  @Volatile
  private var cachedFaces: List<WatchFace> = buildFaceList()

  /** Loads JSON definitions from assets/faces/ and refreshes the registered face list. */
  fun init(context: Context) {
    FaceAssetRepository.ensureInitialized(context)
    cachedFaces = buildFaceList()
  }

  /** Returns all picker watch faces in display order. */
  val faces: List<WatchFace>
    get() = cachedFaces

  /** Looks up a face by ID across picker faces and Stand By system faces, defaulting to the first registered face. */
  fun findById(id: String?): WatchFace =
    faces.firstOrNull { it.id == id }
      ?: systemFaces.firstOrNull { it.id == id }
      ?: faces.first()

  private fun buildFaceList(): List<WatchFace> {
    val result = ArrayList<WatchFace>(16)
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

    return result
  }
}

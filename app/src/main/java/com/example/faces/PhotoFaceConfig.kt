package com.example.faces

import org.json.JSONObject
import java.util.Locale

/** Selectable hand silhouettes for user-created Photo Dial faces. */
internal enum class PhotoHandStyle(val id: String, val label: String) {
  SWORD("sword", "Sword"),
  BATON("baton", "Baton"),
  DAUPHINE("dauphine", "Dauphine"),
  PENCIL("pencil", "Pencil"),
  ARROW("arrow", "Arrow"),
  PLAIN_BAR("plain_bar", "Plain bar");

  companion object {
    fun fromId(raw: String?): PhotoHandStyle? {
      val clean = raw?.trim()?.lowercase(Locale.US) ?: return null
      return entries.firstOrNull { it.id == clean }
    }
  }
}

/** Selectable centre arbor / cap styles for Photo Dial faces. */
internal enum class PhotoCenterCapStyle(val id: String, val label: String) {
  DOUBLE_RING("double_ring", "Ringed hub"),
  SOLID_DISC("solid_disc", "Solid disc"),
  OPEN_HUB("open_hub", "Open hub"),
  MINIMAL_DOT("minimal_dot", "Minimal dot");

  companion object {
    fun fromId(raw: String?): PhotoCenterCapStyle? {
      val clean = raw?.trim()?.lowercase(Locale.US) ?: return null
      return entries.firstOrNull { it.id == clean }
    }
  }
}

/**
 * Immutable persisted configuration stored inside each photo dial folder (`face.json`).
 * Encodes dial asset filename, crop/tone parameters, hand geometry/colours, and date window settings.
 */
internal data class PhotoFaceConfig(
  val version: Int = CURRENT_VERSION,
  val id: String,
  val name: String,
  val dialImageFile: String = DEFAULT_DIAL_FILE,
  val zoom: Float = 1f,
  val panXRatio: Float = 0f,
  val panYRatio: Float = 0f,
  val rotationDeg: Float = 0f,
  val brightness: Float = 0f,
  val contrast: Float = 1f,
  val saturation: Float = 1f,
  val handStyle: PhotoHandStyle = PhotoHandStyle.SWORD,
  val hourColorArgb: Int = 0xFFFFFFFF.toInt(),
  val minuteColorArgb: Int = 0xFFF1F5F9.toInt(),
  val secondColorArgb: Int = 0xFFE00424.toInt(),
  val lumeOutlineEnabled: Boolean = true,
  val lumeColorArgb: Int = 0xFFCCFF00.toInt(),
  val hourLengthRatio: Float = 0.48f,
  val minuteLengthRatio: Float = 0.74f,
  val handWidthScale: Float = 1.0f,
  val showSecondHand: Boolean = true,
  val smoothSeconds: Boolean = true,
  val centerCapStyle: PhotoCenterCapStyle = PhotoCenterCapStyle.DOUBLE_RING,
  val shadowStrength: Float = 0.45f,
  val dateEnabled: Boolean = false,
  val dateXRatio: Float = 0.48f,
  val dateYRatio: Float = 0.0f,
  val dateSizeRatio: Float = 0.085f,
  val dateColorArgb: Int = 0xFFFFFFFF.toInt(),
  val dateFontId: String? = null,
  val updatedAtMs: Long = 0L,
) {

  /** Serializes this configuration to formatted `face.json` text. */
  fun toJsonString(): String = JSONObject().apply {
    put("format", FORMAT_MARKER)
    put("version", version)
    put("id", id)
    put("name", name)
    put("dialImageFile", dialImageFile)
    put("zoom", zoom.toDouble())
    put("panXRatio", panXRatio.toDouble())
    put("panYRatio", panYRatio.toDouble())
    put("rotationDeg", rotationDeg.toDouble())
    put("brightness", brightness.toDouble())
    put("contrast", contrast.toDouble())
    put("saturation", saturation.toDouble())
    put("handStyle", handStyle.id)
    put("hourColor", formatHexArgb(hourColorArgb))
    put("minuteColor", formatHexArgb(minuteColorArgb))
    put("secondColor", formatHexArgb(secondColorArgb))
    put("lumeOutlineEnabled", lumeOutlineEnabled)
    put("lumeColor", formatHexArgb(lumeColorArgb))
    put("hourLengthRatio", hourLengthRatio.toDouble())
    put("minuteLengthRatio", minuteLengthRatio.toDouble())
    put("handWidthScale", handWidthScale.toDouble())
    put("showSecondHand", showSecondHand)
    put("smoothSeconds", smoothSeconds)
    put("centerCapStyle", centerCapStyle.id)
    put("shadowStrength", shadowStrength.toDouble())
    put("dateEnabled", dateEnabled)
    put("dateXRatio", dateXRatio.toDouble())
    put("dateYRatio", dateYRatio.toDouble())
    put("dateSizeRatio", dateSizeRatio.toDouble())
    put("dateColor", formatHexArgb(dateColorArgb))
    if (!dateFontId.isNullOrBlank()) {
      put("dateFontId", dateFontId)
    }
    put("updatedAtMs", updatedAtMs)
  }.toString(2)

  sealed interface ParseResult {
    data class Success(val config: PhotoFaceConfig) : ParseResult
    data class Error(val reason: String) : ParseResult
  }

  companion object {
    const val CURRENT_VERSION = 1
    const val FORMAT_MARKER = "auraclock_photo_face"
    const val DEFAULT_DIAL_FILE = "dial.png"

    private val allowedImageExtensions = setOf("png", "webp", "jpg", "jpeg")

    /** Parses and strictly validates `face.json` content. */
    fun fromJson(jsonText: String): ParseResult {
      if (jsonText.isBlank()) return ParseResult.Error("face.json is empty.")
      val root = try {
        JSONObject(jsonText)
      } catch (e: Exception) {
        return ParseResult.Error("Invalid face.json: ${e.message ?: "syntax error"}")
      }

      val version = root.optInt("version", CURRENT_VERSION)
      if (version < 1 || version > CURRENT_VERSION + 5) {
        return ParseResult.Error("Unsupported photo face version: $version")
      }

      val id = root.optString("id", "").trim()
      if (!isSafeIdentifier(id)) {
        return ParseResult.Error("Invalid or missing photo face id.")
      }

      val name = root.optString("name", "").trim()
      if (name.isEmpty()) {
        return ParseResult.Error("Missing photo face name.")
      }

      val dialFile = root.optString("dialImageFile", DEFAULT_DIAL_FILE).trim()
      if (!isSafeRelativeImageName(dialFile)) {
        return ParseResult.Error("Unsafe or unsupported dial image filename '$dialFile'.")
      }

      val handStyleRaw = root.optString("handStyle", PhotoHandStyle.SWORD.id)
      val handStyle = PhotoHandStyle.fromId(handStyleRaw)
        ?: return ParseResult.Error("Unsupported hand style '$handStyleRaw'.")

      val capStyleRaw = root.optString("centerCapStyle", PhotoCenterCapStyle.DOUBLE_RING.id)
      val capStyle = PhotoCenterCapStyle.fromId(capStyleRaw)
        ?: return ParseResult.Error("Unsupported centre cap style '$capStyleRaw'.")

      val zoom = finiteDouble(root, "zoom", 1.0)?.toFloat()?.coerceIn(1f, 6f)
        ?: return ParseResult.Error("Invalid zoom value.")
      val panXRatio = finiteDouble(root, "panXRatio", 0.0)?.toFloat()?.coerceIn(-4f, 4f)
        ?: return ParseResult.Error("Invalid panXRatio value.")
      val panYRatio = finiteDouble(root, "panYRatio", 0.0)?.toFloat()?.coerceIn(-4f, 4f)
        ?: return ParseResult.Error("Invalid panYRatio value.")
      val rotationDeg = finiteDouble(root, "rotationDeg", 0.0)?.toFloat()?.coerceIn(-180f, 180f)
        ?: return ParseResult.Error("Invalid rotationDeg value.")
      val brightness = finiteDouble(root, "brightness", 0.0)?.toFloat()?.coerceIn(-0.5f, 0.5f)
        ?: return ParseResult.Error("Invalid brightness value.")
      val contrast = finiteDouble(root, "contrast", 1.0)?.toFloat()?.coerceIn(0.5f, 1.8f)
        ?: return ParseResult.Error("Invalid contrast value.")
      val saturation = finiteDouble(root, "saturation", 1.0)?.toFloat()?.coerceIn(0f, 2f)
        ?: return ParseResult.Error("Invalid saturation value.")

      val hourColor = parseHexArgb(root.optString("hourColor", "#FFFFFFFF"))
        ?: return ParseResult.Error("Invalid hourColor.")
      val minuteColor = parseHexArgb(root.optString("minuteColor", "#FFF1F5F9"))
        ?: return ParseResult.Error("Invalid minuteColor.")
      val secondColor = parseHexArgb(root.optString("secondColor", "#FFE00424"))
        ?: return ParseResult.Error("Invalid secondColor.")
      val lumeColor = parseHexArgb(root.optString("lumeColor", "#FFCCFF00"))
        ?: return ParseResult.Error("Invalid lumeColor.")
      val dateColor = parseHexArgb(root.optString("dateColor", "#FFFFFFFF"))
        ?: return ParseResult.Error("Invalid dateColor.")

      val hourLengthRatio = finiteDouble(root, "hourLengthRatio", 0.48)?.toFloat()?.coerceIn(0.30f, 0.65f)
        ?: return ParseResult.Error("Invalid hourLengthRatio.")
      val minuteLengthRatio = finiteDouble(root, "minuteLengthRatio", 0.74)?.toFloat()?.coerceIn(0.50f, 0.90f)
        ?: return ParseResult.Error("Invalid minuteLengthRatio.")
      val handWidthScale = finiteDouble(root, "handWidthScale", 1.0)?.toFloat()?.coerceIn(0.6f, 1.8f)
        ?: return ParseResult.Error("Invalid handWidthScale.")
      val shadowStrength = finiteDouble(root, "shadowStrength", 0.45)?.toFloat()?.coerceIn(0f, 1f)
        ?: return ParseResult.Error("Invalid shadowStrength.")

      val dateXRatio = finiteDouble(root, "dateXRatio", 0.48)?.toFloat()?.coerceIn(-0.78f, 0.78f)
        ?: return ParseResult.Error("Invalid dateXRatio.")
      val dateYRatio = finiteDouble(root, "dateYRatio", 0.0)?.toFloat()?.coerceIn(-0.78f, 0.78f)
        ?: return ParseResult.Error("Invalid dateYRatio.")
      val dateSizeRatio = finiteDouble(root, "dateSizeRatio", 0.085)?.toFloat()?.coerceIn(0.05f, 0.14f)
        ?: return ParseResult.Error("Invalid dateSizeRatio.")

      val rawDateFontId = if (root.has("dateFontId") && !root.isNull("dateFontId")) {
        root.optString("dateFontId", "").trim().takeIf { it.isNotEmpty() && isSafeIdentifier(it) }
      } else {
        null
      }

      return ParseResult.Success(
        PhotoFaceConfig(
          version = version,
          id = id,
          name = name.take(48),
          dialImageFile = dialFile,
          zoom = zoom,
          panXRatio = panXRatio,
          panYRatio = panYRatio,
          rotationDeg = rotationDeg,
          brightness = brightness,
          contrast = contrast,
          saturation = saturation,
          handStyle = handStyle,
          hourColorArgb = hourColor,
          minuteColorArgb = minuteColor,
          secondColorArgb = secondColor,
          lumeOutlineEnabled = root.optBoolean("lumeOutlineEnabled", true),
          lumeColorArgb = lumeColor,
          hourLengthRatio = hourLengthRatio,
          minuteLengthRatio = minuteLengthRatio,
          handWidthScale = handWidthScale,
          showSecondHand = root.optBoolean("showSecondHand", true),
          smoothSeconds = root.optBoolean("smoothSeconds", true),
          centerCapStyle = capStyle,
          shadowStrength = shadowStrength,
          dateEnabled = root.optBoolean("dateEnabled", false),
          dateXRatio = dateXRatio,
          dateYRatio = dateYRatio,
          dateSizeRatio = dateSizeRatio,
          dateColorArgb = dateColor,
          dateFontId = rawDateFontId,
          updatedAtMs = root.optLong("updatedAtMs", 0L).coerceAtLeast(0L),
        ),
      )
    }

    internal fun isSafeIdentifier(id: String): Boolean {
      if (id.isBlank() || id.length > 80) return false
      if (id.contains('/') || id.contains('\\') || id.contains("..")) return false
      return id.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }

    internal fun isSafeRelativeImageName(name: String): Boolean {
      if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return false
      val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
      return ext in allowedImageExtensions
    }

    private fun finiteDouble(obj: JSONObject, key: String, fallback: Double): Double? {
      if (!obj.has(key) || obj.isNull(key)) return fallback
      val v = obj.optDouble(key, Double.NaN)
      return if (v.isFinite()) v else null
    }

    internal fun parseHexArgb(raw: String): Int? {
      val s = raw.trim()
      if (!s.startsWith("#")) return null
      val hex = s.substring(1)
      return try {
        when (hex.length) {
          6 -> (0xFF000000L or hex.toLong(16)).toInt()
          8 -> hex.toLong(16).toInt()
          else -> null
        }
      } catch (_: NumberFormatException) {
        null
      }
    }

    internal fun formatHexArgb(argb: Int): String =
      "#%08X".format(Locale.US, argb)
  }
}

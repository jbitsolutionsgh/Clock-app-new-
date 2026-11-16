package com.example.faces.wff

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Compact little-endian parser for Android compiled resources.arsc tables.
 * Resolves drawable/mipmap resource names to archive file paths (e.g. after R8 resource shrinking)
 * without needing AAPT or AssetManager reflection.
 */
internal object ArscResourceTableReader {

  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_TABLE_TYPE = 0x0002
  private const val RES_TABLE_PACKAGE_TYPE = 0x0200
  private const val RES_TABLE_TYPE_TYPE = 0x0201

  private const val FLAG_UTF8 = 1 shl 8
  private const val TYPE_FLAG_SPARSE = 0x01
  private const val TYPE_FLAG_OFFSET16 = 0x02
  private const val ENTRY_FLAG_COMPLEX = 0x0001
  private const val ENTRY_FLAG_COMPACT = 0x0008
  private const val VALUE_TYPE_STRING = 0x03

  /**
   * Parses [arscBytes] and returns a map of resource entry key name (e.g. "hand_hour")
   * to the best matching archive relative path (e.g. "res/ab.webp").
   */
  fun readDrawableResourcePaths(arscBytes: ByteArray): Map<String, String> =
    readTypedStrings(arscBytes, setOf("drawable", "mipmap", "font"), Locale.getDefault(), scoreAsPath = true)

  /**
   * Parses [arscBytes] for "string" entries matching [locale] (falling back to default config).
   */
  fun readStrings(arscBytes: ByteArray, locale: Locale = Locale.getDefault()): Map<String, String> =
    readTypedStrings(arscBytes, setOf("string"), locale, scoreAsPath = false)

  private fun readTypedStrings(
    arscBytes: ByteArray,
    targetTypes: Set<String>,
    locale: Locale,
    scoreAsPath: Boolean,
  ): Map<String, String> {
    if (arscBytes.size < 12) return emptyMap()
    return runCatching {
      val buf = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN)
      val rootType = buf.getShort(0).toInt() and 0xFFFF
      if (rootType != RES_TABLE_TYPE) return emptyMap()
      val rootHeaderSize = buf.getShort(2).toInt() and 0xFFFF
      val rootSize = buf.getInt(4).coerceAtMost(arscBytes.size)
      if (rootHeaderSize < 12 || rootSize <= rootHeaderSize) return emptyMap()

      var globalStrings = emptyList<String>()
      val result = LinkedHashMap<String, String>()
      val scores = HashMap<String, Int>()

      var pos = rootHeaderSize
      while (pos + 8 <= rootSize) {
        val chunkType = buf.getShort(pos).toInt() and 0xFFFF
        val chunkHeaderSize = buf.getShort(pos + 2).toInt() and 0xFFFF
        val chunkSize = buf.getInt(pos + 4)
        if (chunkSize < 8 || pos + chunkSize > rootSize) break

        when (chunkType) {
          RES_STRING_POOL_TYPE -> {
            globalStrings = readStringPool(buf, pos, pos + chunkSize)
          }
          RES_TABLE_PACKAGE_TYPE -> {
            readPackageChunk(
              buf = buf,
              bytes = arscBytes,
              pkgStart = pos,
              pkgHeaderSize = chunkHeaderSize,
              pkgEnd = pos + chunkSize,
              targetTypes = targetTypes,
              locale = locale,
              scoreAsPath = scoreAsPath,
              globalStrings = globalStrings,
              out = result,
              scores = scores,
            )
          }
        }
        pos += chunkSize
      }
      result
    }.getOrDefault(emptyMap())
  }

  private fun readPackageChunk(
    buf: ByteBuffer,
    bytes: ByteArray,
    pkgStart: Int,
    pkgHeaderSize: Int,
    pkgEnd: Int,
    targetTypes: Set<String>,
    locale: Locale,
    scoreAsPath: Boolean,
    globalStrings: List<String>,
    out: MutableMap<String, String>,
    scores: MutableMap<String, Int>,
  ) {
    if (pkgStart + 284 > pkgEnd || pkgHeaderSize < 284) return
    val typeStringsOffset = buf.getInt(pkgStart + 268)
    val keyStringsOffset = buf.getInt(pkgStart + 276)
    val typeNames = if (typeStringsOffset > 0 && pkgStart + typeStringsOffset + 8 <= pkgEnd) {
      readStringPool(buf, pkgStart + typeStringsOffset, pkgEnd)
    } else emptyList()
    val keyNames = if (keyStringsOffset > 0 && pkgStart + keyStringsOffset + 8 <= pkgEnd) {
      readStringPool(buf, pkgStart + keyStringsOffset, pkgEnd)
    } else emptyList()

    var pos = pkgStart + pkgHeaderSize
    while (pos + 8 <= pkgEnd) {
      val chunkType = buf.getShort(pos).toInt() and 0xFFFF
      val chunkHeaderSize = buf.getShort(pos + 2).toInt() and 0xFFFF
      val chunkSize = buf.getInt(pos + 4)
      if (chunkSize < 8 || pos + chunkSize > pkgEnd) break

      if (chunkType == RES_TABLE_TYPE_TYPE && chunkHeaderSize >= 20) {
        val typeId = bytes[pos + 8].toInt() and 0xFF
        val typeName = typeNames.getOrNull(typeId - 1)?.lowercase(Locale.US)
        if (typeName in targetTypes) {
          val configScore = if (scoreAsPath) 1 else readConfigLocaleScore(buf, bytes, pos, chunkHeaderSize, locale)
          if (configScore >= 0) {
            readTypeChunk(
              buf = buf,
              bytes = bytes,
              typeStart = pos,
              typeHeaderSize = chunkHeaderSize,
              typeEnd = pos + chunkSize,
              configScore = configScore,
              scoreAsPath = scoreAsPath,
              keyNames = keyNames,
              globalStrings = globalStrings,
              out = out,
              scores = scores,
            )
          }
        }
      }
      pos += chunkSize
    }
  }

  private fun readConfigLocaleScore(
    buf: ByteBuffer,
    bytes: ByteArray,
    typeStart: Int,
    typeHeaderSize: Int,
    locale: Locale,
  ): Int {
    if (typeHeaderSize < 24) return 10
    val configSize = buf.getInt(typeStart + 20)
    if (configSize < 8 || typeHeaderSize < 28) return 10
    val lang = decodeLocaleField(bytes[typeStart + 24], bytes[typeStart + 25], isCountry = false)
    val country = decodeLocaleField(bytes[typeStart + 26], bytes[typeStart + 27], isCountry = true)
    val targetLang = locale.language.lowercase(Locale.US)
    val targetCountry = locale.country.uppercase(Locale.US)
    return when {
      lang.isEmpty() && country.isEmpty() -> 10
      lang.equals(targetLang, ignoreCase = true) && country.equals(targetCountry, ignoreCase = true) && targetCountry.isNotEmpty() -> 30
      lang.equals(targetLang, ignoreCase = true) && country.isEmpty() -> 20
      lang.equals(targetLang, ignoreCase = true) -> 5
      else -> -1
    }
  }

  private fun decodeLocaleField(b0: Byte, b1: Byte, isCountry: Boolean): String {
    val u0 = b0.toInt() and 0xFF
    val u1 = b1.toInt() and 0xFF
    if (u0 == 0 && u1 == 0) return ""
    if ((u0 and 0x80) == 0) {
      val c0 = u0.toChar()
      val c1 = u1.toChar()
      return if (c0.isLetterOrDigit() && c1.isLetterOrDigit()) "$c0$c1" else ""
    }
    // Packed 3-letter ISO code inside 2 bytes.
    val base = if (isCountry) '0'.code else 'a'.code
    val first = ((u0 shr 2) and 0x1F) + base
    val second = (((u0 and 0x03) shl 3) or ((u1 shr 5) and 0x07)) + base
    val third = (u1 and 0x1F) + base
    return String(charArrayOf(first.toChar(), second.toChar(), third.toChar()))
  }

  private fun readTypeChunk(
    buf: ByteBuffer,
    bytes: ByteArray,
    typeStart: Int,
    typeHeaderSize: Int,
    typeEnd: Int,
    configScore: Int,
    scoreAsPath: Boolean,
    keyNames: List<String>,
    globalStrings: List<String>,
    out: MutableMap<String, String>,
    scores: MutableMap<String, Int>,
  ) {
    val flags = bytes[typeStart + 9].toInt() and 0xFF
    val entryCount = buf.getInt(typeStart + 12).coerceAtLeast(0)
    val entriesStart = buf.getInt(typeStart + 16)
    val offsetsBase = typeStart + typeHeaderSize
    val entriesBase = typeStart + entriesStart
    if (entriesBase > typeEnd || offsetsBase > typeEnd) return

    val isSparse = (flags and TYPE_FLAG_SPARSE) != 0
    val isOffset16 = (flags and TYPE_FLAG_OFFSET16) != 0

    for (i in 0 until entryCount) {
      val entryByteOffset = when {
        isSparse -> {
          val slot = offsetsBase + i * 4
          if (slot + 4 > typeEnd) break
          val rawOff = buf.getShort(slot + 2).toInt() and 0xFFFF
          if (rawOff == 0xFFFF) continue
          entriesBase + rawOff * 4
        }
        isOffset16 -> {
          val slot = offsetsBase + i * 2
          if (slot + 2 > typeEnd) break
          val rawOff = buf.getShort(slot).toInt() and 0xFFFF
          if (rawOff == 0xFFFF) continue
          entriesBase + rawOff * 4
        }
        else -> {
          val slot = offsetsBase + i * 4
          if (slot + 4 > typeEnd) break
          val rawOff = buf.getInt(slot)
          if (rawOff == -1) continue
          entriesBase + rawOff
        }
      }

      if (entryByteOffset + 8 > typeEnd) continue
      val firstShort = buf.getShort(entryByteOffset).toInt() and 0xFFFF
      val entryFlags = buf.getShort(entryByteOffset + 2).toInt() and 0xFFFF

      val keyIdx: Int
      val dataType: Int
      val dataVal: Int

      if ((entryFlags and ENTRY_FLAG_COMPACT) != 0) {
        keyIdx = firstShort
        dataType = (entryFlags shr 8) and 0xFF
        dataVal = buf.getInt(entryByteOffset + 4)
      } else {
        if ((entryFlags and ENTRY_FLAG_COMPLEX) != 0) continue
        if (entryByteOffset + 16 > typeEnd) continue
        keyIdx = buf.getInt(entryByteOffset + 4)
        dataType = bytes[entryByteOffset + 11].toInt() and 0xFF
        dataVal = buf.getInt(entryByteOffset + 12)
      }

      if (dataType == VALUE_TYPE_STRING) {
        val key = keyNames.getOrNull(keyIdx) ?: continue
        val text = globalStrings.getOrNull(dataVal) ?: continue
        if (scoreAsPath) {
          if (text.isNotBlank()) {
            val sc = scorePath(text)
            if (sc > (scores[key] ?: -1)) {
              scores[key] = sc
              out[key] = text
            }
          }
        } else {
          if (configScore > (scores[key] ?: -1)) {
            scores[key] = configScore
            out[key] = text
          }
        }
      }
    }
  }

  /** Scores candidate resource paths so raster images take priority over XML placeholders. */
  private fun scorePath(path: String): Int {
    val lower = path.lowercase(Locale.US)
    val base = when {
      lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> 100
      lower.endsWith(".xml") -> 10
      else -> 50
    }
    val densityBonus = when {
      "xxxhdpi" in lower -> 5
      "xxhdpi" in lower || "nodpi" in lower -> 4
      "xhdpi" in lower -> 3
      "hdpi" in lower -> 2
      else -> 1
    }
    return base + densityBonus
  }

  private fun readStringPool(buf: ByteBuffer, poolStart: Int, limit: Int): List<String> {
    if (poolStart + 28 > limit) return emptyList()
    val poolSize = buf.getInt(poolStart + 4).coerceAtMost(limit - poolStart)
    val stringCount = buf.getInt(poolStart + 8).coerceAtLeast(0)
    val flags = buf.getInt(poolStart + 16)
    val stringsStart = buf.getInt(poolStart + 20)
    val isUtf8 = (flags and FLAG_UTF8) != 0
    val poolEnd = poolStart + poolSize
    val dataBase = poolStart + stringsStart
    if (dataBase > poolEnd) return emptyList()

    val offsetsBase = poolStart + 28
    val out = ArrayList<String>(stringCount.coerceAtMost(4096))
    for (i in 0 until stringCount) {
      val offPos = offsetsBase + i * 4
      if (offPos + 4 > poolEnd) break
      val strOffset = dataBase + buf.getInt(offPos)
      if (strOffset < dataBase || strOffset >= poolEnd) {
        out += ""
        continue
      }
      out += if (isUtf8) readUtf8String(buf, strOffset, poolEnd) else readUtf16String(buf, strOffset, poolEnd)
    }
    return out
  }

  private fun readUtf8String(buf: ByteBuffer, start: Int, limit: Int): String {
    var pos = start
    // First varint: UTF-16 character count (1 or 2 bytes).
    if (pos >= limit) return ""
    val c1 = buf.get(pos++).toInt() and 0xFF
    if ((c1 and 0x80) != 0 && pos < limit) pos++
    // Second varint: UTF-8 encoded byte length (1 or 2 bytes).
    if (pos >= limit) return ""
    val b1 = buf.get(pos++).toInt() and 0xFF
    val byteLen = if ((b1 and 0x80) != 0 && pos < limit) {
      val b2 = buf.get(pos++).toInt() and 0xFF
      ((b1 and 0x7F) shl 8) or b2
    } else {
      b1
    }
    if (byteLen <= 0 || pos + byteLen > limit) return ""
    val arr = ByteArray(byteLen)
    for (k in 0 until byteLen) {
      arr[k] = buf.get(pos + k)
    }
    return String(arr, StandardCharsets.UTF_8)
  }

  private fun readUtf16String(buf: ByteBuffer, start: Int, limit: Int): String {
    var pos = start
    if (pos + 2 > limit) return ""
    val c1 = buf.getShort(pos).toInt() and 0xFFFF
    pos += 2
    val charLen = if ((c1 and 0x8000) != 0 && pos + 2 <= limit) {
      val c2 = buf.getShort(pos).toInt() and 0xFFFF
      pos += 2
      ((c1 and 0x7FFF) shl 16) or c2
    } else {
      c1
    }
    if (charLen <= 0 || pos + charLen * 2 > limit) return ""
    val chars = CharArray(charLen)
    for (k in 0 until charLen) {
      chars[k] = buf.getShort(pos + k * 2).toInt().toChar()
    }
    return String(chars)
  }
}

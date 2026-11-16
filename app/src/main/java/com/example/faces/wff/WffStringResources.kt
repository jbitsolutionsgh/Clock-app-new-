package com.example.faces.wff

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Loads localized string tables from res/values[-locale]/strings.xml and compiled resources.arsc,
 * selecting the device's active language/country with automatic fallback to default strings.xml.
 */
internal object WffStringResources {

  /** Extracts the best-matching string map for [locale] from [zip] entries and optional [arscBytes]. */
  fun loadFromArchive(
    zip: ZipFile,
    allEntries: List<ZipEntry>,
    arscBytes: ByteArray?,
    locale: Locale = Locale.getDefault(),
  ): Map<String, String> {
    val merged = LinkedHashMap<String, String>()
    if (arscBytes != null) {
      merged.putAll(ArscResourceTableReader.readStrings(arscBytes, locale))
    }

    val xmlCandidates = allEntries.mapNotNull { entry ->
      val lower = entry.name.lowercase(Locale.US)
      val fileName = lower.substringAfterLast('/')
      if (!fileName.endsWith("strings.xml") && fileName != "strings.xml") return@mapNotNull null
      val score = scoreValuesPath(entry.name, locale)
      if (score < 0) return@mapNotNull null
      entry to score
    }.sortedBy { it.second } // Lowest priority first so exact locale overrides default cleanly.

    for ((entry, _) in xmlCandidates) {
      if (entry.size > 1024 * 1024) continue
      val xmlText = runCatching {
        zip.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
      }.getOrNull() ?: continue
      merged.putAll(parseStringsXml(xmlText))
    }
    return merged
  }

  /** Parses plain Android `<resources><string name="key">value</string></resources>` XML text. */
  fun parseStringsXml(xmlText: String): Map<String, String> {
    val clean = xmlText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (!clean.startsWith("<")) return emptyMap()
    return runCatching {
      val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
      val parser = factory.newPullParser()
      parser.setInput(StringReader(clean))

      val out = LinkedHashMap<String, String>()
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "string") {
          val key = parser.getAttributeValue(null, "name")?.trim().orEmpty()
          val rawVal = readMixedText(parser)
          if (key.isNotEmpty()) {
            out[key] = unescapeAndroidString(rawVal)
          }
        }
        event = parser.next()
      }
      out
    }.getOrDefault(emptyMap())
  }

  /** Scores an archive path like res/values-es-rMX/strings.xml against [locale]. Returns -1 to skip. */
  fun scoreValuesPath(path: String, locale: Locale): Int {
    val segments = path.lowercase(Locale.US).split('/')
    val valuesSeg = segments.firstOrNull { it == "values" || it.startsWith("values-") }
      ?: return if (segments.lastOrNull() == "strings.xml") 5 else -1
    if (valuesSeg == "values") return 10

    val qualifier = valuesSeg.removePrefix("values-")
    val targetLang = locale.language.lowercase(Locale.US)
    val targetCountry = locale.country.lowercase(Locale.US)

    if (qualifier.startsWith("b+")) {
      val tags = qualifier.removePrefix("b+").split('+')
      val qLang = tags.getOrNull(0).orEmpty()
      val qRegion = tags.firstOrNull { it.length == 2 && it != qLang }
      return when {
        qLang != targetLang -> -1
        qRegion != null && qRegion == targetCountry -> 30
        qRegion == null -> 20
        else -> 5
      }
    }

    val parts = qualifier.split('-')
    val qLang = parts.firstOrNull { it.length in 2..3 && !it.startsWith("r") && !it.startsWith("v") }
      ?: return 8 // Non-language qualifier (e.g. values-night) stays compatible below explicit locale.
    if (qLang != targetLang) return -1
    val qRegion = parts.firstOrNull { it.startsWith("r") && it.length == 3 }?.substring(1)
    return when {
      qRegion != null && qRegion == targetCountry && targetCountry.isNotEmpty() -> 30
      qRegion == null -> 20
      else -> 5
    }
  }

  /** Resolves a resource name (e.g. "greeting" or "@string/greeting") from [strings], or null if missing. */
  fun resolveOrNull(raw: String?, strings: Map<String, String>): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || strings.isEmpty()) return null
    val key = when {
      trimmed.startsWith("@string/") -> trimmed.removePrefix("@string/")
      trimmed.startsWith("@android:string/") -> trimmed.removePrefix("@android:string/")
      else -> trimmed
    }
    return strings[key]
  }

  /** Resolves [raw] via [strings] when present, otherwise returns [raw] untouched. */
  fun resolve(raw: String, strings: Map<String, String>): String =
    resolveOrNull(raw, strings) ?: raw

  private fun readMixedText(parser: XmlPullParser): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
      when (parser.next()) {
        XmlPullParser.START_TAG -> depth++
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.TEXT -> sb.append(parser.text)
        XmlPullParser.END_DOCUMENT -> break
      }
    }
    return sb.toString().trim()
  }

  private fun unescapeAndroidString(raw: String): String {
    val trimmed = raw.trim().let {
      if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1) else it
    }
    val sb = StringBuilder(trimmed.length)
    var i = 0
    while (i < trimmed.length) {
      val ch = trimmed[i]
      if (ch == '\\' && i + 1 < trimmed.length) {
        when (val next = trimmed[i + 1]) {
          'n' -> sb.append('\n')
          't' -> sb.append('\t')
          '\'', '"', '\\' -> sb.append(next)
          else -> sb.append(next)
        }
        i += 2
      } else {
        sb.append(ch)
        i++
      }
    }
    return sb.toString()
  }
}

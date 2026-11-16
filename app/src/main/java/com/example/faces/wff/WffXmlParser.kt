package com.example.faces.wff

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale

/** Result of parsing a Watch Face Format watchface.xml document. */
internal sealed class WffParseResult {
  /** Successfully parsed WFF scene graph ready for resource resolution and rendering. */
  data class Success(val face: WffWatchFace) : WffParseResult()

  /** Fatal XML format or structural error preventing rendering. */
  data class Error(val reason: String) : WffParseResult()
}

/**
 * Single-pass XmlPullParser reader for Google Watch Face Format (WFF) watchface.xml.
 * Parses WatchFace, UserConfigurations, BitmapFonts, Scene (including Condition/Transform/Variant),
 * Group, AnalogClock, DigitalClock, PartImage, PartText, and PartDraw while scoring compatibility.
 */
internal object WffXmlParser {

  /** Checks whether raw bytes look like compiled Android binary XML (AXML) rather than plain text XML. */
  fun isBinaryXml(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 0x03.toByte() &&
      bytes[1] == 0x00.toByte() &&
      bytes[2] == 0x08.toByte() &&
      bytes[3] == 0x00.toByte()

  /** Parses plain XML text [xmlText] into a [WffWatchFace] assigned [faceId] and fallback [fallbackName]. */
  fun parse(
    xmlText: String,
    faceId: String,
    fallbackName: String,
    stringResources: Map<String, String> = emptyMap(),
  ): WffParseResult {
    val cleanXml = xmlText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (cleanXml.isEmpty() || !cleanXml.startsWith("<")) {
      return WffParseResult.Error("watchface.xml is empty or not plain-text XML.")
    }

    return runCatching {
      val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
      val parser = factory.newPullParser()
      parser.setInput(StringReader(cleanXml))

      val ctx = WffParseContext(stringResources = stringResources)
      var width = 450f
      var height = 450f
      var clipShape = WffClipShape.CIRCLE
      var cornerRadiusX = 0f
      var cornerRadiusY = 0f
      var backgroundColor = 0xFF000000.toInt()
      var backgroundColorExpr: CompiledWffExpr? = null
      var sceneTransforms = emptyList<WffTransform>()
      var sceneVariants = emptyList<WffVariant>()
      var nodes = emptyList<WffSceneNode>()
      var foundWatchFace = false
      var foundScene = false

      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
          when (parser.name) {
            "WatchFace" -> {
              foundWatchFace = true
              width = WffXmlPartParsers.attrFloat(parser, "width", 450f).coerceAtLeast(1f)
              height = WffXmlPartParsers.attrFloat(parser, "height", 450f).coerceAtLeast(1f)
              clipShape = parseClipShape(parser.getAttributeValue(null, "clipShape"))
              cornerRadiusX = WffXmlPartParsers.attrFloat(parser, "cornerRadiusX", 0f)
              cornerRadiusY = WffXmlPartParsers.attrFloat(parser, "cornerRadiusY", 0f)
              ctx.recordItem("WatchFace", WffElementStatus.DRAWN)
              parseWatchFaceChildren(parser, ctx) { bg, bgExpr, st, sv, sceneNodes ->
                foundScene = true
                backgroundColor = bg
                backgroundColorExpr = bgExpr
                sceneTransforms = st
                sceneVariants = sv
                nodes = sceneNodes
              }
            }
            else -> return@runCatching WffParseResult.Error("Root element must be <WatchFace>, found <${parser.name}>.")
          }
        }
        event = parser.next()
      }

      if (!foundWatchFace) return@runCatching WffParseResult.Error("Missing <WatchFace> root element.")
      if (!foundScene) return@runCatching WffParseResult.Error("Missing <Scene> inside <WatchFace>.")

      val resolvedName = ctx.metadata["title"]?.takeIf { it.isNotBlank() }
        ?: ctx.metadata["name"]?.takeIf { it.isNotBlank() }
        ?: fallbackName.ifBlank { faceId }

      WffParseResult.Success(
        WffWatchFace(
          id = faceId,
          name = resolvedName,
          width = width,
          height = height,
          clipShape = clipShape,
          cornerRadiusX = cornerRadiusX,
          cornerRadiusY = cornerRadiusY,
          backgroundColor = backgroundColor,
          backgroundColorExpr = backgroundColorExpr,
          sceneTransforms = sceneTransforms,
          sceneVariants = sceneVariants,
          nodes = nodes,
          userConfigs = ctx.userConfigs.toList(),
          flavors = ctx.flavors,
          bitmapFonts = ctx.bitmapFonts.toMap(),
          metadata = ctx.metadata.toMap(),
          stringResources = stringResources,
          localizationCalendar = ctx.localizationCalendar,
          localizationLocales = ctx.localizationLocales,
          referencedResources = ctx.referencedResources.toSet(),
          referencedFonts = ctx.referencedFonts.toSet(),
          usedSources = ctx.usedSources.toSet(),
          report = WffCompatibilityReport(items = ctx.items.toList()),
        ),
      )
    }.getOrElse { err ->
      WffParseResult.Error("Malformed XML: ${err.message ?: err.javaClass.simpleName}")
    }
  }

  private fun parseWatchFaceChildren(
    parser: XmlPullParser,
    ctx: WffParseContext,
    onScene: (Int, CompiledWffExpr?, List<WffTransform>, List<WffVariant>, List<WffSceneNode>) -> Unit,
  ) {
    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Metadata" -> {
          val key = parser.getAttributeValue(null, "key")?.trim().orEmpty()
          val rawValue = parser.getAttributeValue(null, "value")?.trim().orEmpty()
          val value = ctx.resolveStringOrNull(rawValue) ?: rawValue
          if (key.isNotEmpty()) ctx.metadata[key] = value
          ctx.recordItem("Metadata", WffElementStatus.DRAWN)
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Localization" -> parseLocalization(parser, ctx)
        "UserConfigurations" -> WffXmlPartParsers.parseUserConfigurations(parser, ctx)
        "ColorConfiguration", "ListConfiguration", "BooleanConfiguration", "PhotosConfiguration", "Flavors" ->
          WffXmlPartParsers.parseSingleUserConfig(parser, ctx)
        "BitmapFonts" -> WffXmlPartParsers.parseBitmapFonts(parser, ctx)
        "BitmapFont" -> WffXmlPartParsers.parseSingleBitmapFont(parser, ctx)
        "Scene" -> {
          val (bg, bgExpr) = WffXmlPartParsers.parseColorAndExpr(
            raw = parser.getAttributeValue(null, "backgroundColor"),
            fallback = 0xFF000000.toInt(),
            owner = "Scene",
            ctx = ctx,
          )
          ctx.recordItem("Scene", WffElementStatus.DRAWN)
          val transforms = mutableListOf<WffTransform>()
          val variants = mutableListOf<WffVariant>()
          val sceneNodes = mutableListOf<WffSceneNode>()
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
              "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
              "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
              else -> parseSceneNode(parser, ctx)?.let { sceneNodes += it }
            }
          }
          onScene(bg, bgExpr, transforms, variants, sceneNodes)
        }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, "Unsupported top-level element <${parser.name}>.")
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }
  }

  private fun parseLocalization(parser: XmlPullParser, ctx: WffParseContext) {
    parser.getAttributeValue(null, "calendar")?.trim()?.ifEmpty { null }?.let {
      ctx.localizationCalendar = it
    }
    parser.getAttributeValue(null, "locales")?.trim()?.ifEmpty { null }?.let {
      ctx.localizationLocales = it
    }
    ctx.recordItem("Localization", WffElementStatus.DRAWN)
    WffXmlPartParsers.skipSubtree(parser)
  }

  private fun parseSceneNode(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode? {
    val tag = parser.name
    return when (tag) {
      "Group" -> parseGroup(parser, ctx)
      "Condition" -> parseCondition(parser, ctx)
      "AnalogClock" -> parseAnalogClock(parser, ctx)
      "DigitalClock" -> parseDigitalClock(parser, ctx)
      "PartImage" -> parsePartImage(parser, ctx)
      "PartText" -> parsePartText(parser, ctx)
      "PartDraw" -> parsePartDraw(parser, ctx)
      "ComplicationSlot" -> WffComplicationAndFlavorParser.parseComplicationSlot(parser, ctx, ::parseSceneNode)
      "Localization" -> {
        parseLocalization(parser, ctx)
        null
      }
      else -> {
        ctx.recordItem(tag, WffElementStatus.SKIPPED, reasonForUnsupported(tag))
        WffXmlPartParsers.skipSubtree(parser)
        null
      }
    }
  }

  private fun parseGroup(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.Group {
    ctx.recordItem("Group", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val children = mutableListOf<WffSceneNode>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> parseSceneNode(parser, ctx)?.let { children += it }
      }
    }
    return WffSceneNode.Group(
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      children = children, transforms = transforms, variants = variants,
    )
  }

  private fun parseCondition(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.Condition {
    ctx.recordItem("Condition", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val namedExpressions = HashMap<String, String>()
    val branches = mutableListOf<WffCompareBranch>()
    val defaultChildren = mutableListOf<WffSceneNode>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    // Direct <Condition expression="..."> ... </Condition> support alongside <Expressions>/<Compare>/<Default>
    val inlineCondAttr = parser.getAttributeValue(null, "expression")?.trim()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Expressions" -> {
          ctx.recordItem("Expressions", WffElementStatus.DRAWN)
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "Expression") {
              val name = parser.getAttributeValue(null, "name")?.trim().orEmpty()
              val body = parser.nextText().trim()
              if (name.isNotEmpty() && body.isNotEmpty()) namedExpressions[name] = body
            } else {
              WffXmlPartParsers.skipSubtree(parser)
            }
          }
        }
        "Compare" -> {
          ctx.recordItem("Compare", WffElementStatus.DRAWN)
          val rawExprKey = parser.getAttributeValue(null, "expression")?.trim().orEmpty()
          val resolvedExpr = namedExpressions[rawExprKey] ?: rawExprKey
          val compiled = ctx.compileTracked("Compare($rawExprKey)", resolvedExpr)
          val branchChildren = mutableListOf<WffSceneNode>()
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            parseSceneNode(parser, ctx)?.let { branchChildren += it }
          }
          branches += WffCompareBranch(compiled, branchChildren)
        }
        "Default" -> {
          ctx.recordItem("Default", WffElementStatus.DRAWN)
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            parseSceneNode(parser, ctx)?.let { defaultChildren += it }
          }
        }
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          // If <Condition expression="..."> holds direct child nodes without <Compare>, attach them to a single branch.
          parseSceneNode(parser, ctx)?.let { child ->
            if (!inlineCondAttr.isNullOrEmpty()) {
              val first = branches.firstOrNull()
              if (first == null) {
                branches += WffCompareBranch(ctx.compileTracked("Condition", inlineCondAttr), mutableListOf(child))
              } else {
                branches[0] = first.copy(children = first.children + child)
              }
            } else {
              defaultChildren += child
            }
          }
        }
      }
    }

    return WffSceneNode.Condition(
      branches = branches,
      defaultChildren = defaultChildren,
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      transforms = transforms, variants = variants,
    )
  }

  private fun parseAnalogClock(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.AnalogClock {
    ctx.recordItem("AnalogClock", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val hourHands = mutableListOf<WffHandNode>()
    val minuteHands = mutableListOf<WffHandNode>()
    val secondHands = mutableListOf<WffHandNode>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "HourHand" -> {
          ctx.recordItem("HourHand", WffElementStatus.DRAWN)
          hourHands += parseHandNode(parser, WffHandRole.HOUR, ctx)
        }
        "MinuteHand" -> {
          ctx.recordItem("MinuteHand", WffElementStatus.DRAWN)
          minuteHands += parseHandNode(parser, WffHandRole.MINUTE, ctx)
        }
        "SecondHand" -> {
          ctx.recordItem("SecondHand", WffElementStatus.DRAWN)
          secondHands += parseHandNode(parser, WffHandRole.SECOND, ctx)
        }
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }
    return WffSceneNode.AnalogClock(
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      hourHands = hourHands, minuteHands = minuteHands, secondHands = secondHands,
      transforms = transforms, variants = variants,
    )
  }

  private fun parseHandNode(parser: XmlPullParser, role: WffHandRole, ctx: WffParseContext): WffHandNode {
    val resource = parser.getAttributeValue(null, "resource")?.trim().orEmpty()
    if (resource.isNotEmpty()) ctx.referencedResources += resource
    val x = WffXmlPartParsers.attrFloat(parser, "x", 0f)
    val y = WffXmlPartParsers.attrFloat(parser, "y", 0f)
    val width = WffXmlPartParsers.attrFloat(parser, "width", 40f)
    val height = WffXmlPartParsers.attrFloat(parser, "height", 200f)
    val pivotX = WffXmlPartParsers.attrFloat(parser, "pivotX", 0.5f)
    val pivotY = WffXmlPartParsers.attrFloat(parser, "pivotY", 0.5f)
    val scaleX = WffXmlPartParsers.attrFloat(parser, "scaleX", 1f)
    val scaleY = WffXmlPartParsers.attrFloat(parser, "scaleY", 1f)
    val alpha = WffXmlPartParsers.attrInt(parser, "alpha", 255).coerceIn(0, 255)
    val rawTint = parser.getAttributeValue(null, "tintColor")
    val (tintColor, tintColorExpr) = if (rawTint != null) {
      val (cVal, cExpr) = WffXmlPartParsers.parseColorAndExpr(rawTint, 0xFFFFFFFF.toInt(), "Hand($role)", ctx)
      (if (cExpr == null) cVal else null) to cExpr
    } else null to null

    var motionPolicy: WffMotionPolicy? = null
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Sweep" -> {
          val freq = WffXmlPartParsers.attrInt(parser, "frequency", 15).coerceIn(1, 60)
          motionPolicy = WffMotionPolicy.Sweep(frequency = freq)
          ctx.recordItem("Sweep", WffElementStatus.DRAWN)
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Tick" -> {
          val dur = WffXmlPartParsers.attrFloat(parser, "duration", 0.2f)
          val strength = WffXmlPartParsers.attrFloat(parser, "strength", 0f)
          motionPolicy = WffMotionPolicy.Tick(duration = dur, strength = strength)
          ctx.recordItem("Tick", WffElementStatus.DRAWN)
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    return WffHandNode(
      role = role, resource = resource, x = x, y = y, width = width, height = height,
      pivotX = pivotX, pivotY = pivotY, scaleX = scaleX, scaleY = scaleY, alpha = alpha,
      tintColor = tintColor, tintColorExpr = tintColorExpr, motionPolicy = motionPolicy,
      transforms = transforms, variants = variants,
    )
  }

  private fun parseDigitalClock(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.DigitalClock {
    ctx.recordItem("DigitalClock", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val timeTexts = mutableListOf<WffTimeTextNode>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "TimeText" -> {
          ctx.recordItem("TimeText", WffElementStatus.DRAWN)
          timeTexts += parseTimeText(parser, c.width, c.height, ctx)
        }
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }
    return WffSceneNode.DigitalClock(
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      timeTexts = timeTexts, transforms = transforms, variants = variants,
    )
  }

  private fun parseTimeText(
    parser: XmlPullParser,
    parentW: Float,
    parentH: Float,
    ctx: WffParseContext,
  ): WffTimeTextNode {
    var format = parser.getAttributeValue(null, "format")?.trim()
      ?: parser.getAttributeValue(null, "timeFormat")?.trim()
      ?: "hh:mm"
    var hourFormat = parser.getAttributeValue(null, "hourFormat")?.trim() ?: "SYNC_TO_DEVICE"
    val x = WffXmlPartParsers.attrFloat(parser, "x", 0f)
    val y = WffXmlPartParsers.attrFloat(parser, "y", 0f)
    val width = WffXmlPartParsers.attrFloat(parser, "width", parentW.coerceAtLeast(100f))
    val height = WffXmlPartParsers.attrFloat(parser, "height", parentH.coerceAtLeast(48f))
    val align = WffXmlPartParsers.parseTextAlign(parser.getAttributeValue(null, "align"))
    val alpha = WffXmlPartParsers.attrInt(parser, "alpha", 255).coerceIn(0, 255)
    var fontFamily = parser.getAttributeValue(null, "fontFamily")
    var fontSize = WffXmlPartParsers.attrFloat(parser, "size", 36f)
    var (color, colorExpr) = WffXmlPartParsers.parseColorAndExpr(
      parser.getAttributeValue(null, "color"),
      0xFFFFFFFF.toInt(),
      "TimeText",
      ctx,
    )
    var weight: String? = null
    var slant: String? = null
    var bitmapFontName: String? = null
    var shadow: WffTextShadow? = null
    var outline: WffTextOutline? = null
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "TimeFormat" -> {
          ctx.recordItem("TimeFormat", WffElementStatus.DRAWN)
          parser.getAttributeValue(null, "format")?.trim()?.let { if (it.isNotEmpty()) format = it }
          parser.getAttributeValue(null, "hourFormat")?.trim()?.let { if (it.isNotEmpty()) hourFormat = it }
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Font", "BitmapFont" -> {
          val isBitmap = parser.name == "BitmapFont"
          val fam = parser.getAttributeValue(null, "family")?.trim()
          if (isBitmap) bitmapFontName = fam else fontFamily = fam ?: fontFamily
          fam?.takeIf { !isBitmap && it !in listOf("sans-serif", "serif", "monospace") }?.let {
            ctx.referencedFonts += it
          }
          fontSize = WffXmlPartParsers.attrFloat(parser, "size", fontSize)
          parser.getAttributeValue(null, "color")?.let { rawC ->
            val (cVal, cEx) = WffXmlPartParsers.parseColorAndExpr(rawC, color, "Font", ctx)
            color = cVal; if (cEx != null) colorExpr = cEx
          }
          weight = parser.getAttributeValue(null, "weight") ?: weight
          slant = parser.getAttributeValue(null, "slant") ?: slant
          ctx.recordItem(parser.name, WffElementStatus.DRAWN)
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
              "Shadow" -> shadow = WffXmlPartParsers.parseShadow(parser, ctx)
              "Outline" -> outline = WffXmlPartParsers.parseOutline(parser, ctx)
              else -> WffXmlPartParsers.skipSubtree(parser)
            }
          }
        }
        "Localization" -> parseLocalization(parser, ctx)
        "Shadow" -> shadow = WffXmlPartParsers.parseShadow(parser, ctx)
        "Outline" -> outline = WffXmlPartParsers.parseOutline(parser, ctx)
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    return WffTimeTextNode(
      format = format, hourFormat = hourFormat, x = x, y = y, width = width, height = height,
      align = align, alpha = alpha, fontFamily = fontFamily, fontSize = fontSize,
      color = color, colorExpr = colorExpr, weight = weight, slant = slant,
      bitmapFontName = bitmapFontName, shadow = shadow, outline = outline,
      transforms = transforms, variants = variants,
    )
  }

  private fun parsePartImage(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.PartImage {
    ctx.recordItem("PartImage", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val rawTint = parser.getAttributeValue(null, "tintColor")
    val (tintColor, tintExpr) = if (rawTint != null) {
      val (cv, ce) = WffXmlPartParsers.parseColorAndExpr(rawTint, 0xFFFFFFFF.toInt(), "PartImage", ctx)
      (if (ce == null) cv else null) to ce
    } else null to null
    var blendMode = WffXmlPartParsers.parseBlendMode(parser.getAttributeValue(null, "blendMode"))
    val resources = mutableListOf<String>()
    parser.getAttributeValue(null, "resource")?.trim()?.ifEmpty { null }?.let {
      resources += it
      if (!it.startsWith("[")) ctx.referencedResources += it
    }
    var photosSpec: WffPhotosSpec? = null
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Image" -> {
          ctx.recordItem("Image", WffElementStatus.DRAWN)
          parser.getAttributeValue(null, "blendMode")?.let { blendMode = WffXmlPartParsers.parseBlendMode(it) }
          parser.getAttributeValue(null, "resource")?.trim()?.ifEmpty { null }?.let {
            resources += it
            if (!it.startsWith("[")) ctx.referencedResources += it
          }
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Images" -> {
          ctx.recordItem("Images", WffElementStatus.DRAWN)
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "Image") {
              parser.getAttributeValue(null, "resource")?.trim()?.ifEmpty { null }?.let {
                resources += it
                if (!it.startsWith("[")) ctx.referencedResources += it
              }
            }
            WffXmlPartParsers.skipSubtree(parser)
          }
        }
        "Photos" -> photosSpec = WffComplicationAndFlavorParser.parsePhotosTag(parser, ctx)
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    return WffSceneNode.PartImage(
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      tintColor = tintColor, tintColorExpr = tintExpr, blendMode = blendMode,
      resources = resources, photos = photosSpec, transforms = transforms, variants = variants,
    )
  }

  private fun parsePartText(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.PartText {
    ctx.recordItem("PartText", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    var align = WffTextAlign.CENTER
    var fontFamily: String? = null
    var fontSize = 24f
    var color = 0xFFFFFFFF.toInt()
    var colorExpr: CompiledWffExpr? = null
    var weight: String? = null
    var slant: String? = null
    var bitmapFontName: String? = null
    var shadow: WffTextShadow? = null
    var outline: WffTextOutline? = null
    var rawText: String? = null
    var template: String? = null
    val parameters = mutableListOf<CompiledWffExpr>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    fun handleContent(tagName: String) {
      when (tagName) {
        "Template" -> {
          ctx.recordItem("Template", WffElementStatus.DRAWN)
          val txt = readTemplateCollectingParams(parser, parameters, ctx)
          if (txt.isNotEmpty()) template = txt
        }
        "Parameter" -> {
          ctx.recordItem("Parameter", WffElementStatus.DRAWN)
          val exprStr = parser.getAttributeValue(null, "expression")?.trim().orEmpty()
          parameters += ctx.compileTracked("Parameter", exprStr)
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Shadow" -> shadow = WffXmlPartParsers.parseShadow(parser, ctx)
        "Outline" -> outline = WffXmlPartParsers.parseOutline(parser, ctx)
        "Localization" -> parseLocalization(parser, ctx)
        "Font", "BitmapFont" -> {
          val isBitmap = tagName == "BitmapFont"
          val fam = parser.getAttributeValue(null, "family")?.trim()
          if (isBitmap) bitmapFontName = fam else fontFamily = fam ?: fontFamily
          fam?.takeIf { !isBitmap && it !in listOf("sans-serif", "serif", "monospace") }?.let {
            ctx.referencedFonts += it
          }
          fontSize = WffXmlPartParsers.attrFloat(parser, "size", fontSize)
          parser.getAttributeValue(null, "color")?.let { rawC ->
            val (cVal, cEx) = WffXmlPartParsers.parseColorAndExpr(rawC, color, "Font", ctx)
            color = cVal; if (cEx != null) colorExpr = cEx
          }
          weight = parser.getAttributeValue(null, "weight") ?: weight
          slant = parser.getAttributeValue(null, "slant") ?: slant
          ctx.recordItem(tagName, WffElementStatus.DRAWN)
          val textBuf = StringBuilder()
          while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_TAG) break
            if (ev == XmlPullParser.TEXT) textBuf.append(parser.text)
            else if (ev == XmlPullParser.START_TAG) handleContent(parser.name)
          }
          val literal = textBuf.toString().trim()
          if (literal.isNotEmpty() && rawText == null && template == null) {
            rawText = ctx.resolveStringOrNull(literal) ?: literal
          }
        }
        else -> {
          ctx.recordItem(tagName, WffElementStatus.SKIPPED, reasonForUnsupported(tagName))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Text" -> {
          ctx.recordItem("Text", WffElementStatus.DRAWN)
          align = WffXmlPartParsers.parseTextAlign(parser.getAttributeValue(null, "align"))
          val textBuf = StringBuilder()
          while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_TAG) break
            if (ev == XmlPullParser.TEXT) textBuf.append(parser.text)
            else if (ev == XmlPullParser.START_TAG) handleContent(parser.name)
          }
          val trimmed = textBuf.toString().trim()
          if (trimmed.isNotEmpty() && rawText == null && template == null) {
            rawText = ctx.resolveStringOrNull(trimmed) ?: trimmed
          }
        }
        "Template", "Parameter", "Font", "BitmapFont", "Shadow", "Outline", "Localization" -> handleContent(parser.name)
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    return WffSceneNode.PartText(
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      align = align, fontFamily = fontFamily, fontSize = fontSize,
      color = color, colorExpr = colorExpr, weight = weight, slant = slant,
      bitmapFontName = bitmapFontName, shadow = shadow, outline = outline,
      rawText = rawText, template = template, parameters = parameters,
      transforms = transforms, variants = variants,
    )
  }

  private fun readTemplateCollectingParams(
    parser: XmlPullParser,
    parameters: MutableList<CompiledWffExpr>,
    ctx: WffParseContext,
  ): String {
    val sb = StringBuilder()
    while (true) {
      val ev = parser.next()
      if (ev == XmlPullParser.END_TAG) break
      if (ev == XmlPullParser.TEXT) {
        sb.append(parser.text)
      } else if (ev == XmlPullParser.START_TAG) {
        if (parser.name == "Parameter") {
          ctx.recordItem("Parameter", WffElementStatus.DRAWN)
          val exprStr = parser.getAttributeValue(null, "expression")?.trim().orEmpty()
          parameters += ctx.compileTracked("Parameter", exprStr)
          WffXmlPartParsers.skipSubtree(parser)
        } else if (parser.name == "Localization") {
          parseLocalization(parser, ctx)
        } else {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }
    return ctx.resolveStringOrNull(sb.toString().trim()) ?: sb.toString().trim()
  }

  private fun parsePartDraw(parser: XmlPullParser, ctx: WffParseContext): WffSceneNode.PartDraw {
    ctx.recordItem("PartDraw", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val shapes = mutableListOf<WffDrawShape>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Ellipse", "Rectangle", "RoundRectangle", "Arc", "Line" ->
          shapes += parseDrawShape(parser, parser.name, c, ctx)
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, reasonForUnsupported(parser.name))
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }
    return WffSceneNode.PartDraw(
      x = c.x, y = c.y, width = c.width, height = c.height,
      pivotX = c.pivotX, pivotY = c.pivotY, angle = c.angle,
      scaleX = c.scaleX, scaleY = c.scaleY, alpha = c.alpha, visible = c.visible,
      shapes = shapes, transforms = transforms, variants = variants,
    )
  }

  private fun parseDrawShape(
    parser: XmlPullParser,
    shapeTag: String,
    parent: WffXmlPartParsers.CommonAttrs,
    ctx: WffParseContext,
  ): WffDrawShape {
    ctx.recordItem(shapeTag, WffElementStatus.DRAWN)
    val x = WffXmlPartParsers.attrFloat(parser, "x", 0f)
    val y = WffXmlPartParsers.attrFloat(parser, "y", 0f)
    val width = WffXmlPartParsers.attrFloat(parser, "width", parent.width)
    val height = WffXmlPartParsers.attrFloat(parser, "height", parent.height)
    val cornerRadiusX = WffXmlPartParsers.attrFloat(parser, "cornerRadiusX", 8f)
    val cornerRadiusY = WffXmlPartParsers.attrFloat(parser, "cornerRadiusY", cornerRadiusX)
    val centerX = WffXmlPartParsers.attrFloat(parser, "centerX", width / 2f)
    val centerY = WffXmlPartParsers.attrFloat(parser, "centerY", height / 2f)
    val startAngle = WffXmlPartParsers.attrFloat(parser, "startAngle", 0f)
    val sweepAngle = WffXmlPartParsers.attrFloat(parser, "sweepAngle", WffXmlPartParsers.attrFloat(parser, "endAngle", 90f) - startAngle)
    val startX = WffXmlPartParsers.attrFloat(parser, "startX", 0f)
    val startY = WffXmlPartParsers.attrFloat(parser, "startY", 0f)
    val endX = WffXmlPartParsers.attrFloat(parser, "endX", width)
    val endY = WffXmlPartParsers.attrFloat(parser, "endY", height)

    var fillColor: Int? = null
    var fillExpr: CompiledWffExpr? = null
    var strokeColor: Int? = null
    var strokeExpr: CompiledWffExpr? = null
    var strokeWidth = 2f
    var cap = android.graphics.Paint.Cap.ROUND
    val shapeTransforms = mutableListOf<WffTransform>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Fill" -> {
          ctx.recordItem("Fill", WffElementStatus.DRAWN)
          val (cv, ce) = WffXmlPartParsers.parseColorAndExpr(
            parser.getAttributeValue(null, "color"),
            0xFFFFFFFF.toInt(),
            "Fill",
            ctx,
          )
          if (ce == null) fillColor = cv else fillExpr = ce
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Stroke" -> {
          ctx.recordItem("Stroke", WffElementStatus.DRAWN)
          val (cv, ce) = WffXmlPartParsers.parseColorAndExpr(
            parser.getAttributeValue(null, "color"),
            0xFFFFFFFF.toInt(),
            "Stroke",
            ctx,
          )
          if (ce == null) strokeColor = cv else strokeExpr = ce
          strokeWidth = WffXmlPartParsers.attrFloat(parser, "thickness", 2f)
          cap = WffXmlPartParsers.parseCap(parser.getAttributeValue(null, "cap"))
          WffXmlPartParsers.skipSubtree(parser)
        }
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { shapeTransforms += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.PARTLY_DRAWN, "Sub-element <${parser.name}> skipped inside <$shapeTag>.")
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    return when (shapeTag) {
      "Ellipse" -> WffDrawShape.Ellipse(x, y, width, height, fillColor, fillExpr, strokeColor, strokeExpr, strokeWidth, cap)
      "Rectangle" -> WffDrawShape.Rectangle(x, y, width, height, fillColor, fillExpr, strokeColor, strokeExpr, strokeWidth, cap)
      "RoundRectangle" -> WffDrawShape.RoundRectangle(x, y, width, height, cornerRadiusX, cornerRadiusY, fillColor, fillExpr, strokeColor, strokeExpr, strokeWidth, cap)
      "Arc" -> WffDrawShape.Arc(centerX, centerY, width, height, startAngle, sweepAngle, fillColor, fillExpr, strokeColor, strokeExpr, strokeWidth, cap, shapeTransforms)
      else -> WffDrawShape.Line(startX, startY, endX, endY, fillColor, fillExpr, strokeColor, strokeExpr, strokeWidth, cap)
    }
  }

  private fun parseClipShape(raw: String?): WffClipShape = when (raw?.trim()?.uppercase(Locale.US)) {
    "NONE" -> WffClipShape.NONE
    "RECTANGLE" -> WffClipShape.RECTANGLE
    else -> WffClipShape.CIRCLE
  }

  private fun reasonForUnsupported(tag: String): String = when (tag) {
    "ComplicationSlot" -> "Watch complications are not supported."
    "Launch" -> "App launch targets are not supported."
    else -> "Unsupported Watch Face Format element <$tag>."
  }
}

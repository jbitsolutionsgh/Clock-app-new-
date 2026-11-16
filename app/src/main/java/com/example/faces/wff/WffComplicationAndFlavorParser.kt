package com.example.faces.wff

import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Modular XML sub-parsers for WFF Update 9 elements:
 * `<Flavors>`, `<PhotosConfiguration>`, `<Photos>`, and `<ComplicationSlot>`.
 */
internal object WffComplicationAndFlavorParser {

  /** Parses `<PhotosConfiguration>` under `<UserConfigurations>`. */
  fun parsePhotosConfiguration(parser: XmlPullParser, ctx: WffParseContext) {
    val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
    val rawName = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { id }
    val displayName = ctx.resolveString(rawName)
    val typeAttr = parser.getAttributeValue(null, "configType")?.trim()?.uppercase(Locale.US)
      ?: parser.getAttributeValue(null, "type")?.trim()?.uppercase(Locale.US)
    val photosType = if (typeAttr == "SINGLE") WffPhotosType.SINGLE else WffPhotosType.MULTIPLE
    val maxPhotos = WffXmlPartParsers.attrInt(parser, "maxNumberOfPhotos", if (photosType == WffPhotosType.SINGLE) 1 else 10)
      .coerceIn(1, 30)
    WffXmlPartParsers.skipSubtree(parser)
    if (id.isNotEmpty()) {
      ctx.userConfigs += WffUserConfig.PhotosConfig(id, displayName, photosType, maxPhotos)
      ctx.recordItem("PhotosConfiguration", WffElementStatus.DRAWN)
    }
  }

  /** Parses `<Flavors>` and its `<Flavor>` preset bundles under `<UserConfigurations>`. */
  fun parseFlavors(parser: XmlPullParser, ctx: WffParseContext) {
    val defaultFlavor = parser.getAttributeValue(null, "defaultValue")?.trim().orEmpty()
    val flavorList = mutableListOf<WffFlavor>()
    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      if (parser.name == "Flavor") {
        parseSingleFlavor(parser, ctx)?.let { flavorList += it }
      } else {
        WffXmlPartParsers.skipSubtree(parser)
      }
    }
    if (flavorList.isNotEmpty()) {
      ctx.flavors = WffFlavorSet(
        defaultFlavorId = defaultFlavor.ifEmpty { flavorList.first().id },
        flavors = flavorList,
      )
      ctx.recordItem("Flavors", WffElementStatus.DRAWN)
      ctx.recordItem("Flavor", WffElementStatus.DRAWN)
    }
  }

  private fun parseSingleFlavor(parser: XmlPullParser, ctx: WffParseContext): WffFlavor? {
    val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
    val rawName = parser.getAttributeValue(null, "displayName")?.trim().orEmpty().ifEmpty { id }
    val displayName = ctx.resolveString(rawName)
    val iconRes = parser.getAttributeValue(null, "icon")?.trim()?.ifEmpty { null }
    if (iconRes != null && !iconRes.startsWith("[")) {
      ctx.referencedResources += iconRes
    }
    val configChoices = mutableListOf<WffFlavorConfigChoice>()
    val compChoices = mutableListOf<WffFlavorComplicationChoice>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "Configuration" -> {
          val cfgId = parser.getAttributeValue(null, "id")?.trim().orEmpty()
          val optId = parser.getAttributeValue(null, "optionId")?.trim()
            ?: parser.getAttributeValue(null, "optionIdValue")?.trim()
            ?: ""
          if (cfgId.isNotEmpty() && optId.isNotEmpty()) {
            configChoices += WffFlavorConfigChoice(cfgId, optId)
          }
          WffXmlPartParsers.skipSubtree(parser)
        }
        "ComplicationSlot" -> {
          val slotId = parser.getAttributeValue(null, "slotId")?.trim().orEmpty()
          var policy: WffDefaultProviderPolicy? = null
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "DefaultProviderPolicy") {
              policy = parseDefaultProviderPolicy(parser)
            } else {
              WffXmlPartParsers.skipSubtree(parser)
            }
          }
          if (slotId.isNotEmpty() && policy != null) {
            compChoices += WffFlavorComplicationChoice(
              slotId = slotId,
              defaultSystemProvider = policy.defaultSystemProvider,
              defaultSystemProviderType = policy.defaultSystemProviderType,
            )
          }
        }
        else -> WffXmlPartParsers.skipSubtree(parser)
      }
    }
    if (id.isEmpty()) return null
    return WffFlavor(id, displayName, iconRes, configChoices, compChoices)
  }

  /** Parses `<Photos .../>` nested inside `<PartImage>`. */
  fun parsePhotosTag(parser: XmlPullParser, ctx: WffParseContext): WffPhotosSpec {
    ctx.recordItem("Photos", WffElementStatus.DRAWN)
    val rawSource = parser.getAttributeValue(null, "source")?.trim()
    val sourceExpr = if (!rawSource.isNullOrEmpty()) ctx.compileTracked("Photos", rawSource) else null
    val configId = extractConfigId(rawSource)
    val defaultRes = parser.getAttributeValue(null, "defaultImageResource")?.trim()?.ifEmpty { null }
      ?: parser.getAttributeValue(null, "defaultResource")?.trim()?.ifEmpty { null }
    if (defaultRes != null && !defaultRes.startsWith("[")) {
      ctx.referencedResources += defaultRes
    }
    val changeAttr = parser.getAttributeValue(null, "change")?.trim()?.uppercase(Locale.US).orEmpty()
    val changeTokens = changeAttr.split(Regex("[\\s,|]+")).filter { it.isNotEmpty() }
    val onTap = "TAP" in changeTokens || changeTokens.isEmpty()
    val onVisible = "ON_VISIBLE" in changeTokens
    val changeAfterEvery = WffXmlPartParsers.attrInt(parser, "changeAfterEvery", 1).coerceAtLeast(1)
    WffXmlPartParsers.skipSubtree(parser)
    return WffPhotosSpec(
      sourceExpr = sourceExpr,
      configId = configId,
      defaultResource = defaultRes,
      changeOnTap = onTap,
      changeOnVisible = onVisible,
      changeAfterEvery = changeAfterEvery,
    )
  }

  private fun extractConfigId(rawSource: String?): String? {
    if (rawSource.isNullOrBlank()) return null
    val match = Regex("\\[CONFIGURATION\\.([^\\].]+)", RegexOption.IGNORE_CASE).find(rawSource)
    return match?.groupValues?.getOrNull(1)?.trim()
  }

  /** Parses `<ComplicationSlot>` including bounding geometry, provider policy, and nested `<Complication>` templates. */
  fun parseComplicationSlot(
    parser: XmlPullParser,
    ctx: WffParseContext,
    parseSceneNode: (XmlPullParser, WffParseContext) -> WffSceneNode?,
  ): WffSceneNode.ComplicationSlot {
    ctx.recordItem("ComplicationSlot", WffElementStatus.DRAWN)
    val c = WffXmlPartParsers.readCommonAttrs(parser)
    val slotId = parser.getAttributeValue(null, "slotId")?.trim()?.ifEmpty { null } ?: "0"
    val rawName = parser.getAttributeValue(null, "displayName")?.trim()?.ifEmpty { null }
      ?: parser.getAttributeValue(null, "name")?.trim()?.ifEmpty { null }
      ?: "Slot $slotId"
    val displayName = ctx.resolveString(rawName)
    val supportedTypes = parser.getAttributeValue(null, "supportedTypes")
      ?.trim()
      ?.split(Regex("[\\s,|]+"))
      ?.map { it.trim().uppercase(Locale.US) }
      ?.filter { it.isNotEmpty() }
      ?: listOf("SHORT_TEXT")
    val isCustomizable = !parser.getAttributeValue(null, "isCustomizable").equals("false", ignoreCase = true)

    var boundingShape: WffBoundingShape? = null
    var policy: WffDefaultProviderPolicy? = null
    val templates = mutableListOf<WffComplicationTemplate>()
    val transforms = mutableListOf<WffTransform>()
    val variants = mutableListOf<WffVariant>()

    while (parser.next() != XmlPullParser.END_TAG) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      when (parser.name) {
        "BoundingOval" -> {
          ctx.recordItem("BoundingOval", WffElementStatus.DRAWN)
          boundingShape = WffBoundingShape.Oval(
            x = WffXmlPartParsers.attrFloat(parser, "x", 0f),
            y = WffXmlPartParsers.attrFloat(parser, "y", 0f),
            width = WffXmlPartParsers.attrFloat(parser, "width", c.width),
            height = WffXmlPartParsers.attrFloat(parser, "height", c.height),
          )
          WffXmlPartParsers.skipSubtree(parser)
        }
        "BoundingRoundBox" -> {
          ctx.recordItem("BoundingRoundBox", WffElementStatus.DRAWN)
          boundingShape = WffBoundingShape.RoundBox(
            x = WffXmlPartParsers.attrFloat(parser, "x", 0f),
            y = WffXmlPartParsers.attrFloat(parser, "y", 0f),
            width = WffXmlPartParsers.attrFloat(parser, "width", c.width),
            height = WffXmlPartParsers.attrFloat(parser, "height", c.height),
            cornerRadiusX = WffXmlPartParsers.attrFloat(parser, "cornerRadiusX", 12f),
            cornerRadiusY = WffXmlPartParsers.attrFloat(parser, "cornerRadiusY", 12f),
          )
          WffXmlPartParsers.skipSubtree(parser)
        }
        "BoundingBox" -> {
          ctx.recordItem("BoundingBox", WffElementStatus.DRAWN)
          boundingShape = WffBoundingShape.Box(
            x = WffXmlPartParsers.attrFloat(parser, "x", 0f),
            y = WffXmlPartParsers.attrFloat(parser, "y", 0f),
            width = WffXmlPartParsers.attrFloat(parser, "width", c.width),
            height = WffXmlPartParsers.attrFloat(parser, "height", c.height),
          )
          WffXmlPartParsers.skipSubtree(parser)
        }
        "BoundingArc" -> {
          ctx.recordItem("BoundingArc", WffElementStatus.DRAWN)
          if (boundingShape == null) {
            boundingShape = WffBoundingShape.Oval(0f, 0f, c.width, c.height)
          }
          WffXmlPartParsers.skipSubtree(parser)
        }
        "DefaultProviderPolicy" -> {
          ctx.recordItem("DefaultProviderPolicy", WffElementStatus.DRAWN)
          policy = parseDefaultProviderPolicy(parser)
        }
        "Complication" -> {
          ctx.recordItem("Complication", WffElementStatus.DRAWN)
          val compType = parser.getAttributeValue(null, "type")?.trim()?.uppercase(Locale.US) ?: "SHORT_TEXT"
          val children = mutableListOf<WffSceneNode>()
          while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            parseSceneNode(parser, ctx)?.let { children += it }
          }
          templates += WffComplicationTemplate(compType, children)
        }
        "Transform" -> WffXmlPartParsers.parseTransform(parser, ctx)?.let { transforms += it }
        "Variant" -> WffXmlPartParsers.parseVariant(parser, ctx)?.let { variants += it }
        else -> {
          ctx.recordItem(parser.name, WffElementStatus.SKIPPED, "Unsupported child <${parser.name}> in ComplicationSlot.")
          WffXmlPartParsers.skipSubtree(parser)
        }
      }
    }

    return WffSceneNode.ComplicationSlot(
      slotId = slotId,
      displayName = displayName,
      x = c.x,
      y = c.y,
      width = c.width,
      height = c.height,
      pivotX = c.pivotX,
      pivotY = c.pivotY,
      angle = c.angle,
      scaleX = c.scaleX,
      scaleY = c.scaleY,
      alpha = c.alpha,
      visible = c.visible,
      supportedTypes = supportedTypes,
      isCustomizable = isCustomizable,
      boundingShape = boundingShape ?: WffBoundingShape.Box(0f, 0f, c.width, c.height),
      defaultProviderPolicy = policy,
      templates = templates,
      transforms = transforms,
      variants = variants,
    )
  }

  private fun parseDefaultProviderPolicy(parser: XmlPullParser): WffDefaultProviderPolicy {
    val sysProv = parser.getAttributeValue(null, "defaultSystemProvider")?.trim()
    val sysType = parser.getAttributeValue(null, "defaultSystemProviderType")?.trim()
    val primary = parser.getAttributeValue(null, "primaryDataSource")?.trim()
    WffXmlPartParsers.skipSubtree(parser)
    return WffDefaultProviderPolicy(
      defaultSystemProvider = sysProv,
      defaultSystemProviderType = sysType,
      primaryDataSource = primary,
    )
  }
}

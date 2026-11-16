package com.example.faces.wff

/** Supported WFF PhotosConfiguration selection mode. */
enum class WffPhotosType {
  SINGLE,
  MULTIPLE,
}

/** Configuration metadata for a `<Photos>` block inside a `<PartImage>`. */
data class WffPhotosSpec(
  val sourceExpr: CompiledWffExpr?,
  val configId: String?,
  val defaultResource: String?,
  val changeOnTap: Boolean,
  val changeOnVisible: Boolean,
  val changeAfterEvery: Int,
)

/** Single configuration key-value choice inside a `<Flavor>`. */
data class WffFlavorConfigChoice(
  val configId: String,
  val optionId: String,
)

/** Default complication assignment inside a `<Flavor>`. */
data class WffFlavorComplicationChoice(
  val slotId: String,
  val defaultSystemProvider: String?,
  val defaultSystemProviderType: String?,
)

/** Preset `<Flavor>` bundling UserConfiguration options and ComplicationSlot defaults. */
data class WffFlavor(
  val id: String,
  val displayName: String,
  val iconResource: String? = null,
  val configChoices: List<WffFlavorConfigChoice> = emptyList(),
  val complicationChoices: List<WffFlavorComplicationChoice> = emptyList(),
)

/** Collection of `<Flavor>` presets declared under `<UserConfigurations><Flavors>`. */
data class WffFlavorSet(
  val defaultFlavorId: String,
  val flavors: List<WffFlavor>,
)

/** Clipping geometry defined by BoundingOval, BoundingRoundBox, or BoundingBox in a ComplicationSlot. */
sealed class WffBoundingShape {
  abstract val x: Float
  abstract val y: Float
  abstract val width: Float
  abstract val height: Float

  data class Oval(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
  ) : WffBoundingShape()

  data class RoundBox(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val cornerRadiusX: Float,
    val cornerRadiusY: Float,
  ) : WffBoundingShape()

  data class Box(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
  ) : WffBoundingShape()
}

/** WFF `<DefaultProviderPolicy>` attributes on a `<ComplicationSlot>`. */
data class WffDefaultProviderPolicy(
  val defaultSystemProvider: String? = null,
  val defaultSystemProviderType: String? = null,
  val primaryDataSource: String? = null,
)

/** Render template for a specific complication type (e.g. SHORT_TEXT, RANGED_VALUE) inside a slot. */
data class WffComplicationTemplate(
  val type: String,
  val children: List<WffSceneNode>,
)

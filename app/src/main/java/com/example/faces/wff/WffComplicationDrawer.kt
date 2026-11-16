package com.example.faces.wff

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Interactive hit-test region recorded during WFF Canvas drawing in screen pixel coordinates. */
internal data class WffInteractiveSlotHit(
  val slotId: String,
  val provider: AuraComplicationProvider,
  val rect: RectF,
)

/** Interactive photo-tap region recorded during WFF Canvas drawing in screen pixel coordinates. */
internal data class WffInteractivePhotoHit(
  val configId: String,
  val rect: RectF,
)

/**
 * Draws `<ComplicationSlot>` elements (clipping to `BoundingOval`/`BoundingRoundBox`/`BoundingBox`,
 * binding `COMPLICATION.*` into `evalCtx`, drawing child `<Complication>` templates or an offline
 * fallback card, and rendering built-in vector icons for `[COMPLICATION.*_IMAGE]`).
 */
internal object WffComplicationDrawer {

  /** Renders [slot] onto [canvas], recording its mapped touch bounds into [slotHits]. */
  fun drawComplicationSlot(
    canvas: android.graphics.Canvas,
    slot: WffSceneNode.ComplicationSlot,
    width: Float,
    height: Float,
    slotIndex: Int,
    complicationProviders: Map<String, AuraComplicationProvider>,
    evalCtx: WffEvalContext,
    showEventTitles: Boolean,
    scratch: WffDrawHelpers.DrawScratch,
    effectiveAlpha: Int,
    slotHits: MutableList<WffInteractiveSlotHit>,
    drawChild: (WffSceneNode, Int) -> Unit,
  ) {
    val provider = complicationProviders[slot.slotId]
      ?: AuraComplicationProvider.fromPolicy(slot.defaultProviderPolicy, slot.supportedTypes, slotIndex)
    val resolved = WffComplicationResolver.resolve(slot, provider, evalCtx.data, showEventTitles)

    val w = if (width > 0f) width else slot.boundingShape.width.coerceAtLeast(48f)
    val h = if (height > 0f) height else slot.boundingShape.height.coerceAtLeast(48f)

    // Record mapped screen bounds so taps anywhere in the slot navigate to the corresponding tab.
    @Suppress("DEPRECATION")
    canvas.getMatrix(scratch.hitMatrix)
    scratch.rectF.set(0f, 0f, w, h)
    val mappedRect = RectF()
    scratch.hitMatrix.mapRect(mappedRect, scratch.rectF)
    slotHits += WffInteractiveSlotHit(slot.slotId, provider, mappedRect)

    if (resolved.isEmpty) return

    val clipSave = canvas.save()
    clipToBoundingShape(canvas, slot.boundingShape, w, h, scratch)

    val prevComplication = evalCtx.activeComplication
    evalCtx.activeComplication = resolved
    evalCtx.compVer++
    try {
      val template = slot.templates.firstOrNull { it.type.equals(resolved.activeType, ignoreCase = true) }
        ?: slot.templates.firstOrNull { it.type.equals("SHORT_TEXT", ignoreCase = true) }
        ?: slot.templates.firstOrNull()

      if (template != null && template.children.isNotEmpty()) {
        for (child in template.children) {
          drawChild(child, effectiveAlpha)
        }
      } else {
        drawDefaultSlotFallback(canvas, resolved, w, h, scratch, effectiveAlpha)
      }
    } finally {
      evalCtx.activeComplication = prevComplication
      canvas.restoreToCount(clipSave)
    }
  }

  private fun clipToBoundingShape(
    canvas: android.graphics.Canvas,
    shape: WffBoundingShape,
    fallbackW: Float,
    fallbackH: Float,
    scratch: WffDrawHelpers.DrawScratch,
  ) {
    val bx = shape.x
    val by = shape.y
    val bw = if (shape.width > 0f) shape.width else fallbackW
    val bh = if (shape.height > 0f) shape.height else fallbackH
    scratch.path.rewind()
    scratch.rectF.set(bx, by, bx + bw, by + bh)
    when (shape) {
      is WffBoundingShape.Oval -> scratch.path.addOval(scratch.rectF, Path.Direction.CW)
      is WffBoundingShape.RoundBox -> scratch.path.addRoundRect(scratch.rectF, shape.cornerRadiusX, shape.cornerRadiusY, Path.Direction.CW)
      is WffBoundingShape.Box -> scratch.path.addRect(scratch.rectF, Path.Direction.CW)
    }
    canvas.clipPath(scratch.path)
  }

  private fun drawDefaultSlotFallback(
    canvas: android.graphics.Canvas,
    resolved: WffResolvedComplication,
    width: Float,
    height: Float,
    scratch: WffDrawHelpers.DrawScratch,
    alpha: Int,
  ) {
    val p = scratch.shapePaint
    scratch.rectF.set(1f, 1f, width - 1f, height - 1f)
    val radius = minOf(width, height) * 0.25f

    p.style = Paint.Style.FILL
    p.color = 0xFF141B24.toInt()
    p.alpha = ((210 * alpha) / 255).coerceIn(0, 255)
    canvas.drawRoundRect(scratch.rectF, radius, radius, p)

    p.style = Paint.Style.STROKE
    p.strokeWidth = 1.5f
    p.color = 0xFF334155.toInt()
    p.alpha = alpha
    canvas.drawRoundRect(scratch.rectF, radius, radius, p)

    val frac = if (resolved.rangedMax > resolved.rangedMin) {
      ((resolved.rangedValue - resolved.rangedMin) / (resolved.rangedMax - resolved.rangedMin)).coerceIn(0f, 1f)
    } else 0f
    if (resolved.activeType == "RANGED_VALUE" || resolved.activeType == "GOAL_PROGRESS") {
      p.strokeWidth = 3.5f
      p.strokeCap = Paint.Cap.ROUND
      p.color = resolved.weightedColors.firstOrNull() ?: 0xFF3B82F6.toInt()
      p.alpha = alpha
      scratch.rectF.set(4f, 4f, width - 4f, height - 4f)
      canvas.drawArc(scratch.rectF, -90f, 360f * frac, false, p)
    }

    val titleText = resolved.title.take(14)
    val valueText = resolved.text.ifEmpty { titleText }.take(16)
    val compact = width < 96f || height < 56f

    val tp = scratch.textPaint
    tp.clearShadowLayer()
    tp.textAlign = Paint.Align.CENTER

    if (!compact && titleText.isNotEmpty() && titleText != valueText) {
      tp.color = 0xFF94A3B8.toInt()
      tp.alpha = alpha
      tp.textSize = (height * 0.19f).coerceIn(9f, 15f)
      canvas.drawText(titleText, width * 0.5f, height * 0.36f, tp)

      tp.color = Color.WHITE
      tp.alpha = alpha
      tp.textSize = (height * 0.26f).coerceIn(11f, 20f)
      canvas.drawText(valueText, width * 0.5f, height * 0.70f, tp)
    } else {
      tp.color = Color.WHITE
      tp.alpha = alpha
      tp.textSize = (height * 0.28f).coerceIn(10f, 18f)
      val fm = tp.fontMetrics
      val baseY = height * 0.5f - (fm.ascent + fm.descent) * 0.5f
      canvas.drawText(valueText, width * 0.5f, baseY, tp)
    }
  }

  /** Draws a clean built-in vector icon when a complication image resolves to `[COMPLICATION.*_IMAGE]`. */
  fun drawComplicationIcon(
    canvas: android.graphics.Canvas,
    iconKind: WffComplicationIconKind?,
    width: Float,
    height: Float,
    tintColor: Int,
    alpha: Int,
    scratch: WffDrawHelpers.DrawScratch,
  ) {
    val kind = iconKind ?: WffComplicationIconKind.NONE
    if (kind == WffComplicationIconKind.NONE) return
    val p = scratch.shapePaint
    val cx = width * 0.5f
    val cy = height * 0.5f
    val r = minOf(width, height) * 0.36f
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    p.strokeWidth = (r * 0.22f).coerceAtLeast(1.5f)
    p.color = tintColor
    p.alpha = alpha

    when (kind) {
      WffComplicationIconKind.BATTERY -> {
        scratch.rectF.set(cx - r * 0.8f, cy - r * 0.5f, cx + r * 0.65f, cy + r * 0.5f)
        canvas.drawRoundRect(scratch.rectF, 2f, 2f, p)
        p.style = Paint.Style.FILL
        canvas.drawRect(cx + r * 0.7f, cy - r * 0.22f, cx + r * 0.9f, cy + r * 0.22f, p)
        canvas.drawRect(cx - r * 0.55f, cy - r * 0.25f, cx + r * 0.2f, cy + r * 0.25f, p)
      }
      WffComplicationIconKind.TIMER, WffComplicationIconKind.STOPWATCH -> {
        canvas.drawCircle(cx, cy + r * 0.08f, r * 0.75f, p)
        canvas.drawLine(cx - r * 0.3f, cy - r * 0.85f, cx + r * 0.3f, cy - r * 0.85f, p)
        canvas.drawLine(cx, cy + r * 0.08f, cx, cy - r * 0.32f, p)
        canvas.drawLine(cx, cy + r * 0.08f, cx + r * 0.35f, cy + r * 0.22f, p)
      }
      WffComplicationIconKind.CALENDAR, WffComplicationIconKind.DATE -> {
        scratch.rectF.set(cx - r * 0.75f, cy - r * 0.65f, cx + r * 0.75f, cy + r * 0.75f)
        canvas.drawRoundRect(scratch.rectF, 3f, 3f, p)
        canvas.drawLine(cx - r * 0.75f, cy - r * 0.2f, cx + r * 0.75f, cy - r * 0.2f, p)
      }
      WffComplicationIconKind.STEPS -> {
        p.style = Paint.Style.FILL
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.24f, p)
        canvas.drawCircle(cx + r * 0.3f, cy + r * 0.1f, r * 0.24f, p)
        canvas.drawCircle(cx - r * 0.2f, cy + r * 0.45f, r * 0.16f, p)
      }
      WffComplicationIconKind.NONE -> Unit
    }
  }
}

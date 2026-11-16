package com.example.faces.wff

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reusable drawing buffers, font loaders, bitmap downsamplers, and bitmap-font / text-effect
 * helpers shared by [WffRenderer] across Canvas frames without per-frame allocations.
 */
internal object WffDrawHelpers {

  /** Reusable scratch buffers and expression cache slots kept per face rendering session. */
  class DrawScratch {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeJoin = Paint.Join.ROUND
    }
    val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val textBuilder = StringBuilder(64)
    val rectF = RectF()
    val destRectF = RectF()
    val path = Path()

    private val exprSlots = IdentityHashMap<CompiledWffExpr, EvalCacheSlot>()
    private val filterCache = HashMap<Int, PorterDuffColorFilter>()
    private val xfermodeCache = HashMap<WffBlendMode, PorterDuffXfermode>()
    private val typefaceCache = HashMap<String, Typeface>()

    /** Evaluates [expr] using a per-expression [EvalCacheSlot] so unchanged inputs do not re-run. */
    fun evalCached(expr: CompiledWffExpr, ctx: WffEvalContext): WffValue {
      val slot = exprSlots.getOrPut(expr) { EvalCacheSlot() }
      return WffExpression.evaluateCached(slot, expr, ctx)
    }

    fun colorFilterFor(color: Int): PorterDuffColorFilter =
      filterCache.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    fun xfermodeFor(mode: WffBlendMode): PorterDuffXfermode =
      xfermodeCache.getOrPut(mode) { PorterDuffXfermode(mode.porterDuff) }

    fun resolveTypeface(
      family: String?,
      weight: String?,
      slant: String?,
      customFonts: Map<String, Typeface>,
    ): Typeface {
      val key = "${family.orEmpty()}|${weight.orEmpty()}|${slant.orEmpty()}|${customFonts.size}"
      return typefaceCache.getOrPut(key) {
        val customBase = findCustomTypeface(family, customFonts)
        val base = customBase ?: when {
          family.isNullOrBlank() -> Typeface.DEFAULT
          family.contains("mono", ignoreCase = true) -> Typeface.MONOSPACE
          family.contains("serif", ignoreCase = true) && !family.contains("sans", ignoreCase = true) -> Typeface.SERIF
          else -> Typeface.SANS_SERIF
        }
        val bold = weight?.contains("BOLD", ignoreCase = true) == true
        val italic = slant?.contains("ITALIC", ignoreCase = true) == true
        val style = when {
          bold && italic -> Typeface.BOLD_ITALIC
          bold -> Typeface.BOLD
          italic -> Typeface.ITALIC
          else -> Typeface.NORMAL
        }
        if (style == Typeface.NORMAL && customBase != null) customBase else Typeface.create(base, style)
      }
    }

    private fun findCustomTypeface(family: String?, customFonts: Map<String, Typeface>): Typeface? {
      if (family.isNullOrBlank() || customFonts.isEmpty()) return null
      customFonts[family]?.let { return it }
      val clean = family.trim().removePrefix("@font/").substringAfterLast('/').substringBeforeLast('.')
      customFonts[clean]?.let { return it }
      return customFonts.entries.firstOrNull { (k, _) ->
        k.removePrefix("@font/").substringAfterLast('/').substringBeforeLast('.').equals(clean, ignoreCase = true)
      }?.value
    }
  }

  /** Loads extracted `.ttf`/`.otf` fonts from [fontsDir] once when preparing a face stage. */
  fun loadCustomFonts(fontsDir: File?, fontFileMap: Map<String, String>): Map<String, Typeface> {
    if (fontsDir == null || !fontsDir.exists() || fontFileMap.isEmpty()) return emptyMap()
    val loaded = HashMap<String, Typeface>(fontFileMap.size)
    for ((logicalKey, fileName) in fontFileMap) {
      val file = File(fontsDir, fileName)
      if (!file.exists()) continue
      runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { tf ->
        loaded[logicalKey] = tf
      }
    }
    return loaded
  }

  /** Decodes only referenced bitmaps (including Condition branches, PartImages, and BitmapFont glyphs). */
  fun decodeNeededBitmaps(
    face: WffWatchFace,
    imagesDir: File,
    resourceFileMap: Map<String, String>,
    stageScale: Float,
  ): Map<String, Bitmap> {
    if (resourceFileMap.isEmpty()) return emptyMap()
    val maxDimsByRes = HashMap<String, Pair<Int, Int>>()

    fun recordUsage(resName: String?, w: Float, h: Float) {
      if (resName.isNullOrBlank()) return
      val targetW = (w * stageScale).roundToInt().coerceAtLeast(16)
      val targetH = (h * stageScale).roundToInt().coerceAtLeast(16)
      val prev = maxDimsByRes[resName]
      if (prev == null) {
        maxDimsByRes[resName] = targetW to targetH
      } else {
        maxDimsByRes[resName] = maxOf(prev.first, targetW) to maxOf(prev.second, targetH)
      }
    }

    fun scan(node: WffSceneNode) {
      when (node) {
        is WffSceneNode.Group -> node.children.forEach(::scan)
        is WffSceneNode.Condition -> {
          node.branches.forEach { b -> b.children.forEach(::scan) }
          node.defaultChildren.forEach(::scan)
        }
        is WffSceneNode.PartImage -> node.resources.forEach { res ->
          recordUsage(res, node.width, node.height)
        }
        is WffSceneNode.AnalogClock -> {
          node.hourHands.forEach { recordUsage(it.resource, it.width, it.height) }
          node.minuteHands.forEach { recordUsage(it.resource, it.width, it.height) }
          node.secondHands.forEach { recordUsage(it.resource, it.width, it.height) }
        }
        else -> Unit
      }
    }

    face.nodes.forEach(::scan)
    for (font in face.bitmapFonts.values) {
      for (glyph in font.glyphs.values) {
        recordUsage(glyph.resource, glyph.width, glyph.height)
      }
    }

    val out = HashMap<String, Bitmap>(maxDimsByRes.size)
    for ((resKey, dims) in maxDimsByRes) {
      val fileName = resourceFileMap[resKey] ?: continue
      val file = File(imagesDir, fileName)
      if (!file.exists()) continue
      decodeBitmapFileForSize(file, dims.first, dims.second)?.let { out[resKey] = it }
    }
    return out
  }

  /** Decodes [file] at drawn bounds without exceeding source or target dimensions. */
  fun decodeBitmapFileForSize(file: File, maxTargetW: Int, maxTargetH: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    val srcW = opts.outWidth
    val srcH = opts.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    var sampleSize = 1
    while ((srcW / (sampleSize * 2)) >= maxTargetW && (srcH / (sampleSize * 2)) >= maxTargetH) {
      sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
    val finalW = min(decoded.width, maxTargetW).coerceAtLeast(1)
    val finalH = min(decoded.height, maxTargetH).coerceAtLeast(1)
    if (decoded.width > finalW || decoded.height > finalH) {
      val scale = min(finalW.toFloat() / decoded.width, finalH.toFloat() / decoded.height)
      val scaledW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
      val scaledH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
      if (scaledW < decoded.width || scaledH < decoded.height) {
        return Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true)
      }
    }
    return decoded
  }

  /** Draws text using a BitmapFont's Character/Word glyph images, scaled to match [fontSize]. */
  fun drawBitmapFontText(
    canvas: android.graphics.Canvas,
    text: CharSequence,
    font: WffBitmapFont,
    bitmaps: Map<String, Bitmap>,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    align: WffTextAlign,
    fontSize: Float,
    alpha: Int,
    scratch: DrawScratch,
  ) {
    val str = text.toString()
    val glyphs = ArrayList<WffBitmapGlyph>(str.length)
    val wordKeys = font.glyphs.keys.filter { it.length > 1 }.sortedByDescending { it.length }

    var i = 0
    while (i < str.length) {
      var matchedWord: String? = null
      for (wk in wordKeys) {
        if (str.startsWith(wk, i)) {
          matchedWord = wk
          break
        }
      }
      if (matchedWord != null) {
        font.glyphs[matchedWord]?.let { glyphs += it }
        i += matchedWord.length
        continue
      }
      val chKey = str[i].toString()
      val chGlyph = font.glyphs[chKey] ?: font.glyphs[chKey.uppercase(Locale.US)]
      if (chGlyph != null) {
        glyphs += chGlyph
      } else {
        glyphs += WffBitmapGlyph(" ", "", fontSize * 0.35f, fontSize)
      }
      i++
    }
    if (glyphs.isEmpty()) return

    val maxGlyphH = glyphs.maxOf { it.height.coerceAtLeast(1f) }
    val scale = if (fontSize > 0f) (fontSize / maxGlyphH) else 1f
    val totalW = glyphs.sumOf { (it.width * scale).toDouble() }.toFloat()
    val totalH = maxGlyphH * scale

    var curX = when (align) {
      WffTextAlign.START -> x
      WffTextAlign.CENTER -> x + (width - totalW) * 0.5f
      WffTextAlign.END -> x + (width - totalW)
    }
    val topY = y + (height - totalH) * 0.5f
    scratch.bitmapPaint.alpha = alpha
    scratch.bitmapPaint.colorFilter = null

    for (g in glyphs) {
      val gw = g.width * scale
      val gh = g.height * scale
      val bmp = bitmaps[g.resource]
      if (bmp != null) {
        val gTop = topY + (totalH - gh) * 0.5f
        scratch.destRectF.set(curX, gTop, curX + gw, gTop + gh)
        canvas.drawBitmap(bmp, null, scratch.destRectF, scratch.bitmapPaint)
      }
      curX += gw
    }
  }

  /** Renders standard system or custom TTF text with optional Outline stroke and Shadow layer. */
  fun drawStyledText(
    canvas: android.graphics.Canvas,
    text: CharSequence,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    align: WffTextAlign,
    fontSize: Float,
    fontFamily: String?,
    weight: String?,
    slant: String?,
    color: Int,
    alpha: Int,
    shadow: WffTextShadow?,
    outline: WffTextOutline?,
    customFonts: Map<String, Typeface>,
    scratch: DrawScratch,
  ) {
    val typeface = scratch.resolveTypeface(fontFamily, weight, slant, customFonts)
    val paintAlign = when (align) {
      WffTextAlign.START -> Paint.Align.LEFT
      WffTextAlign.CENTER -> Paint.Align.CENTER
      WffTextAlign.END -> Paint.Align.RIGHT
    }
    val drawX = when (align) {
      WffTextAlign.START -> x
      WffTextAlign.CENTER -> x + width * 0.5f
      WffTextAlign.END -> x + width
    }
    val scaledSize = fontSize.coerceAtLeast(6f)
    val paint = scratch.textPaint
    paint.textSize = scaledSize
    paint.typeface = typeface
    paint.textAlign = paintAlign
    val fm = paint.fontMetrics
    val baselineY = y + height * 0.5f - (fm.ascent + fm.descent) * 0.5f

    if (shadow != null) {
      val shadowAlpha = ((android.graphics.Color.alpha(shadow.color) * alpha) / 255).coerceIn(0, 255)
      val sc = (shadow.color and 0x00FFFFFF) or (shadowAlpha shl 24)
      paint.setShadowLayer(shadow.radius, shadow.offsetX, shadow.offsetY, sc)
    } else {
      paint.clearShadowLayer()
    }

    if (outline != null && outline.width > 0f) {
      val op = scratch.outlinePaint
      op.color = outline.color
      op.alpha = ((android.graphics.Color.alpha(outline.color) * alpha) / 255).coerceIn(0, 255)
      op.textSize = scaledSize
      op.typeface = typeface
      op.textAlign = paintAlign
      op.strokeWidth = outline.width * 2f
      canvas.drawText(text, 0, text.length, drawX, baselineY, op)
    }

    paint.color = color
    paint.alpha = ((android.graphics.Color.alpha(color) * alpha) / 255).coerceIn(0, 255)
    canvas.drawText(text, 0, text.length, drawX, baselineY, paint)
    if (shadow != null) {
      paint.clearShadowLayer()
    }
  }

  /** Renders a PartDraw vector shape with optional expression-driven colors and Arc transforms. */
  fun drawShape(
    canvas: android.graphics.Canvas,
    shape: WffDrawShape,
    evalCtx: WffEvalContext,
    animController: WffAnimationController,
    nowElapsedMs: Long,
    isAmbient: Boolean,
    scratch: DrawScratch,
    effectiveAlpha: Int,
  ) {
    val paint = scratch.shapePaint
    paint.strokeCap = shape.cap

    val resolvedFill = shape.fillColorExpr?.let {
      scratch.evalCached(it, evalCtx).asColorInt(shape.fillColor ?: 0)
    } ?: shape.fillColor

    val resolvedStroke = shape.strokeColorExpr?.let {
      scratch.evalCached(it, evalCtx).asColorInt(shape.strokeColor ?: 0)
    } ?: shape.strokeColor

    fun applyPaint(color: Int, style: Paint.Style) {
      paint.style = style
      paint.color = color
      paint.alpha = ((android.graphics.Color.alpha(color) * effectiveAlpha) / 255).coerceIn(0, 255)
      if (style == Paint.Style.STROKE) {
        paint.strokeWidth = shape.strokeWidth
      }
    }

    when (shape) {
      is WffDrawShape.Ellipse -> {
        scratch.rectF.set(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        resolvedFill?.let { applyPaint(it, Paint.Style.FILL); canvas.drawOval(scratch.rectF, paint) }
        resolvedStroke?.let { applyPaint(it, Paint.Style.STROKE); canvas.drawOval(scratch.rectF, paint) }
      }
      is WffDrawShape.Rectangle -> {
        scratch.rectF.set(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        resolvedFill?.let { applyPaint(it, Paint.Style.FILL); canvas.drawRect(scratch.rectF, paint) }
        resolvedStroke?.let { applyPaint(it, Paint.Style.STROKE); canvas.drawRect(scratch.rectF, paint) }
      }
      is WffDrawShape.RoundRectangle -> {
        scratch.rectF.set(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        resolvedFill?.let {
          applyPaint(it, Paint.Style.FILL)
          canvas.drawRoundRect(scratch.rectF, shape.cornerRadiusX, shape.cornerRadiusY, paint)
        }
        resolvedStroke?.let {
          applyPaint(it, Paint.Style.STROKE)
          canvas.drawRoundRect(scratch.rectF, shape.cornerRadiusX, shape.cornerRadiusY, paint)
        }
      }
      is WffDrawShape.Arc -> {
        var cx = shape.centerX
        var cy = shape.centerY
        var w = shape.width
        var h = shape.height
        var start = shape.startAngle
        var sweep = shape.sweepAngle
        for (tr in shape.transforms) {
          val raw = scratch.evalCached(tr.valueExpr, evalCtx).asFloat()
          val v = animController.sample(tr, raw, nowElapsedMs, isAmbient)
          when (tr.target) {
            "centerX" -> cx = v
            "centerY" -> cy = v
            "width" -> w = v
            "height" -> h = v
            "startAngle" -> start = v
            "sweepAngle" -> sweep = v
          }
        }
        val left = cx - w * 0.5f
        val top = cy - h * 0.5f
        scratch.rectF.set(left, top, left + w, top + h)
        resolvedFill?.let {
          applyPaint(it, Paint.Style.FILL)
          canvas.drawArc(scratch.rectF, start - 90f, sweep, true, paint)
        }
        resolvedStroke?.let {
          applyPaint(it, Paint.Style.STROKE)
          canvas.drawArc(scratch.rectF, start - 90f, sweep, false, paint)
        }
      }
      is WffDrawShape.Line -> {
        val lineColor = resolvedStroke ?: resolvedFill ?: return
        applyPaint(lineColor, Paint.Style.STROKE)
        canvas.drawLine(shape.startX, shape.startY, shape.endX, shape.endY, paint)
      }
    }
  }
}

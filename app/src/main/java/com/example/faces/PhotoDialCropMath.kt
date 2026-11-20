package com.example.faces

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Integer rectangle in source bitmap pixel coordinates covering a circular dial crop. */
internal data class CropRect(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int get() = (right - left).coerceAtLeast(0)
  val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * Pure geometry helpers mapping between the circular dial preview viewport and source bitmap pixels,
 * supporting drag pan, pinch zoom, arbitrary rotation, and strict edge limits so the circle never
 * exposes out-of-bounds areas.
 */
internal object PhotoDialCropMath {

  const val MIN_ZOOM = 1.0f
  const val MAX_ZOOM = 6.0f

  /** Clamps zoom factor into [[MIN_ZOOM], [MAX_ZOOM]], rejecting NaN/Infinity cleanly. */
  fun clampZoom(zoom: Float): Float =
    if (!zoom.isFinite()) MIN_ZOOM else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

  /** Wraps an arbitrary rotation angle into [-180f, 180f]. */
  fun normalizeAngleDeg(angleDeg: Float): Float {
    if (!angleDeg.isFinite()) return 0f
    var a = angleDeg % 360f
    if (a > 180f) a -= 360f
    if (a < -180f) a += 360f
    return a
  }

  /**
   * Minimum uniform scale factor such that the smaller dimension of a [srcWidth] x [srcHeight]
   * photo fills the [targetDiameterPx] circle completely at zoom = 1.0.
   */
  fun fitBaseScale(srcWidth: Int, srcHeight: Int, targetDiameterPx: Float): Float {
    if (srcWidth <= 0 || srcHeight <= 0 || !targetDiameterPx.isFinite() || targetDiameterPx <= 0f) {
      return 1f
    }
    return max(targetDiameterPx / srcWidth.toFloat(), targetDiameterPx / srcHeight.toFloat())
  }

  /** Effective bitmap-to-screen scale factor at the requested [zoom]. */
  fun totalScale(srcWidth: Int, srcHeight: Int, targetDiameterPx: Float, zoom: Float): Float =
    fitBaseScale(srcWidth, srcHeight, targetDiameterPx) * clampZoom(zoom)

  /**
   * Maximum allowed shift of the circle center from the image center along the bitmap's local X/Y
   * axes (in source pixels) while keeping the entire circle radius inside the photo rectangle.
   */
  fun maxSourcePanOffsets(
    srcWidth: Int,
    srcHeight: Int,
    targetDiameterPx: Float,
    zoom: Float,
  ): Pair<Float, Float> {
    if (srcWidth <= 0 || srcHeight <= 0 || targetDiameterPx <= 0f) return 0f to 0f
    val scale = totalScale(srcWidth, srcHeight, targetDiameterPx, zoom)
    val radiusSrc = (targetDiameterPx * 0.5f) / scale
    val maxX = (srcWidth * 0.5f - radiusSrc).coerceAtLeast(0f)
    val maxY = (srcHeight * 0.5f - radiusSrc).coerceAtLeast(0f)
    return maxX to maxY
  }

  /**
   * Clamps a screen-space pan offset `(panX, panY)` so that after inverse-rotating by [rotationDeg],
   * the cropped circle stays strictly inside `[0..srcWidth, 0..srcHeight]`.
   */
  fun clampScreenPan(
    panX: Float,
    panY: Float,
    srcWidth: Int,
    srcHeight: Int,
    targetDiameterPx: Float,
    zoom: Float,
    rotationDeg: Float,
  ): Pair<Float, Float> {
    if (!panX.isFinite() || !panY.isFinite() || srcWidth <= 0 || srcHeight <= 0 || targetDiameterPx <= 0f) {
      return 0f to 0f
    }
    val scale = totalScale(srcWidth, srcHeight, targetDiameterPx, zoom)
    val (maxSrcX, maxSrcY) = maxSourcePanOffsets(srcWidth, srcHeight, targetDiameterPx, zoom)

    val rad = normalizeAngleDeg(rotationDeg) * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()

    // In screen space, image offset from circle center is (+panX, +panY), so circle center relative
    // to rotated image center is (-panX, -panY). Rotate back into unrotated bitmap local space:
    val localOffsetXSrc = (-panX * cosA + -panY * sinA) / scale
    val localOffsetYSrc = (panX * sinA - panY * cosA) / scale

    val clampedSrcX = localOffsetXSrc.coerceIn(-maxSrcX, maxSrcX)
    val clampedSrcY = localOffsetYSrc.coerceIn(-maxSrcY, maxSrcY)

    // Transform clamped source offset back to screen pan.
    val screenCircleRelX = clampedSrcX * cosA - clampedSrcY * sinA
    val screenCircleRelY = clampedSrcX * sinA + clampedSrcY * cosA
    val clampedPanX = (-screenCircleRelX * scale)
    val clampedPanY = (-screenCircleRelY * scale)
    return (if (abs(clampedPanX) < 1e-4f) 0f else clampedPanX) to
      (if (abs(clampedPanY) < 1e-4f) 0f else clampedPanY)
  }

  /**
   * Maps the screen circular crop region into the tightest axis-aligned [CropRect] in source bitmap
   * coordinates, accounting for [zoom], screen [panX]/[panY], and [rotationDeg], clamped to the
   * source image bounds `[0, srcWidth] x [0, srcHeight]`.
   */
  fun screenCropToSourceRect(
    srcWidth: Int,
    srcHeight: Int,
    targetDiameterPx: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    rotationDeg: Float,
  ): CropRect {
    if (srcWidth <= 0 || srcHeight <= 0 || targetDiameterPx <= 0f) {
      return CropRect(0, 0, 0, 0)
    }
    val scale = totalScale(srcWidth, srcHeight, targetDiameterPx, zoom)
    val (clampedPanX, clampedPanY) = clampScreenPan(
      panX = panX,
      panY = panY,
      srcWidth = srcWidth,
      srcHeight = srcHeight,
      targetDiameterPx = targetDiameterPx,
      zoom = zoom,
      rotationDeg = rotationDeg,
    )

    val rad = normalizeAngleDeg(rotationDeg) * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()

    // Circle center in unrotated source coordinates (relative to top-left 0,0).
    val localShiftX = (-clampedPanX * cosA - clampedPanY * sinA) / scale
    val localShiftY = (clampedPanX * sinA - clampedPanY * cosA) / scale
    val srcCenterX = srcWidth * 0.5f + localShiftX
    val srcCenterY = srcHeight * 0.5f + localShiftY

    // Circle has radius R_src regardless of rotation angle.
    val radiusSrc = (targetDiameterPx * 0.5f) / scale

    val left = floor((srcCenterX - radiusSrc).toDouble()).toInt().coerceIn(0, srcWidth - 1)
    val top = floor((srcCenterY - radiusSrc).toDouble()).toInt().coerceIn(0, srcHeight - 1)
    val right = ceil((srcCenterX + radiusSrc).toDouble()).toInt().coerceIn(left + 1, srcWidth)
    val bottom = ceil((srcCenterY + radiusSrc).toDouble()).toInt().coerceIn(top + 1, srcHeight)

    return CropRect(
      left = min(left, right - 1),
      top = min(top, bottom - 1),
      right = right,
      bottom = bottom,
    )
  }
}

package com.example

import com.example.faces.PhotoDialCropMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure JVM unit tests for circular dial crop geometry, zoom, rotation, and edge clamping. */
class PhotoDialCropMathTest {

  @Test
  fun `fitBaseScale covers circular diameter using shortest image dimension`() {
    // 1600x800 landscape into 400px circle -> base scale driven by height 800 -> 400/800 = 0.5f
    assertEquals(0.5f, PhotoDialCropMath.fitBaseScale(1600, 800, 400f), 1e-4f)
    // 800x1600 portrait into 400px circle -> base scale driven by width 800 -> 0.5f
    assertEquals(0.5f, PhotoDialCropMath.fitBaseScale(800, 1600, 400f), 1e-4f)
    // Square 1000x1000 into 500px circle -> 0.5f
    assertEquals(0.5f, PhotoDialCropMath.fitBaseScale(1000, 1000, 500f), 1e-4f)
  }

  @Test
  fun `clampZoom and normalizeAngleDeg handle extremes and non-finite inputs`() {
    assertEquals(1.0f, PhotoDialCropMath.clampZoom(0.2f), 1e-4f)
    assertEquals(6.0f, PhotoDialCropMath.clampZoom(12.5f), 1e-4f)
    assertEquals(2.5f, PhotoDialCropMath.clampZoom(2.5f), 1e-4f)
    assertEquals(1.0f, PhotoDialCropMath.clampZoom(Float.NaN), 1e-4f)

    assertEquals(-90f, PhotoDialCropMath.normalizeAngleDeg(270f), 1e-4f)
    assertEquals(90f, PhotoDialCropMath.normalizeAngleDeg(-270f), 1e-4f)
    assertEquals(0f, PhotoDialCropMath.normalizeAngleDeg(Float.POSITIVE_INFINITY), 1e-4f)
  }

  @Test
  fun `clampScreenPan prevents dragging past photo edges at zoom 1x`() {
    // 2000x1000 photo in 500px circle at zoom 1x: scale = 0.5, circle radius in src = 500px.
    // Max X pan in source = 1000 - 500 = 500px -> screen = 250px.
    // Max Y pan in source = 500 - 500 = 0px -> screen = 0px!
    val (clampedX, clampedY) = PhotoDialCropMath.clampScreenPan(
      panX = 900f,
      panY = 400f,
      srcWidth = 2000,
      srcHeight = 1000,
      targetDiameterPx = 500f,
      zoom = 1.0f,
      rotationDeg = 0f,
    )
    assertEquals(250f, clampedX, 1e-3f)
    assertEquals(0f, clampedY, 1e-3f)
  }

  @Test
  fun `clampScreenPan respects 90 degree rotation axis swap`() {
    // Same 2000x1000 photo rotated 90 degrees -> now vertical axis has slack, horizontal has 0 slack!
    val (clampedX, clampedY) = PhotoDialCropMath.clampScreenPan(
      panX = 400f,
      panY = -900f,
      srcWidth = 2000,
      srcHeight = 1000,
      targetDiameterPx = 500f,
      zoom = 1.0f,
      rotationDeg = 90f,
    )
    assertTrue("Expected horizontal pan ~0 after 90 deg rotation, got $clampedX", abs(clampedX) < 1e-2f)
    assertEquals(-250f, clampedY, 1e-2f)
  }

  @Test
  fun `screenCropToSourceRect maps centered and zoomed regions strictly within bitmap bounds`() {
    // Centered square 1000x1000 at zoom 1x -> full [0, 0, 1000, 1000]
    val full = PhotoDialCropMath.screenCropToSourceRect(
      srcWidth = 1000,
      srcHeight = 1000,
      targetDiameterPx = 500f,
      zoom = 1f,
      panX = 0f,
      panY = 0f,
      rotationDeg = 0f,
    )
    assertEquals(0, full.left)
    assertEquals(0, full.top)
    assertEquals(1000, full.right)
    assertEquals(1000, full.bottom)

    // At zoom 2x centered -> radius halves to 250px -> [250, 250, 750, 750]
    val zoomed = PhotoDialCropMath.screenCropToSourceRect(
      srcWidth = 1000,
      srcHeight = 1000,
      targetDiameterPx = 500f,
      zoom = 2f,
      panX = 0f,
      panY = 0f,
      rotationDeg = 0f,
    )
    assertEquals(250, zoomed.left)
    assertEquals(250, zoomed.top)
    assertEquals(750, zoomed.right)
    assertEquals(750, zoomed.bottom)

    // Extreme pan + diagonal rotation stays safely clamped inside [0..2000, 0..1000]
    val extreme = PhotoDialCropMath.screenCropToSourceRect(
      srcWidth = 2000,
      srcHeight = 1000,
      targetDiameterPx = 400f,
      zoom = 2f,
      panX = -1500f,
      panY = 1500f,
      rotationDeg = -45f,
    )
    assertTrue(extreme.left >= 0)
    assertTrue(extreme.top >= 0)
    assertTrue(extreme.right <= 2000)
    assertTrue(extreme.bottom <= 1000)
    assertTrue(extreme.width > 0 && extreme.height > 0)
  }
}

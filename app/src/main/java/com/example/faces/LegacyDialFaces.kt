package com.example.faces

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.example.R

/**
 * Legacy hand-coded DialConfig definitions retained for reference after porting
 * Motorsport M and Speedmaster Racing to JSON face definitions under assets/faces/.
 */
internal object LegacyDialFaces {

  private val motorsportSilver = Color(0xFFE2E8F0)
  private val motorsportRed = Color(0xFFE00424)
  private val motorsportNavy = Color(0xFF0F172A)

  private val speedmasterGold = Color(0xFFD4AF37)
  private val speedmasterCream = Color(0xFFFFFBEB)

  /** Unused legacy fallback builder for Motorsport M. */
  fun motorsportM(): WatchFace = ImageDialFace(
    id = "motorsport_m",
    displayName = "Motorsport M",
    dialResId = R.drawable.face_motorsport_m,
    config = DialConfig(
      hourLengthRatio = 0.50f,
      hourColor = motorsportSilver,
      hourWidthDp = 6.5f,
      hourCap = StrokeCap.Butt,
      hourStripeColor = Color.White,
      hourStripeWidthDp = 2.8f,
      minuteLengthRatio = 0.74f,
      minuteColor = motorsportSilver,
      minuteWidthDp = 4.8f,
      minuteCap = StrokeCap.Butt,
      minuteStripeColor = Color.White,
      minuteStripeWidthDp = 2.2f,
      secondLengthRatio = 0.86f,
      secondColor = motorsportRed,
      secondWidthDp = 1.8f,
      secondTailDp = 24f,
      counterweight = CounterweightSpec(
        radiusDp = 4.5f,
        ringColor = motorsportNavy,
        centerColor = motorsportRed,
        tailOffsetDp = 17f,
      ),
      centerCapOuterDp = 6.5f,
      centerCapOuterColor = motorsportNavy,
      centerCapInnerDp = 3.5f,
      centerCapInnerColor = motorsportSilver,
    ),
  )

  /** Unused legacy fallback builder for Speedmaster Racing. */
  fun speedmasterRacing(): WatchFace = ImageDialFace(
    id = "speedmaster_racing",
    displayName = "Speedmaster Racing",
    dialResId = R.drawable.face_speedmaster_racing,
    config = DialConfig(
      hourLengthRatio = 0.48f,
      hourColor = speedmasterGold,
      hourWidthDp = 4.8f,
      hourCap = StrokeCap.Round,
      hourStripeColor = speedmasterCream,
      hourStripeWidthDp = 2.2f,
      minuteLengthRatio = 0.72f,
      minuteColor = speedmasterGold,
      minuteWidthDp = 3.8f,
      minuteCap = StrokeCap.Round,
      minuteStripeColor = speedmasterCream,
      minuteStripeWidthDp = 1.8f,
      secondLengthRatio = 0.86f,
      secondColor = speedmasterGold,
      secondWidthDp = 1.6f,
      secondTailDp = 20f,
      counterweight = CounterweightSpec(
        radiusDp = 3f,
        ringColor = speedmasterGold,
        centerColor = Color.Black,
        positionAlongPointerRatio = 0.75f,
      ),
      centerCapOuterDp = 6f,
      centerCapOuterColor = speedmasterGold,
      centerCapInnerDp = 2.5f,
      centerCapInnerColor = Color.Black,
      leftSubDial = SubDialSpec(
        offsetXRatio = -0.38f,
        lengthRatio = 0.22f * 0.75f,
        widthDp = 2f,
        color = speedmasterGold,
        isSecondsSubDial = true,
      ),
      rightSubDial = SubDialSpec(
        offsetXRatio = 0.38f,
        lengthRatio = 0.22f * 0.75f,
        widthDp = 2f,
        color = speedmasterGold,
        isSecondsSubDial = false,
      ),
      dateOffsetYRatio = 0.52f,
      dateTextSizeRatio = 0.065f,
    ),
  )
}

package com.example.faces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.AuraThemeColor
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack
import com.example.ui.theme.SpaceGroteskFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Big iOS Stand By-inspired digital face with bold tabular numerals and crisp date header.
 */
internal class StandByDigitalFace : WatchFace {
  override val id: String = "standby_digital"
  override val displayName: String = "Stand By Digital"

  @Composable
  override fun Render(
    timeProvider: () -> ClockTimeSnapshot,
    accent: AuraThemeColor,
    smoothSeconds: Boolean,
    modifier: Modifier,
  ) {
    val t = timeProvider()
    val displayHour = when {
      t.hour12 == 0 -> 12
      else -> t.hour12
    }
    val timeText = "%d:%02d".format(displayHour, t.minute)

    BoxWithConstraints(
      modifier = modifier
        .fillMaxSize()
        .background(OledBlack),
      contentAlignment = Alignment.Center,
    ) {
      val numeralSize = (minOf(maxWidth, maxHeight).value * 0.34f).coerceIn(72f, 148f).sp

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp),
      ) {
        Text(
          text = t.monthDayLabel,
          fontFamily = SpaceGroteskFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          letterSpacing = 2.sp,
          color = accent.primary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = timeText,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = numeralSize,
          lineHeight = numeralSize,
          color = Color.White,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Surface(
            color = accent.container,
            shape = RoundedCornerShape(50),
          ) {
            Text(
              text = "%02d s".format(t.second),
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 16.sp,
              color = accent.primary,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = if (t.isAm) "AM" else "PM",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color(0xFF94A3B8),
          )
        }
      }
    }
  }
}

/**
 * Clean, minimal code-drawn analog face with themed accent seconds hand and crisp indices.
 */
internal class MinimalAnalogFace : WatchFace {
  override val id: String = "minimal_analog"
  override val displayName: String = "Minimal Analog"

  @Composable
  override fun Render(
    timeProvider: () -> ClockTimeSnapshot,
    accent: AuraThemeColor,
    smoothSeconds: Boolean,
    modifier: Modifier,
  ) {
    BoxWithConstraints(
      modifier = modifier
        .fillMaxSize()
        .background(OledBlack),
      contentAlignment = Alignment.Center,
    ) {
      val dialSize = minOf(maxWidth, maxHeight) * 0.90f
      Box(
        modifier = Modifier.size(dialSize),
        contentAlignment = Alignment.Center,
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val t = timeProvider()
          val r = size.minDimension / 2f
          val center = Offset(size.width / 2f, size.height / 2f)

          // Outer subtle ring
          drawCircle(
            color = Color(0xFF1E2430),
            radius = r * 0.98f,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
          )

          // 60 tick markers (prominent 12/3/6/9, crisp hour markers, subtle minute ticks)
          for (i in 0 until 60) {
            val angleRad = (i * 6f - 90f) * (PI / 180.0)
            val dir = Offset(cos(angleRad).toFloat(), sin(angleRad).toFloat())
            val isHour = i % 5 == 0
            val isMajor = i % 15 == 0
            val innerR = when {
              isMajor -> r * 0.80f
              isHour -> r * 0.85f
              else -> r * 0.91f
            }
            val outerR = r * 0.94f
            drawLine(
              color = when {
                isMajor -> accent.primary
                isHour -> Color(0xFFE2E8F0)
                else -> Color(0xFF334155)
              },
              start = center + dir * innerR,
              end = center + dir * outerR,
              strokeWidth = when {
                isMajor -> 3.5.dp.toPx()
                isHour -> 2.dp.toPx()
                else -> 1.dp.toPx()
              },
              cap = StrokeCap.Round,
            )
          }

          val secFloat = if (smoothSeconds) t.second + (t.millis / 1000f) else t.second.toFloat()
          val minFloat = t.minute + (secFloat / 60f)
          val hourFloat = (t.hour12 % 12) + (minFloat / 60f)

          val hourRad = (hourFloat * 30f - 90f) * (PI / 180.0)
          val minRad = (minFloat * 6f - 90f) * (PI / 180.0)
          val secRad = (secFloat * 6f - 90f) * (PI / 180.0)

          val hourDir = Offset(cos(hourRad).toFloat(), sin(hourRad).toFloat())
          val minDir = Offset(cos(minRad).toFloat(), sin(minRad).toFloat())
          val secDir = Offset(cos(secRad).toFloat(), sin(secRad).toFloat())

          // Hour hand
          drawLine(
            color = Color.White,
            start = center - hourDir * 10.dp.toPx(),
            end = center + hourDir * (r * 0.50f),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round,
          )

          // Minute hand
          drawLine(
            color = Color(0xFFE2E8F0),
            start = center - minDir * 12.dp.toPx(),
            end = center + minDir * (r * 0.75f),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
          )

          // Themed seconds hand
          drawLine(
            color = accent.primary,
            start = center - secDir * 20.dp.toPx(),
            end = center + secDir * (r * 0.86f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
          )

          drawCircle(color = accent.primary, radius = 5.5.dp.toPx(), center = center)
          drawCircle(color = OledBlack, radius = 2.dp.toPx(), center = center)
        }
      }
    }
  }
}

package com.example.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.JetBrainsMonoFamily

/**
 * Number-pad dialog for entering exact MM:SS durations (for example 6 s, 45 s, or 2:30)
 * or editing a quick-preset chip on long-press.
 */
@Composable
internal fun TimerKeypadDialog(
  title: String,
  initialSeconds: Int,
  onDismiss: () -> Unit,
  onConfirmSeconds: (Int) -> Unit,
) {
  var rawDigits by remember {
    val m = initialSeconds / 60
    val s = initialSeconds % 60
    val initial = if (m > 0) "$m${"%02d".format(s)}" else "$s"
    mutableStateOf(if (initial == "0") "" else initial)
  }

  val padded = rawDigits.padStart(4, '0')
  val mins = padded.substring(0, 2).toIntOrNull() ?: 0
  val secs = padded.substring(2, 4).toIntOrNull() ?: 0
  val previewText = "%02d:%02d".format(mins, secs)
  val totalSeconds = (mins * 60 + secs).coerceIn(0, 5999)

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = previewText,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 48.sp,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("keypad_preview"),
        )
        Spacer(modifier = Modifier.height(16.dp))

        val rows = listOf(
          listOf("1", "2", "3"),
          listOf("4", "5", "6"),
          listOf("7", "8", "9"),
        )
        rows.forEach { row ->
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 4.dp),
          ) {
            row.forEach { digit ->
              KeypadButton(
                label = digit,
                testTag = "keypad_digit_$digit",
                onClick = {
                  if (rawDigits.length < 4) {
                    val next = (rawDigits + digit).trimStart('0')
                    rawDigits = next
                  }
                },
              )
            }
          }
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.padding(vertical = 4.dp),
        ) {
          KeypadButton(
            label = "C",
            testTag = "keypad_clear",
            onClick = { rawDigits = "" },
          )
          KeypadButton(
            label = "0",
            testTag = "keypad_digit_0",
            onClick = {
              if (rawDigits.isNotEmpty() && rawDigits.length < 4) {
                rawDigits += "0"
              }
            },
          )
          Box(
            modifier = Modifier
              .size(64.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .clickable {
                if (rawDigits.isNotEmpty()) {
                  rawDigits = rawDigits.dropLast(1)
                }
              }
              .testTag("keypad_backspace"),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Backspace,
              contentDescription = "Backspace",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
              .weight(1f)
              .height(50.dp)
              .testTag("keypad_cancel"),
          ) {
            Text("Cancel")
          }
          Button(
            onClick = {
              if (totalSeconds > 0) {
                onConfirmSeconds(totalSeconds)
              }
            },
            enabled = totalSeconds > 0,
            modifier = Modifier
              .weight(1f)
              .height(50.dp)
              .testTag("keypad_confirm"),
          ) {
            Text("Set")
          }
        }
      }
    }
  }
}

@Composable
private fun KeypadButton(
  label: String,
  testTag: String,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(64.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick)
      .testTag(testTag),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      fontFamily = JetBrainsMonoFamily,
      fontWeight = FontWeight.Bold,
      fontSize = 22.sp,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

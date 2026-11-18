package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.StartupSafetyNet
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.OledBlack

/**
 * Last-resort recovery UI displayed instead of the normal app if AuraClock crashed during its
 * last two startups. Intentionally avoids reading shared preferences or watch faces.
 */
@Composable
fun StartupProblemScreen(
  errorText: String,
  onStartSafeMode: () -> Unit,
  onClearFacesAndSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var copiedNotice by remember { mutableStateOf(false) }

  Scaffold(
    containerColor = OledBlack,
    contentWindowInsets = WindowInsets.safeDrawing,
    modifier = modifier,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(OledBlack)
        .padding(innerPadding)
        .padding(20.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          imageVector = Icons.Default.WarningAmber,
          contentDescription = "Startup problem",
          tint = Color(0xFFF59E0B),
          modifier = Modifier.size(32.dp),
        )
        Text(
          text = "Startup problem",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          modifier = Modifier.testTag("startup_problem_title"),
        )
      }

      Text(
        text = "AuraClock crashed during its last two startups. You can copy or share the error details, start in safe mode without optional features, or clear imported faces and settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFCBD5E1),
      )

      Surface(
        color = Color(0xFF0E131A),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 120.dp, max = 260.dp)
          .border(1.dp, Color(0xFF283244), RoundedCornerShape(14.dp)),
      ) {
        Column(
          modifier = Modifier
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        ) {
          Text(
            text = errorText,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            color = Color(0xFFF8FAFC),
            modifier = Modifier.testTag("startup_problem_error_text"),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = {
            runCatching {
              val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              cm?.setPrimaryClip(ClipData.newPlainText("AuraClock Startup Error", errorText))
              copiedNotice = true
            }
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .testTag("startup_problem_copy_button"),
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(if (copiedNotice) "Copied" else "Copy")
        }

        OutlinedButton(
          onClick = {
            runCatching {
              context.startActivity(StartupSafetyNet.createShareIntent(context, errorText))
            }
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .testTag("startup_problem_share_button"),
        ) {
          Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Share")
        }
      }

      Button(
        onClick = onStartSafeMode,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .testTag("startup_problem_safe_mode_button"),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Start in safe mode", fontWeight = FontWeight.Bold)
      }

      OutlinedButton(
        onClick = onClearFacesAndSettings,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .testTag("startup_problem_clear_button"),
      ) {
        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Clear imported faces and settings", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

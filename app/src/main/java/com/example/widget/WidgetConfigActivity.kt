package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.ui.theme.AuraClockTheme
import com.example.ui.theme.OledBlack

/**
 * Configuration activity launched when the user adds an AuraClock widget to their home screen.
 * Lets the user choose analog/digital style and accent colour.
 */
class WidgetConfigActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
    )

    val appWidgetId = intent?.extras?.getInt(
      AppWidgetManager.EXTRA_APPWIDGET_ID,
      AppWidgetManager.INVALID_APPWIDGET_ID,
    ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
      finish()
      return
    }

    val prefs = CoreSingletons.prefs(this)

    setContent {
      var selectedStyle by remember { mutableStateOf(AuraClockWidgetProvider.STYLE_MOTORSPORT) }
      var selectedColor by remember { mutableStateOf(prefs.themeColor.value) }

      AuraClockTheme(accent = selectedColor) {
        Scaffold(
          containerColor = OledBlack,
          contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
          WidgetConfigContent(
            selectedStyle = selectedStyle,
            selectedColor = selectedColor,
            onSelectStyle = { selectedStyle = it },
            onSelectColor = { selectedColor = it },
            onConfirm = {
              prefs.saveWidgetConfig(appWidgetId, selectedStyle, selectedColor.id)
              val manager = AppWidgetManager.getInstance(this)
              AuraClockWidgetProvider.updateAppWidget(this, manager, appWidgetId)
              val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
              setResult(RESULT_OK, resultIntent)
              finish()
            },
            modifier = Modifier
              .padding(innerPadding)
              .windowInsetsPadding(WindowInsets.safeDrawing),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigContent(
  selectedStyle: String,
  selectedColor: AuraThemeColor,
  onSelectStyle: (String) -> Unit,
  onSelectColor: (AuraThemeColor) -> Unit,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val styles = listOf(
    AuraClockWidgetProvider.STYLE_MOTORSPORT to "Motorsport M (Analog)",
    AuraClockWidgetProvider.STYLE_SPEEDMASTER to "Speedmaster Racing (Analog)",
    AuraClockWidgetProvider.STYLE_MINIMAL to "Minimal Analog",
    AuraClockWidgetProvider.STYLE_DIGITAL to "Digital Clock",
  )

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(OledBlack)
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      text = "Configure Clock Widget",
      style = MaterialTheme.typography.headlineMedium,
      color = Color.White,
    )
    Text(
      text = "Transparent overlay with no card or background.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("Widget Style", style = MaterialTheme.typography.titleLarge, color = Color.White)
        styles.forEach { (id, label) ->
          val selected = id == selectedStyle
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
              )
              .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
              )
              .clickable { onSelectStyle(id) }
              .padding(horizontal = 14.dp, vertical = 14.dp)
              .testTag("widget_style_$id"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Medium)
            if (selected) {
              Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }
    }

    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Accent Colour", style = MaterialTheme.typography.titleLarge, color = Color.White)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          AuraThemeColor.entries.forEach { color ->
            val selected = color == selectedColor
            Row(
              modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) color.container else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                  width = if (selected) 1.5.dp else 1.dp,
                  color = if (selected) color.primary else MaterialTheme.colorScheme.outline,
                  shape = RoundedCornerShape(14.dp),
                )
                .clickable { onSelectColor(color) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("widget_color_${color.id}"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .size(16.dp)
                  .clip(CircleShape)
                  .background(color.primary),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(color.label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    }

    Button(
      onClick = onConfirm,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .testTag("widget_confirm_button"),
    ) {
      Text("Add Widget", fontWeight = FontWeight.Bold)
    }
  }
}

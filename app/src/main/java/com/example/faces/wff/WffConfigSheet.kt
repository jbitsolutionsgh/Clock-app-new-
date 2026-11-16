package com.example.faces.wff

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CoreSingletons

/**
 * Material 3 ModalBottomSheet displaying ColorConfiguration swatches, ListConfiguration choices,
 * and BooleanConfiguration switches for an imported WFF watch face, saving live per face.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WffConfigSheet(
  entry: WffStoredFace,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }
  val allConfigs by prefs.wffFaceConfigs.collectAsStateWithLifecycle()
  val saved = allConfigs[entry.id].orEmpty()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = Modifier.testTag("wff_config_sheet_${entry.id}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 28.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Customize ${entry.displayName}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Text(
            text = "${entry.face.userConfigs.size} watch face options • Saved offline per face",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.testTag("wff_config_done_button"),
        ) {
          Text("Done")
        }
      }

      entry.face.userConfigs.forEach { cfg ->
        val currentVal = saved[cfg.id] ?: cfg.defaultValue
        when (cfg) {
          is WffUserConfig.ColorConfig -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = cfg.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
              )
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                cfg.options.forEach { opt ->
                  val selected = currentVal == opt.id
                  val swatchArgb = opt.colors.firstOrNull() ?: 0xFFFFFFFF.toInt()
                  Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                      .clip(RoundedCornerShape(12.dp))
                      .clickable { prefs.setWffConfigValue(entry.id, cfg.id, opt.id) }
                      .padding(4.dp)
                      .testTag("wff_cfg_color_${cfg.id}_${opt.id}"),
                  ) {
                    Box(
                      modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(swatchArgb))
                        .border(
                          width = if (selected) 3.dp else 1.dp,
                          color = if (selected) Color.White else MaterialTheme.colorScheme.outline,
                          shape = CircleShape,
                        ),
                      contentAlignment = Alignment.Center,
                    ) {
                      if (selected) {
                        Icon(
                          imageVector = Icons.Default.Check,
                          contentDescription = "Selected",
                          tint = if (luminanceOf(swatchArgb) > 0.6f) Color.Black else Color.White,
                          modifier = Modifier.size(20.dp),
                        )
                      }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                      text = opt.displayName,
                      style = MaterialTheme.typography.labelSmall,
                      color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }

          is WffUserConfig.ListConfig -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = cfg.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
              )
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                cfg.options.forEach { opt ->
                  val selected = currentVal == opt.id
                  FilterChip(
                    selected = selected,
                    onClick = { prefs.setWffConfigValue(entry.id, cfg.id, opt.id) },
                    label = { Text(opt.displayName) },
                    modifier = Modifier.testTag("wff_cfg_list_${cfg.id}_${opt.id}"),
                  )
                }
              }
            }
          }

          is WffUserConfig.BooleanConfig -> {
            val checked = currentVal.equals("TRUE", ignoreCase = true) || currentVal == "1"
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                  prefs.setWffConfigValue(entry.id, cfg.id, if (checked) "FALSE" else "TRUE")
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = cfg.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
              )
              Spacer(modifier = Modifier.width(12.dp))
              Switch(
                checked = checked,
                onCheckedChange = { on ->
                  prefs.setWffConfigValue(entry.id, cfg.id, if (on) "TRUE" else "FALSE")
                },
                modifier = Modifier.testTag("wff_cfg_bool_${cfg.id}"),
              )
            }
          }
        }
      }
    }
  }
}

private fun luminanceOf(argb: Int): Float {
  val r = ((argb shr 16) and 0xFF) / 255f
  val g = ((argb shr 8) and 0xFF) / 255f
  val b = (argb and 0xFF) / 255f
  return 0.299f * r + 0.587f * g + 0.114f * b
}

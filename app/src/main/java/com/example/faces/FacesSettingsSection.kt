package com.example.faces

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.faces.wff.WffConfigSheet
import com.example.faces.wff.WffImportResult
import com.example.faces.wff.WffStoredFace

/**
 * Watch face picker and Wear OS Watch Face Format (WFF) import/management card shown inside Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FacesSettingsSection(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }
  LaunchedEffect(context) {
    WatchFaceRegistry.init(context)
  }

  val allFaces by WatchFaceRegistry.facesFlow.collectAsStateWithLifecycle()
  val importedWffFaces by WatchFaceRegistry.wffFacesFlow.collectAsStateWithLifecycle()
  val selectedFaceId by prefs.selectedFaceId.collectAsStateWithLifecycle()

  var statusMessage by remember { mutableStateOf<String?>(null) }
  var statusIsError by remember { mutableStateOf(false) }
  var detailsFace by remember { mutableStateOf<WffStoredFace?>(null) }
  var customizeFace by remember { mutableStateOf<WffStoredFace?>(null) }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      when (val result = WatchFaceRegistry.importWff(context, uri)) {
        is WffImportResult.Success -> {
          val entry = result.entry
          prefs.setSelectedFaceId(entry.id)
          statusIsError = false
          val score = entry.report.scorePercent
          statusMessage = "Imported \"${entry.displayName}\" ($score% compatibility)."
        }
        is WffImportResult.Error -> {
          statusIsError = true
          statusMessage = result.reason
        }
      }
    }
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(18.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(10.dp))
        Text("Watch Faces", style = MaterialTheme.typography.titleLarge, color = Color.White)
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        allFaces.forEach { face ->
          val selected = face.id == selectedFaceId
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
              )
              .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
              )
              .clickable { prefs.setSelectedFaceId(face.id) }
              .padding(horizontal = 10.dp, vertical = 8.dp)
              .testTag("settings_face_chip_${face.id}"),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (selected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
              text = face.displayName,
              style = MaterialTheme.typography.bodyMedium,
              color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            )
          }
        }
      }

      OutlinedButton(
        onClick = {
          importLauncher.launch(
            arrayOf(
              "application/vnd.android.package-archive",
              "application/zip",
              "text/xml",
              "application/xml",
              "*/*",
            ),
          )
        },
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("settings_import_wff_button"),
      ) {
        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Import watch face (.apk, .zip, watchface.xml)")
      }

      statusMessage?.let { msg ->
        Text(
          text = msg,
          style = MaterialTheme.typography.bodyMedium,
          color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("settings_wff_import_status"),
        )
      }

      if (importedWffFaces.isNotEmpty()) {
        Text(
          text = "Imported Watch Face Format (${importedWffFaces.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        importedWffFaces.forEach { entry ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("imported_wff_row_${entry.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
              )
              val score = entry.report.scorePercent
              val partlyCount = entry.report.partlyDrawnCount
              val skipCount = entry.report.skippedCount
              Text(
                text = "Compatibility: $score% • ${entry.report.drawnCount} drawn, $partlyCount partly, $skipCount skipped",
                style = MaterialTheme.typography.bodySmall,
                color = if (skipCount == 0 && partlyCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
              )
            }
            if (entry.face.userConfigs.isNotEmpty()) {
              IconButton(
                onClick = { customizeFace = entry },
                modifier = Modifier.testTag("wff_customize_button_${entry.id}"),
              ) {
                Icon(
                  imageVector = Icons.Default.Tune,
                  contentDescription = "Customize ${entry.displayName}",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
            TextButton(
              onClick = { detailsFace = entry },
              modifier = Modifier.testTag("wff_details_button_${entry.id}"),
            ) {
              Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Face details")
            }
            IconButton(
              onClick = { WatchFaceRegistry.deleteWff(context, entry.id) },
              modifier = Modifier.testTag("wff_delete_button_${entry.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete ${entry.displayName}",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }
    }
  }

  detailsFace?.let { entry ->
    AlertDialog(
      onDismissRequest = { detailsFace = null },
      title = { Text("Face details: ${entry.displayName}") },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Source file: ${entry.sourceFileName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "Canvas: ${entry.face.width.toInt()}x${entry.face.height.toInt()} (${entry.face.clipShape})",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
          )
          Text(
            text = "Compatibility score: ${entry.report.scorePercent}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          if (entry.face.metadata.isNotEmpty()) {
            Text(
              text = "Metadata",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
            )
            entry.face.metadata.forEach { (k, v) ->
              Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
          }
          Text(
            text = "Drawn (${entry.report.drawnCount})",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF10B981),
          )
          Text(
            text = entry.report.drawnElements.joinToString(", ").ifEmpty { "None" },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
          )
          if (entry.report.partlyDrawnItems.isNotEmpty()) {
            Text(
              text = "Partly drawn (${entry.report.partlyDrawnCount})",
              style = MaterialTheme.typography.titleSmall,
              color = Color(0xFFF59E0B),
            )
            entry.report.partlyDrawnItems.forEach { item ->
              Text(
                text = "• ${item.element}: ${item.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF59E0B),
              )
            }
          }
          Text(
            text = "Skipped (${entry.report.skippedCount})",
            style = MaterialTheme.typography.titleSmall,
            color = if (entry.report.skippedItems.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
          )
          if (entry.report.skippedItems.isEmpty()) {
            Text("None — all parsed tags and resources resolved.", style = MaterialTheme.typography.bodySmall)
          } else {
            entry.report.skippedItems.forEach { item ->
              Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                  text = "• ${item.element}: ${item.reason}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { detailsFace = null }) {
          Text("Close")
        }
      },
    )
  }

  customizeFace?.let { entry ->
    WffConfigSheet(
      entry = entry,
      onDismiss = { customizeFace = null },
    )
  }
}

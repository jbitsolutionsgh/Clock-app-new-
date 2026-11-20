package com.example.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraPrefs
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons
import com.example.core.CustomFontStorage
import com.example.core.FontImportResult
import com.example.core.ui.CollapsibleSection
import com.example.faces.engine.DefinitionFace
import com.example.faces.engine.SlotLayer
import com.example.faces.engine.TextLayer
import com.example.faces.wff.WffConfigSheet
import com.example.faces.wff.WffImportResult
import com.example.faces.wff.WffStoredFace
import java.util.Locale

private data class PhotoEditorSession(
  val bitmap: Bitmap,
  val config: PhotoFaceConfig,
  val isEditingExisting: Boolean,
)

/**
 * Watch face picker, Photo Dial studio, Custom Fonts, WFF import/management,
 * and colour theme section owned by faces chiplet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FacesSettingsSection(
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val prefs = remember { CoreSingletons.prefs(context) }
  LaunchedEffect(context) {
    WatchFaceRegistry.init(context)
  }

  val allFaces by WatchFaceRegistry.facesFlow.collectAsStateWithLifecycle()
  val importedWffFaces by WatchFaceRegistry.wffFacesFlow.collectAsStateWithLifecycle()
  val photoFaces by WatchFaceRegistry.photoFacesFlow.collectAsStateWithLifecycle()
  val importedFonts by CustomFontStorage.fontsFlow.collectAsStateWithLifecycle()
  val faceDigitalFonts by prefs.faceDigitalFonts.collectAsStateWithLifecycle()
  val selectedFaceId by prefs.selectedFaceId.collectAsStateWithLifecycle()
  val themeColor by prefs.themeColor.collectAsStateWithLifecycle()

  var statusMessage by remember { mutableStateOf<String?>(null) }
  var statusIsError by remember { mutableStateOf(false) }
  var fontStatusMessage by remember { mutableStateOf<String?>(null) }
  var fontStatusIsError by remember { mutableStateOf(false) }
  var detailsFace by remember { mutableStateOf<WffStoredFace?>(null) }
  var customizeFace by remember { mutableStateOf<WffStoredFace?>(null) }
  var duplicateFound by remember { mutableStateOf<WffImportResult.DuplicateFound?>(null) }
  var editorSession by remember { mutableStateOf<PhotoEditorSession?>(null) }
  var pendingExportFace by remember { mutableStateOf<PhotoStoredFace?>(null) }

  // Faces eligible for per-face "Digital font" (never WFF faces)
  val fontEditableFaces = remember(allFaces) {
    val list = mutableListOf("standby_digital" to "Stand By Digital")
    allFaces.forEach { face ->
      if (face is DefinitionFace &&
        face.definition.layers.any { it is TextLayer || it is SlotLayer }
      ) {
        list += face.id to face.displayName
      }
    }
    list
  }
  var selectedFontTargetFaceId by remember(fontEditableFaces, selectedFaceId) {
    val preferred = fontEditableFaces.firstOrNull { it.first == selectedFaceId }?.first
      ?: fontEditableFaces.firstOrNull()?.first
      ?: "standby_digital"
    mutableStateOf(preferred)
  }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      when (val unified = WatchFaceRegistry.importAnyArchive(context, uri)) {
        is WatchFaceRegistry.UnifiedImportResult.PhotoSuccess -> {
          statusIsError = false
          statusMessage = "Imported photo face \"${unified.stored.displayName}\"."
        }
        is WatchFaceRegistry.UnifiedImportResult.PhotoError -> {
          statusIsError = true
          statusMessage = unified.reason
        }
        is WatchFaceRegistry.UnifiedImportResult.Wff -> {
          when (val result = unified.result) {
            is WffImportResult.Success -> {
              val entry = result.entry
              prefs.setSelectedFaceId(entry.id)
              statusIsError = false
              statusMessage = "Imported \"${entry.displayName}\" (${entry.report.scorePercent}% compatibility)."
            }
            is WffImportResult.DuplicateFound -> duplicateFound = result
            is WffImportResult.Error -> {
              statusIsError = true
              statusMessage = result.reason
            }
          }
        }
      }
    }
  }

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    if (uri != null) {
      when (val loaded = PhotoDialBitmapProcessor.loadPickedPhotoSafely(context, uri)) {
        is PhotoLoadResult.Success -> {
          statusIsError = false
          statusMessage = null
          val currentEditing = editorSession
          val nextConfig = if (currentEditing != null && currentEditing.isEditingExisting) {
            currentEditing.config.copy(
              zoom = 1f,
              panXRatio = 0f,
              panYRatio = 0f,
              rotationDeg = 0f,
            )
          } else {
            PhotoFaceConfig(
              id = PhotoFaceStorage.generateUniqueId(context, "Photo Dial"),
              name = "Photo Dial ${photoFaces.size + 1}",
            )
          }
          editorSession = PhotoEditorSession(
            bitmap = loaded.bitmap,
            config = nextConfig,
            isEditingExisting = currentEditing?.isEditingExisting ?: false,
          )
        }
        is PhotoLoadResult.Error -> {
          statusIsError = true
          statusMessage = loaded.reason
        }
      }
    }
  }

  val exportZipLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/zip"),
  ) { uri ->
    val target = pendingExportFace
    pendingExportFace = null
    if (uri != null && target != null) {
      val res = PhotoFaceStorage.exportFaceToUri(context, target, uri)
      if (res.isSuccess) {
        statusIsError = false
        statusMessage = "Exported \"${target.displayName}\"."
      } else {
        statusIsError = true
        statusMessage = res.exceptionOrNull()?.message ?: "Could not export watch face."
      }
    }
  }

  val importFontLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      when (val result = CustomFontStorage.importFromUri(context, uri)) {
        is FontImportResult.Success -> {
          fontStatusIsError = false
          fontStatusMessage = "Imported font \"${result.font.displayName}\"."
        }
        is FontImportResult.Error -> {
          fontStatusIsError = true
          fontStatusMessage = result.reason
        }
      }
    }
  }

  val activeFaceName = remember(allFaces, selectedFaceId) {
    allFaces.firstOrNull { it.id == selectedFaceId }?.displayName ?: "Motorsport M"
  }
  val totalImportedCount = importedWffFaces.size + photoFaces.size
  val closedSummary = if (totalImportedCount == 0) {
    "$activeFaceName • ${themeColor.label}"
  } else {
    "$activeFaceName • $totalImportedCount custom • ${themeColor.label}"
  }

  CollapsibleSection(
    title = "Watch faces",
    summary = closedSummary,
    icon = Icons.Default.Watch,
    expanded = expanded,
    onToggle = onToggle,
    headerTestTag = "settings_section_faces",
    modifier = modifier,
  ) {

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
              color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }

      OutlinedButton(
        onClick = {
          photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
          )
        },
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("settings_add_photo_face_button"),
      ) {
        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add a face from a photo")
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

      if (photoFaces.isNotEmpty()) {
        Text(
          text = "Photo Dial Faces (${photoFaces.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        photoFaces.forEach { entry ->
          val thumb = remember(entry.dialFile.absolutePath, entry.config.updatedAtMs) {
            DialThumbnailCache.getOrDecodeFile(entry.dialFile)
          }
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .padding(horizontal = 10.dp, vertical = 8.dp)
              .testTag("imported_photo_row_${entry.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (thumb != null) {
              Image(
                bitmap = thumb,
                contentDescription = "${entry.displayName} thumbnail",
                modifier = Modifier.size(40.dp).clip(CircleShape),
              )
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "${entry.config.handStyle.label} hands" +
                  if (entry.config.dateEnabled) " • Date" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = {
                val bmp = BitmapFactory.decodeFile(entry.dialFile.absolutePath)
                if (bmp != null) {
                  editorSession = PhotoEditorSession(
                    bitmap = bmp,
                    config = entry.config,
                    isEditingExisting = true,
                  )
                }
              },
              modifier = Modifier.testTag("photo_edit_button_${entry.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit ${entry.displayName}",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            IconButton(
              onClick = {
                pendingExportFace = entry
                val slug = entry.displayName.lowercase(Locale.US)
                  .replace(Regex("[^a-z0-9]+"), "_")
                  .trim('_')
                  .ifEmpty { "photo_face" }
                exportZipLauncher.launch("$slug.zip")
              },
              modifier = Modifier.testTag("photo_export_button_${entry.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.FileDownload,
                contentDescription = "Export this face (${entry.displayName})",
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
            IconButton(
              onClick = { WatchFaceRegistry.deletePhotoFace(context, entry.id) },
              modifier = Modifier.testTag("photo_delete_button_${entry.id}"),
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
                color = MaterialTheme.colorScheme.onSurface,
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
            if (com.example.faces.wff.WffFace.hasCustomizableOptions(entry.face)) {
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

      // Per-face Digital font selector (never applied to WFF faces)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(12.dp)
          .testTag("settings_digital_font_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.TextFields,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Digital font (per face)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          fontEditableFaces.forEach { (faceId, label) ->
            FilterChip(
              selected = selectedFontTargetFaceId == faceId,
              onClick = { selectedFontTargetFaceId = faceId },
              label = { Text(label) },
              modifier = Modifier.testTag("digital_font_face_$faceId"),
            )
          }
        }
        val currentFontId = faceDigitalFonts[selectedFontTargetFaceId]
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          FilterChip(
            selected = currentFontId == null || currentFontId == AuraPrefs.FONT_DEFAULT_ID,
            onClick = { prefs.setFaceDigitalFontId(selectedFontTargetFaceId, null) },
            label = { Text("Default") },
            modifier = Modifier.testTag("digital_font_option_default"),
          )
          importedFonts.forEach { font ->
            FilterChip(
              selected = currentFontId == font.id,
              onClick = { prefs.setFaceDigitalFontId(selectedFontTargetFaceId, font.id) },
              label = { Text(font.displayName) },
              modifier = Modifier.testTag("digital_font_option_${font.id}"),
            )
          }
        }
      }

      // Fonts management section
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(12.dp)
          .testTag("settings_fonts_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.FontDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Fonts",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Text(
          text = "Only import fonts you have the right to use.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("settings_fonts_legal_note"),
        )
        OutlinedButton(
          onClick = {
            importFontLauncher.launch(
              arrayOf(
                "font/ttf",
                "font/otf",
                "application/x-font-ttf",
                "application/x-font-otf",
                "application/octet-stream",
                "*/*",
              ),
            )
          },
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("settings_import_font_button"),
        ) {
          Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Import font (.ttf, .otf)")
        }

        fontStatusMessage?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (fontStatusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("settings_font_import_status"),
          )
        }

        importedFonts.forEach { font ->
          val tf = remember(font.id) { CustomFontStorage.resolveTypeface(context, font.id) }
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MaterialTheme.colorScheme.surface)
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("imported_font_row_${font.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = font.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "12:45 0123456789",
                fontFamily = tf?.let { FontFamily(it) } ?: FontFamily.Default,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }
            IconButton(
              onClick = {
                CustomFontStorage.deleteFont(context, font.id)
                fontStatusIsError = false
                fontStatusMessage = "Deleted font \"${font.displayName}\"."
              },
              modifier = Modifier.testTag("font_delete_button_${font.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete ${font.displayName}",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Palette,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Colour Theme",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AuraThemeColor.entries.forEach { option ->
          val selected = option == themeColor
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(14.dp))
              .background(
                if (selected) option.container else MaterialTheme.colorScheme.surfaceVariant,
              )
              .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) option.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp),
              )
              .clickable { prefs.setThemeColor(option) }
              .padding(horizontal = 12.dp, vertical = 10.dp)
              .testTag("theme_chip_${option.id}"),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(option.primary),
              contentAlignment = Alignment.Center,
            ) {
              if (selected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(12.dp),
                )
              }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = option.label,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
  }

  editorSession?.let { session ->
    PhotoFaceEditorDialog(
      initialBitmap = session.bitmap,
      initialConfig = session.config,
      isEditingExisting = session.isEditingExisting,
      onPickReplacementPhoto = {
        photoPickerLauncher.launch(
          PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
      },
      onSave = { finalConfig, srcBitmap, reBake ->
        if (!session.isEditingExisting || reBake) {
          val baked = PhotoDialBitmapProcessor.bakeCircularDialBitmap(
            source = srcBitmap,
            zoom = finalConfig.zoom,
            panXRatio = finalConfig.panXRatio,
            panYRatio = finalConfig.panYRatio,
            rotationDeg = finalConfig.rotationDeg,
            brightness = finalConfig.brightness,
            contrast = finalConfig.contrast,
            saturation = finalConfig.saturation,
          )
          val resetCropConfig = finalConfig.copy(
            zoom = 1f,
            panXRatio = 0f,
            panYRatio = 0f,
            rotationDeg = 0f,
            brightness = 0f,
            contrast = 1f,
            saturation = 1f,
          )
          WatchFaceRegistry.savePhotoFace(context, resetCropConfig, baked)
          if (baked !== srcBitmap) baked.recycle()
        } else {
          WatchFaceRegistry.updatePhotoFaceConfig(context, finalConfig)
        }
        statusIsError = false
        statusMessage = "Saved photo face \"${finalConfig.name}\"."
        editorSession = null
      },
      onDismiss = { editorSession = null },
    )
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
            color = MaterialTheme.colorScheme.onSurface,
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
              Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
          }
          val sensorDiag = remember(entry.id) { CoreSingletons.sensorSource(context).diagnostics.value }
          val compMap = remember(entry.id) { prefs.getWffComplicationProviders(entry.id) }
          val dataSources = remember(entry.id, sensorDiag, compMap) {
            com.example.faces.wff.WffExpressionEvaluator.buildDataSourceProvenance(entry.face, sensorDiag, compMap)
          }
          if (dataSources.isNotEmpty()) {
            Text(
              text = "Data sources",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
            )
            dataSources.forEach { ds ->
              val originColor = when (ds.origin) {
                com.example.core.WffDataSourceOrigin.REAL_SENSOR -> Color(0xFF10B981)
                com.example.core.WffDataSourceOrigin.AURACLOCK_DATA -> MaterialTheme.colorScheme.primary
                com.example.core.WffDataSourceOrigin.NO_DATA -> Color(0xFFF59E0B)
              }
              Text(
                text = "• ${ds.source}: ${ds.origin.label} (${ds.detail})",
                style = MaterialTheme.typography.bodySmall,
                color = originColor,
              )
            }
          }
          if (entry.face.localizationCalendar != null || entry.face.localizationLocales != null) {
            Text(
              text = "Localization: ${entry.face.localizationCalendar ?: "GREGORIAN"} ${entry.face.localizationLocales.orEmpty()}".trim(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            text = "Drawn (${entry.report.drawnCount})",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF10B981),
          )
          Text(
            text = entry.report.drawnElements.joinToString(", ").ifEmpty { "None" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
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

  duplicateFound?.let { dup ->
    AlertDialog(
      onDismissRequest = {
        com.example.faces.wff.WffStorageManager.cancelStagedImport(dup.stagedFile)
        duplicateFound = null
      },
      modifier = Modifier.testTag("wff_duplicate_dialog"),
      title = { Text("Watch face already imported") },
      text = {
        Text(
          "\"${dup.existingFace.displayName}\" (${dup.existingFace.sourceFileName}) matches this watch face. Replace the existing copy or cancel?",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            when (val res = WatchFaceRegistry.replaceWff(context, dup.stagedFile, dup.sourceFileName, dup.existingFace.id)) {
              is WffImportResult.Success -> {
                prefs.setSelectedFaceId(res.entry.id)
                statusIsError = false
                statusMessage = "Replaced \"${res.entry.displayName}\" (${res.entry.report.scorePercent}% compatibility)."
              }
              is WffImportResult.Error -> {
                statusIsError = true
                statusMessage = res.reason
              }
              is WffImportResult.DuplicateFound -> Unit
            }
            duplicateFound = null
          },
          modifier = Modifier.testTag("wff_duplicate_replace_button"),
        ) {
          Text("Replace")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            com.example.faces.wff.WffStorageManager.cancelStagedImport(dup.stagedFile)
            duplicateFound = null
          },
          modifier = Modifier.testTag("wff_duplicate_cancel_button"),
        ) {
          Text("Cancel")
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

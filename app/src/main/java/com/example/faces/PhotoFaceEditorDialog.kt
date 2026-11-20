package com.example.faces

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AuraPrefs
import com.example.core.CustomFontStorage
import com.example.core.FaceRenderMode
import com.example.faces.PhotoDialRenderer.drawPhotoDialOverlay
import kotlin.math.hypot
import kotlin.math.roundToInt

private val handPaletteColors: List<Pair<String, Int>> = listOf(
  "White" to 0xFFFFFFFF.toInt(),
  "Silver" to 0xFFE2E8F0.toInt(),
  "Black" to 0xFF111827.toInt(),
  "Red" to 0xFFE00424.toInt(),
  "Cobalt" to 0xFF3B82F6.toInt(),
  "Gold" to 0xFFD4AF37.toInt(),
  "Volt" to 0xFFCCFF00.toInt(),
  "Emerald" to 0xFF10B981.toInt(),
)

/**
 * Full-featured two-step Photo Dial editor dialog:
 * Step 0 = Crop, zoom, rotate/12-o'clock nudge, and brightness/contrast/saturation.
 * Step 1 = Look, hands, lume outline, centre cap, shadow, date window tap placement, and name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoFaceEditorDialog(
  initialBitmap: Bitmap,
  initialConfig: PhotoFaceConfig,
  isEditingExisting: Boolean,
  onSave: (config: PhotoFaceConfig, sourceBitmap: Bitmap, reBakeImage: Boolean) -> Unit,
  onPickReplacementPhoto: (() -> Unit)? = null,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val importedFonts by CustomFontStorage.fontsFlow.collectAsStateWithLifecycle()

  var activeTab by remember { mutableIntStateOf(if (isEditingExisting) 1 else 0) }
  var config by remember(initialConfig.id) { mutableStateOf(initialConfig) }
  var cropChanged by remember(initialConfig.id, initialBitmap) { mutableStateOf(!isEditingExisting) }
  var nameError by remember { mutableStateOf<String?>(null) }

  val scratch = remember { PhotoDialRenderer.HandsScratch() }
  val bitmapPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
  val previewTime = remember {
    ClockTimeSnapshot(
      hour12 = 10,
      hour24 = 10,
      minute = 10,
      second = 32,
      millis = 0,
      dayOfMonth = 15,
      dayOfWeekShort = "FRI",
      monthDayLabel = "FRI, NOV 15",
      isAm = true,
    )
  }
  val resolvedDateTypeface = remember(config.dateFontId, importedFonts) {
    CustomFontStorage.resolveTypeface(context, config.dateFontId)
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.background,
      shape = RoundedCornerShape(24.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
        .testTag("photo_face_editor_dialog"),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (isEditingExisting) "Edit photo watch face" else "Create photo watch face",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          IconButton(onClick = onDismiss, modifier = Modifier.testTag("photo_editor_close_button")) {
            Icon(Icons.Default.Close, contentDescription = "Close editor")
          }
        }

        TabRow(selectedTabIndex = activeTab) {
          Tab(
            selected = activeTab == 0,
            onClick = { activeTab = 0 },
            modifier = Modifier.testTag("photo_editor_tab_crop"),
            text = { Text("1. Crop") },
          )
          Tab(
            selected = activeTab == 1,
            onClick = { activeTab = 1 },
            modifier = Modifier.testTag("photo_editor_tab_look"),
            text = { Text("2. Look & hands") },
          )
        }

        // Live circular dial preview stage (supports pan/pinch in Crop tab and tap-to-place-date in Look tab)
        Box(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = Alignment.Center,
        ) {
          BoxWithConstraints(
            modifier = Modifier
              .fillMaxWidth(0.78f)
              .aspectRatio(1f)
              .clip(CircleShape)
              .background(Color.Black)
              .testTag("photo_editor_preview_circle"),
            contentAlignment = Alignment.Center,
          ) {
            val stageDiameterPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

            val interactiveModifier = if (activeTab == 0) {
              Modifier
                .fillMaxSize()
                .pointerInput(initialBitmap, config.rotationDeg, stageDiameterPx) {
                  detectTransformGestures { _, pan, zoomChange, _ ->
                    val newZoom = PhotoDialCropMath.clampZoom(config.zoom * zoomChange)
                    val half = stageDiameterPx * 0.5f
                    val rawPanX = config.panXRatio * half + pan.x
                    val rawPanY = config.panYRatio * half + pan.y
                    val (clampedX, clampedY) = PhotoDialCropMath.clampScreenPan(
                      panX = rawPanX,
                      panY = rawPanY,
                      srcWidth = initialBitmap.width,
                      srcHeight = initialBitmap.height,
                      targetDiameterPx = stageDiameterPx,
                      zoom = newZoom,
                      rotationDeg = config.rotationDeg,
                    )
                    config = config.copy(
                      zoom = newZoom,
                      panXRatio = clampedX / half,
                      panYRatio = clampedY / half,
                    )
                    cropChanged = true
                  }
                }
            } else {
              Modifier
                .fillMaxSize()
                .pointerInput(config.dateEnabled, stageDiameterPx) {
                  detectTapGestures { tapOffset ->
                    if (config.dateEnabled) {
                      val r = stageDiameterPx * 0.5f
                      val dx = (tapOffset.x - r) / r
                      val dy = (tapOffset.y - r) / r
                      val dist = hypot(dx, dy)
                      val maxR = 0.72f
                      val clampedX = if (dist > maxR && dist > 0f) dx * (maxR / dist) else dx
                      val clampedY = if (dist > maxR && dist > 0f) dy * (maxR / dist) else dy
                      config = config.copy(
                        dateXRatio = clampedX.coerceIn(-maxR, maxR),
                        dateYRatio = clampedY.coerceIn(-maxR, maxR),
                      )
                    }
                  }
                }
            }

            Canvas(modifier = interactiveModifier) {
              val side = size.minDimension
              val radius = side * 0.5f
              val c = Offset(size.width * 0.5f, size.height * 0.5f)
              if (!initialBitmap.isRecycled) {
                val matrix = PhotoDialBitmapProcessor.buildCropMatrix(
                  srcWidth = initialBitmap.width,
                  srcHeight = initialBitmap.height,
                  viewportDiameterPx = side,
                  zoom = config.zoom,
                  panXRatio = config.panXRatio,
                  panYRatio = config.panYRatio,
                  rotationDeg = config.rotationDeg,
                )
                bitmapPaint.colorFilter = ColorMatrixColorFilter(
                  PhotoDialBitmapProcessor.buildToneColorMatrix(
                    brightness = config.brightness,
                    contrast = config.contrast,
                    saturation = config.saturation,
                  ),
                )
                drawContext.canvas.nativeCanvas.drawBitmap(initialBitmap, matrix, bitmapPaint)
              }

              // Circular bezel guide
              drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = radius - 1.dp.toPx(),
                center = c,
                style = Stroke(width = 2.dp.toPx()),
              )

              drawPhotoDialOverlay(
                config = config,
                time = previewTime,
                renderMode = FaceRenderMode.NORMAL,
                dateTypeface = resolvedDateTypeface,
                scratch = scratch,
              )
            }
          }
        }

        if (activeTab == 0) {
          Text(
            text = "Drag to move and pinch to zoom inside the dial circle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Zoom slider
          LabeledSlider(
            label = "Zoom (${String.format("%.1fx", config.zoom)})",
            value = config.zoom,
            range = PhotoDialCropMath.MIN_ZOOM..PhotoDialCropMath.MAX_ZOOM,
            tag = "photo_crop_zoom_slider",
            onValueChange = { z ->
              config = config.copy(zoom = PhotoDialCropMath.clampZoom(z))
              cropChanged = true
            },
          )

          // Rotation + 12 o'clock nudge
          LabeledSlider(
            label = "Rotation (${config.rotationDeg.roundToInt()}°)",
            value = config.rotationDeg,
            range = -180f..180f,
            tag = "photo_crop_rotation_slider",
            onValueChange = { deg ->
              config = config.copy(rotationDeg = PhotoDialCropMath.normalizeAngleDeg(deg))
              cropChanged = true
            },
          )

          Text(
            text = "12 o'clock rotation nudge",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = {
                config = config.copy(rotationDeg = PhotoDialCropMath.normalizeAngleDeg(config.rotationDeg - 90f))
                cropChanged = true
              },
              modifier = Modifier.heightIn(min = 48.dp).testTag("photo_nudge_minus_90"),
            ) {
              Icon(Icons.Default.RotateLeft, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("-90°")
            }
            OutlinedButton(
              onClick = {
                config = config.copy(rotationDeg = PhotoDialCropMath.normalizeAngleDeg(config.rotationDeg - 1f))
                cropChanged = true
              },
              modifier = Modifier.heightIn(min = 48.dp).testTag("photo_nudge_minus_1"),
            ) {
              Text("-1°")
            }
            OutlinedButton(
              onClick = {
                config = config.copy(rotationDeg = 0f, panXRatio = 0f, panYRatio = 0f)
                cropChanged = true
              },
              modifier = Modifier.heightIn(min = 48.dp).testTag("photo_nudge_reset"),
            ) {
              Text("Center / 0°")
            }
            OutlinedButton(
              onClick = {
                config = config.copy(rotationDeg = PhotoDialCropMath.normalizeAngleDeg(config.rotationDeg + 1f))
                cropChanged = true
              },
              modifier = Modifier.heightIn(min = 48.dp).testTag("photo_nudge_plus_1"),
            ) {
              Text("+1°")
            }
            OutlinedButton(
              onClick = {
                config = config.copy(rotationDeg = PhotoDialCropMath.normalizeAngleDeg(config.rotationDeg + 90f))
                cropChanged = true
              },
              modifier = Modifier.heightIn(min = 48.dp).testTag("photo_nudge_plus_90"),
            ) {
              Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("+90°")
            }
          }

          LabeledSlider(
            label = "Brightness (${(config.brightness * 100).roundToInt()}%)",
            value = config.brightness,
            range = -0.45f..0.45f,
            tag = "photo_crop_brightness_slider",
            onValueChange = { b ->
              config = config.copy(brightness = b)
              cropChanged = true
            },
          )
          LabeledSlider(
            label = "Contrast (${String.format("%.2fx", config.contrast)})",
            value = config.contrast,
            range = 0.5f..1.8f,
            tag = "photo_crop_contrast_slider",
            onValueChange = { c ->
              config = config.copy(contrast = c)
              cropChanged = true
            },
          )
          LabeledSlider(
            label = "Saturation (${String.format("%.2fx", config.saturation)})",
            value = config.saturation,
            range = 0f..2f,
            tag = "photo_crop_saturation_slider",
            onValueChange = { s ->
              config = config.copy(saturation = s)
              cropChanged = true
            },
          )

          if (onPickReplacementPhoto != null) {
            OutlinedButton(
              onClick = onPickReplacementPhoto,
              modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
              Text("Choose a different photo")
            }
          }

          Button(
            onClick = { activeTab = 1 },
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("photo_editor_next_button"),
          ) {
            Text("Next: Look & hands")
          }
        } else {
          OutlinedTextField(
            value = config.name,
            onValueChange = {
              config = config.copy(name = it.take(48))
              nameError = null
            },
            label = { Text("Watch face name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { msg -> { Text(msg) } },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("photo_editor_name_input"),
          )

          Text("Hand style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            PhotoHandStyle.entries.forEach { style ->
              FilterChip(
                selected = config.handStyle == style,
                onClick = { config = config.copy(handStyle = style) },
                label = { Text(style.label) },
                modifier = Modifier.testTag("photo_hand_style_${style.id}"),
              )
            }
          }

          ColorSwatchRow(
            label = "Hour hand colour",
            selectedArgb = config.hourColorArgb,
            tagPrefix = "photo_hour_color",
            onSelect = { config = config.copy(hourColorArgb = it) },
          )
          ColorSwatchRow(
            label = "Minute hand colour",
            selectedArgb = config.minuteColorArgb,
            tagPrefix = "photo_minute_color",
            onSelect = { config = config.copy(minuteColorArgb = it) },
          )
          ColorSwatchRow(
            label = "Second hand colour",
            selectedArgb = config.secondColorArgb,
            tagPrefix = "photo_second_color",
            onSelect = { config = config.copy(secondColorArgb = it) },
          )

          Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Lume outline", style = MaterialTheme.typography.bodyLarge)
            Switch(
              checked = config.lumeOutlineEnabled,
              onCheckedChange = { config = config.copy(lumeOutlineEnabled = it) },
              modifier = Modifier.testTag("photo_lume_switch"),
            )
          }
          if (config.lumeOutlineEnabled) {
            ColorSwatchRow(
              label = "Lume glow colour",
              selectedArgb = config.lumeColorArgb,
              tagPrefix = "photo_lume_color",
              onSelect = { config = config.copy(lumeColorArgb = it) },
            )
          }

          LabeledSlider(
            label = "Hour hand length (${(config.hourLengthRatio * 100).roundToInt()}%)",
            value = config.hourLengthRatio,
            range = 0.30f..0.65f,
            tag = "photo_hour_length_slider",
            onValueChange = { config = config.copy(hourLengthRatio = it) },
          )
          LabeledSlider(
            label = "Minute hand length (${(config.minuteLengthRatio * 100).roundToInt()}%)",
            value = config.minuteLengthRatio,
            range = 0.50f..0.90f,
            tag = "photo_minute_length_slider",
            onValueChange = { config = config.copy(minuteLengthRatio = it) },
          )
          LabeledSlider(
            label = "Hand width (${String.format("%.1fx", config.handWidthScale)})",
            value = config.handWidthScale,
            range = 0.6f..1.8f,
            tag = "photo_hand_width_slider",
            onValueChange = { config = config.copy(handWidthScale = it) },
          )
          LabeledSlider(
            label = "Hand shadow strength (${(config.shadowStrength * 100).roundToInt()}%)",
            value = config.shadowStrength,
            range = 0f..1f,
            tag = "photo_shadow_strength_slider",
            onValueChange = { config = config.copy(shadowStrength = it) },
          )

          Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Show second hand", style = MaterialTheme.typography.bodyLarge)
            Switch(
              checked = config.showSecondHand,
              onCheckedChange = { config = config.copy(showSecondHand = it) },
              modifier = Modifier.testTag("photo_second_hand_switch"),
            )
          }

          if (config.showSecondHand) {
            Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = if (config.smoothSeconds) "Seconds motion: Smooth sweep" else "Seconds motion: Ticking",
                style = MaterialTheme.typography.bodyLarge,
              )
              Switch(
                checked = config.smoothSeconds,
                onCheckedChange = { config = config.copy(smoothSeconds = it) },
                modifier = Modifier.testTag("photo_smooth_seconds_switch"),
              )
            }
          }

          Text("Centre cap style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            PhotoCenterCapStyle.entries.forEach { cap ->
              FilterChip(
                selected = config.centerCapStyle == cap,
                onClick = { config = config.copy(centerCapStyle = cap) },
                label = { Text(cap.label) },
                modifier = Modifier.testTag("photo_cap_style_${cap.id}"),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Date window", style = MaterialTheme.typography.bodyLarge)
            Switch(
              checked = config.dateEnabled,
              onCheckedChange = { config = config.copy(dateEnabled = it) },
              modifier = Modifier.testTag("photo_date_enabled_switch"),
            )
          }

          if (config.dateEnabled) {
            Text(
              text = "Tap anywhere inside the watch preview above to position the date window.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
            LabeledSlider(
              label = "Date size",
              value = config.dateSizeRatio,
              range = 0.05f..0.14f,
              tag = "photo_date_size_slider",
              onValueChange = { config = config.copy(dateSizeRatio = it) },
            )
            ColorSwatchRow(
              label = "Date colour",
              selectedArgb = config.dateColorArgb,
              tagPrefix = "photo_date_color",
              onSelect = { config = config.copy(dateColorArgb = it) },
            )
            Text("Date font", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              FilterChip(
                selected = config.dateFontId == null || config.dateFontId == AuraPrefs.FONT_DEFAULT_ID,
                onClick = { config = config.copy(dateFontId = null) },
                label = { Text("Default") },
                modifier = Modifier.testTag("photo_date_font_default"),
              )
              importedFonts.forEach { font ->
                FilterChip(
                  selected = config.dateFontId == font.id,
                  onClick = { config = config.copy(dateFontId = font.id) },
                  label = { Text(font.displayName) },
                  modifier = Modifier.testTag("photo_date_font_${font.id}"),
                )
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            TextButton(
              onClick = onDismiss,
              modifier = Modifier.weight(1f).height(48.dp),
            ) {
              Text("Cancel")
            }
            Button(
              onClick = {
                val trimmedName = config.name.trim()
                if (trimmedName.isEmpty()) {
                  nameError = "Please enter a name for this face."
                  return@Button
                }
                onSave(config.copy(name = trimmedName), initialBitmap, cropChanged)
              },
              modifier = Modifier
                .weight(2f)
                .height(48.dp)
                .testTag("photo_editor_save_button"),
            ) {
              Text("Save watch face")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LabeledSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  tag: String,
  onValueChange: (Float) -> Unit,
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onValueChange,
      valueRange = range,
      modifier = Modifier.testTag(tag),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchRow(
  label: String,
  selectedArgb: Int,
  tagPrefix: String,
  onSelect: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      handPaletteColors.forEach { (colorName, argb) ->
        val selected = argb == selectedArgb
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
              width = if (selected) 3.dp else 1.dp,
              color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
              shape = CircleShape,
            )
            .clickable { onSelect(argb) }
            .testTag("${tagPrefix}_$colorName"),
          contentAlignment = Alignment.Center,
        ) {
          if (selected) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = "$colorName selected",
              tint = if (argb == 0xFF111827.toInt()) Color.White else Color.Black,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }
  }
}

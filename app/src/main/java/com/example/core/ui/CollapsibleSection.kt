package com.example.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Controls whether Settings allows only one collapsible section open at a time (false)
 * or lets several stay open together (true).
 */
const val ALLOW_MULTIPLE_OPEN_SECTIONS: Boolean = false

/**
 * Pure accordion state transitions and Saver used across Settings sections.
 */
object SettingsAccordion {

  /** Initial state when Settings opens: all sections start collapsed. */
  fun initialOpenSections(): Set<String> = emptySet()

  /** Returns true when [sectionId] is currently expanded in [openSections]. */
  fun isExpanded(openSections: Set<String>, sectionId: String): Boolean =
    sectionId in openSections

  /**
   * Computes the next set of open section IDs after toggling [sectionId].
   * When [allowMultiple] is false, opening a new section closes the previous one.
   */
  fun toggleSection(
    openSections: Set<String>,
    sectionId: String,
    allowMultiple: Boolean = ALLOW_MULTIPLE_OPEN_SECTIONS,
  ): Set<String> = when {
    sectionId in openSections -> openSections - sectionId
    allowMultiple -> openSections + sectionId
    else -> setOf(sectionId)
  }

  /** Saveable Saver for storing open section IDs across rotation and navigation. */
  fun saver(): Saver<Set<String>, ArrayList<String>> = Saver(
    save = { ArrayList(it) },
    restore = { it.toSet() },
  )
}

/**
 * Shared Material 3 collapsible card owned by core UI so every chiplet renders its Settings
 * section consistently without knowing about any other chiplet.
 * Inner [content] is only composed while [expanded] is true.
 */
@Composable
fun CollapsibleSection(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  icon: ImageVector? = null,
  headerTestTag: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = RoundedCornerShape(18.dp)
  val borderColor = if (expanded) {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
  } else {
    MaterialTheme.colorScheme.outline
  }
  val chevronRotation by animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec = tween(durationMillis = 200),
    label = "collapsible_chevron",
  )

  val accessibilitySummary = if (!expanded && !summary.isNullOrBlank()) "$title, $summary" else title
  val accessibilityState = if (expanded) "Expanded" else "Collapsed"

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = shape,
    modifier = modifier
      .fillMaxWidth()
      .border(width = 1.dp, color = borderColor, shape = shape)
      .clip(shape),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = tween(durationMillis = 200)),
    ) {
      val headerBase = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 64.dp)
        .let { m -> if (headerTestTag != null) m.testTag(headerTestTag) else m }
        .semantics(mergeDescendants = true) {
          contentDescription = accessibilitySummary
          stateDescription = accessibilityState
        }
        .clickable(
          role = Role.Button,
          onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
          onClick = onToggle,
        )
        .padding(horizontal = 16.dp, vertical = 14.dp)

      Row(
        modifier = headerBase,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (icon != null) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
          Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          if (!expanded && !summary.isNullOrBlank()) {
            Text(
              text = summary,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))
        Icon(
          imageVector = Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .size(24.dp)
            .graphicsLayer { rotationZ = chevronRotation },
        )
      }

      if (expanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          content = content,
        )
      }
    }
  }
}

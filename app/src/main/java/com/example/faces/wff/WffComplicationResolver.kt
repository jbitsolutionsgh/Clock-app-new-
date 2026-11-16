package com.example.faces.wff

import com.example.core.AppTabTarget
import com.example.core.FaceData
import com.example.core.TimerPhase
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Offline AuraClock complication data sources that fill Watch Face Format ComplicationSlots. */
enum class AuraComplicationProvider(
  val id: String,
  val label: String,
  val description: String,
  val tapTarget: AppTabTarget?,
) {
  STEPS("steps", "Steps", "Today's phone steps & goal", AppTabTarget.DIAGNOSTICS),
  BATTERY("battery", "Battery", "Phone battery level", AppTabTarget.DIAGNOSTICS),
  NEXT_EVENT("next_event", "Next Event", "Upcoming Business Calendar event", AppTabTarget.SETTINGS_CALENDAR),
  TIMER("timer", "Timer", "Classroom countdown timer", AppTabTarget.TIMER),
  STOPWATCH("stopwatch", "Stopwatch", "Elapsed stopwatch time", AppTabTarget.STOPWATCH),
  DATE("date", "Date", "Month and day", AppTabTarget.CLOCK),
  DAY_OF_WEEK("day_of_week", "Day of Week", "Current weekday", AppTabTarget.CLOCK),
  EMPTY("empty", "Off (Empty)", "Hide this slot", null);

  companion object {
    /** Finds a provider by saved ID, or null if absent. */
    fun fromId(id: String?): AuraComplicationProvider? =
      entries.firstOrNull { it.id == id }

    /** Maps a WFF `<DefaultProviderPolicy>` onto the best matching offline AuraClock provider. */
    fun fromPolicy(
      policy: WffDefaultProviderPolicy?,
      supportedTypes: List<String>,
      slotIndex: Int = 0,
    ): AuraComplicationProvider {
      if (policy?.defaultSystemProviderType.equals("EMPTY", ignoreCase = true)) {
        return EMPTY
      }
      val sys = policy?.defaultSystemProvider?.trim()?.uppercase(Locale.US).orEmpty()
      return when (sys) {
        "STEP_COUNT", "STEPS" -> STEPS
        "WATCH_BATTERY", "BATTERY" -> BATTERY
        "NEXT_EVENT", "CALENDAR" -> NEXT_EVENT
        "DAY_AND_DATE", "DATE", "TIME_AND_DATE", "WORLD_CLOCK" -> DATE
        "DAY_OF_WEEK" -> DAY_OF_WEEK
        "TIMER" -> TIMER
        "STOPWATCH" -> STOPWATCH
        "UNREAD_NOTIFICATION_COUNT" -> NEXT_EVENT
        else -> when {
          "LONG_TEXT" in supportedTypes -> NEXT_EVENT
          "GOAL_PROGRESS" in supportedTypes -> STEPS
          "RANGED_VALUE" in supportedTypes -> when (slotIndex % 3) {
            0 -> STEPS
            1 -> BATTERY
            else -> TIMER
          }
          else -> when (slotIndex % 4) {
            0 -> DATE
            1 -> STEPS
            2 -> BATTERY
            else -> NEXT_EVENT
          }
        }
      }
    }
  }
}

enum class WffComplicationIconKind {
  NONE,
  STEPS,
  BATTERY,
  CALENDAR,
  TIMER,
  STOPWATCH,
  DATE,
}

/** Resolved runtime payload bound to `COMPLICATION.*` while rendering a single ComplicationSlot. */
internal data class WffResolvedComplication(
  val slotId: String,
  val activeType: String,
  val provider: AuraComplicationProvider,
  val text: String = "",
  val title: String = "",
  val rangedValue: Float = 0f,
  val rangedMin: Float = 0f,
  val rangedMax: Float = 100f,
  val goalValue: Float = 0f,
  val goalTarget: Float = 10000f,
  val weightedColors: IntArray = intArrayOf(0xFF3B82F6.toInt(), 0xFF334155.toInt()),
  val weightedWeights: FloatArray = floatArrayOf(1f, 1f),
  val iconKind: WffComplicationIconKind = WffComplicationIconKind.NONE,
  val isEmpty: Boolean = false,
) {
  override fun equals(other: Any?): Boolean =
    this === other || (other is WffResolvedComplication && slotId == other.slotId &&
      activeType == other.activeType && provider == other.provider && text == other.text &&
      title == other.title && rangedValue == other.rangedValue && isEmpty == other.isEmpty)

  override fun hashCode(): Int = slotId.hashCode() * 31 + text.hashCode()
}

/**
 * Resolves offline AuraClock data (`FaceData`) into WFF `COMPLICATION.*` fields for each slot.
 */
internal object WffComplicationResolver {

  private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

  /** Produces a [WffResolvedComplication] for [slot] using [provider] and live [data]. */
  fun resolve(
    slot: WffSceneNode.ComplicationSlot,
    provider: AuraComplicationProvider,
    data: FaceData,
    showEventTitles: Boolean = true,
  ): WffResolvedComplication {
    if (provider == AuraComplicationProvider.EMPTY) {
      return WffResolvedComplication(
        slotId = slot.slotId,
        activeType = "EMPTY",
        provider = provider,
        isEmpty = true,
      )
    }

    val chosenType = pickActiveType(slot, provider)
    return when (provider) {
      AuraComplicationProvider.STEPS -> {
        val steps = data.sensors.stepCount.coerceAtLeast(0)
        val goal = data.sensors.stepGoal.coerceAtLeast(1)
        val rem = (goal - steps).coerceAtLeast(0).toFloat()
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = NumberFormat.getIntegerInstance(Locale.getDefault()).format(steps),
          title = "Steps",
          rangedValue = steps.toFloat(),
          rangedMin = 0f,
          rangedMax = goal.toFloat(),
          goalValue = steps.toFloat(),
          goalTarget = goal.toFloat(),
          weightedColors = intArrayOf(0xFF10B981.toInt(), 0xFF1E293B.toInt()),
          weightedWeights = floatArrayOf(steps.coerceAtLeast(1).toFloat(), rem.coerceAtLeast(0.5f)),
          iconKind = WffComplicationIconKind.STEPS,
        )
      }
      AuraComplicationProvider.BATTERY -> {
        val pct = data.battery.levelPercent.coerceIn(0, 100)
        val rem = (100 - pct).toFloat()
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = "$pct%",
          title = if (data.battery.isCharging) "Chg" else "Bat",
          rangedValue = pct.toFloat(),
          rangedMin = 0f,
          rangedMax = 100f,
          goalValue = pct.toFloat(),
          goalTarget = 100f,
          weightedColors = intArrayOf(
            if (pct <= 20) 0xFFEF4444.toInt() else 0xFF3B82F6.toInt(),
            0xFF1E293B.toInt(),
          ),
          weightedWeights = floatArrayOf(pct.coerceAtLeast(1).toFloat(), rem.coerceAtLeast(0.5f)),
          iconKind = WffComplicationIconKind.BATTERY,
        )
      }
      AuraComplicationProvider.NEXT_EVENT -> {
        val evt = data.nextEvent
        val text = if (evt == null) {
          "No event"
        } else if (evt.isAllDay) {
          "All day"
        } else {
          timeFmt.get()?.format(Date(evt.startEpochMs)).orEmpty()
        }
        val title = when {
          evt == null -> "Calendar"
          !showEventTitles -> "Event"
          else -> evt.title
        }
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = if (chosenType == "LONG_TEXT" && evt != null) "$text • $title" else text,
          title = title,
          rangedValue = if (evt != null) 1f else 0f,
          rangedMin = 0f,
          rangedMax = 1f,
          iconKind = WffComplicationIconKind.CALENDAR,
        )
      }
      AuraComplicationProvider.TIMER -> {
        val tm = data.timer
        val remSec = ((tm.remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        val mm = remSec / 60L
        val ss = remSec % 60L
        val label = if (tm.phase == TimerPhase.IDLE && tm.remainingMs <= 0L) "Ready" else String.format(Locale.US, "%02d:%02d", mm, ss)
        val total = tm.totalDurationMs.coerceAtLeast(1L).toFloat()
        val left = tm.remainingMs.coerceAtLeast(0L).toFloat()
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = label,
          title = "R${tm.roundNumber}",
          rangedValue = left,
          rangedMin = 0f,
          rangedMax = total,
          goalValue = left,
          goalTarget = total,
          weightedColors = intArrayOf(0xFFF59E0B.toInt(), 0xFF1E293B.toInt()),
          weightedWeights = floatArrayOf(left.coerceAtLeast(1f), (total - left).coerceAtLeast(0.5f)),
          iconKind = WffComplicationIconKind.TIMER,
        )
      }
      AuraComplicationProvider.STOPWATCH -> {
        val sw = data.stopwatch
        val totalSec = (sw.elapsedMs / 1000L).coerceAtLeast(0L)
        val mm = totalSec / 60L
        val ss = totalSec % 60L
        val label = String.format(Locale.US, "%02d:%02d", mm, ss)
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = label,
          title = if (sw.isRunning) "Run" else "SW",
          rangedValue = (totalSec % 60).toFloat(),
          rangedMin = 0f,
          rangedMax = 60f,
          iconKind = WffComplicationIconKind.STOPWATCH,
        )
      }
      AuraComplicationProvider.DATE -> {
        val t = data.time
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = "${t.dayOfMonth}",
          title = t.monthNameShort,
          rangedValue = t.dayOfMonth.toFloat(),
          rangedMin = 1f,
          rangedMax = 31f,
          iconKind = WffComplicationIconKind.DATE,
        )
      }
      AuraComplicationProvider.DAY_OF_WEEK -> {
        val t = data.time
        WffResolvedComplication(
          slotId = slot.slotId,
          activeType = chosenType,
          provider = provider,
          text = t.dayNameShort,
          title = "${t.monthNameShort} ${t.dayOfMonth}",
          rangedValue = t.dayOfWeek.toFloat(),
          rangedMin = 1f,
          rangedMax = 7f,
          iconKind = WffComplicationIconKind.DATE,
        )
      }
      AuraComplicationProvider.EMPTY -> WffResolvedComplication(slot.slotId, "EMPTY", provider, isEmpty = true)
    }
  }

  private fun pickActiveType(slot: WffSceneNode.ComplicationSlot, provider: AuraComplicationProvider): String {
    val available = slot.templates.map { it.type }.toSet()
    val preferred = when (provider) {
      AuraComplicationProvider.NEXT_EVENT -> listOf("LONG_TEXT", "SHORT_TEXT", "SMALL_IMAGE", "MONOCHROMATIC_IMAGE")
      AuraComplicationProvider.STEPS -> listOf("GOAL_PROGRESS", "RANGED_VALUE", "SHORT_TEXT", "WEIGHTED_ELEMENTS", "SMALL_IMAGE")
      AuraComplicationProvider.BATTERY, AuraComplicationProvider.TIMER -> listOf("RANGED_VALUE", "GOAL_PROGRESS", "SHORT_TEXT", "WEIGHTED_ELEMENTS")
      else -> listOf("SHORT_TEXT", "RANGED_VALUE", "LONG_TEXT", "SMALL_IMAGE", "MONOCHROMATIC_IMAGE")
    }
    return preferred.firstOrNull { it in available }
      ?: slot.defaultProviderPolicy?.defaultSystemProviderType?.takeIf { it in available }
      ?: slot.templates.firstOrNull()?.type
      ?: "SHORT_TEXT"
  }
}

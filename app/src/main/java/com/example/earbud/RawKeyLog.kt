package com.example.earbud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Arrival route for a media button or transport callback event reaching AuraClock. */
internal enum class KeyArrivalRoute(val label: String) {
  MEDIA_BUTTON_RECEIVER("Media button receiver"),
  SERVICE_FORWARD("Service forward"),
  MEDIA_SESSION_EVENT("MediaSession event"),
  MEDIA_SESSION_PLAY("MediaSession play"),
  MEDIA_SESSION_PAUSE("MediaSession pause"),
  MEDIA_SESSION_NEXT("MediaSession next"),
  MEDIA_SESSION_PREVIOUS("MediaSession previous"),
  LEARN_MODE("Learn mode"),
  DIRECT("Direct"),
}

/** Single button event captured in memory for Diagnostics. */
internal data class RawKeyLogEntry(
  val timestampLabel: String,
  val keyCode: Int,
  val keyName: String,
  val isDown: Boolean,
  val route: String,
  val outcome: String,
) {
  val directionLabel: String
    get() = if (isDown) "DOWN" else "UP"

  fun formatLine(): String =
    "$timestampLabel  $directionLabel  $keyName ($keyCode)  [$route]  -> $outcome"
}

/**
 * In-memory ring buffer storing the last [MAX_ENTRIES] raw earbud/media button events.
 * Never persisted to disk. Thread-safe across broadcast, service binder, and main threads.
 */
internal object RawKeyLog {

  const val MAX_ENTRIES = 30

  private val lock = Any()
  private val buffer = ArrayDeque<RawKeyLogEntry>(MAX_ENTRIES + 1)

  private val _entries = MutableStateFlow<List<RawKeyLogEntry>>(emptyList())
  val entries: StateFlow<List<RawKeyLogEntry>> = _entries.asStateFlow()

  /** Appends a button event to the head of the log, keeping at most the last [MAX_ENTRIES]. */
  fun record(
    keyCode: Int,
    isDown: Boolean,
    route: String,
    outcome: String,
    timestampLabel: String = currentTimestampLabel(),
  ) {
    val entry = RawKeyLogEntry(
      timestampLabel = timestampLabel,
      keyCode = keyCode,
      keyName = PressPatternDetector.androidKeyName(keyCode),
      isDown = isDown,
      route = route,
      outcome = outcome,
    )
    synchronized(lock) {
      buffer.addFirst(entry)
      while (buffer.size > MAX_ENTRIES) {
        buffer.removeLast()
      }
      _entries.value = buffer.toList()
    }
  }

  /** Formats all recorded entries into a plain-text block ready for clipboard sharing. */
  fun formatAllForCopy(): String {
    val snapshot = _entries.value
    if (snapshot.isEmpty()) return "No button events recorded."
    return snapshot.joinToString(separator = "\n") { it.formatLine() }
  }

  /** Clears all in-memory button log entries. */
  fun clear() {
    synchronized(lock) {
      buffer.clear()
      _entries.value = emptyList()
    }
  }

  private fun currentTimestampLabel(): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}

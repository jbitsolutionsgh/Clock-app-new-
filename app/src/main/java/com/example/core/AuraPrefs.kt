package com.example.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single shared SharedPreferences store for all AuraClock settings.
 * Exposes reactive StateFlows so screens update automatically without recreating listeners.
 */
class AuraPrefs(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _themeColor = MutableStateFlow(loadThemeColor())
  val themeColor: StateFlow<AuraThemeColor> = _themeColor.asStateFlow()

  private val _timerDurationSeconds = MutableStateFlow(prefs.getInt(KEY_TIMER_SECONDS, 60).coerceIn(1, 5999))
  val timerDurationSeconds: StateFlow<Int> = _timerDurationSeconds.asStateFlow()

  private val _quickPresets = MutableStateFlow(loadQuickPresets())
  val quickPresets: StateFlow<List<Int>> = _quickPresets.asStateFlow()

  private val _roundsMode = MutableStateFlow(prefs.getBoolean(KEY_ROUNDS_MODE, false))
  val roundsMode: StateFlow<Boolean> = _roundsMode.asStateFlow()

  private val _roundGapSeconds = MutableStateFlow(prefs.getInt(KEY_ROUND_GAP_SECONDS, 1).coerceIn(0, 3))
  val roundGapSeconds: StateFlow<Int> = _roundGapSeconds.asStateFlow()

  private val _turnIndicatorEnabled = MutableStateFlow(prefs.getBoolean(KEY_TURN_ENABLED, false))
  val turnIndicatorEnabled: StateFlow<Boolean> = _turnIndicatorEnabled.asStateFlow()

  private val _turnGroupSize = MutableStateFlow(prefs.getInt(KEY_TURN_GROUP_SIZE, 4).coerceIn(2, 10))
  val turnGroupSize: StateFlow<Int> = _turnGroupSize.asStateFlow()

  private val _beepDurationMs = MutableStateFlow(prefs.getInt(KEY_BEEP_DURATION_MS, 1500).coerceIn(500, 5000))
  val beepDurationMs: StateFlow<Int> = _beepDurationMs.asStateFlow()

  private val _useAlarmStream = MutableStateFlow(prefs.getBoolean(KEY_USE_ALARM_STREAM, true))
  val useAlarmStream: StateFlow<Boolean> = _useAlarmStream.asStateFlow()

  private val _soundUri = MutableStateFlow(prefs.getString(KEY_SOUND_URI, null))
  val soundUri: StateFlow<String?> = _soundUri.asStateFlow()

  private val _soundName = MutableStateFlow(prefs.getString(KEY_SOUND_NAME, "Default Tone") ?: "Default Tone")
  val soundName: StateFlow<String> = _soundName.asStateFlow()

  private val _selectedFaceId = MutableStateFlow(prefs.getString(KEY_SELECTED_FACE, "motorsport_m") ?: "motorsport_m")
  val selectedFaceId: StateFlow<String> = _selectedFaceId.asStateFlow()

  private val _earbudEnabled = MutableStateFlow(prefs.getBoolean(KEY_EARBUD_ENABLED, true))
  val earbudEnabled: StateFlow<Boolean> = _earbudEnabled.asStateFlow()

  private val _playPauseActionId = MutableStateFlow(prefs.getString(KEY_ACTION_PLAY_PAUSE, ACTION_SPEAK_TIME) ?: ACTION_SPEAK_TIME)
  val playPauseActionId: StateFlow<String> = _playPauseActionId.asStateFlow()

  private val _playPauseDoubleActionId = MutableStateFlow(prefs.getString(KEY_ACTION_PLAY_PAUSE_DOUBLE, ACTION_NONE) ?: ACTION_NONE)
  val playPauseDoubleActionId: StateFlow<String> = _playPauseDoubleActionId.asStateFlow()

  private val _nextActionId = MutableStateFlow(prefs.getString(KEY_ACTION_NEXT, ACTION_TIMER_TOGGLE) ?: ACTION_TIMER_TOGGLE)
  val nextActionId: StateFlow<String> = _nextActionId.asStateFlow()

  private val _previousActionId = MutableStateFlow(prefs.getString(KEY_ACTION_PREVIOUS, ACTION_NEXT_FACE) ?: ACTION_NEXT_FACE)
  val previousActionId: StateFlow<String> = _previousActionId.asStateFlow()

  private val _standByUseSelectedFace = MutableStateFlow(prefs.getBoolean(KEY_STANDBY_USE_SELECTED, false))
  val standByUseSelectedFace: StateFlow<Boolean> = _standByUseSelectedFace.asStateFlow()

  private val _standByDimLevel = MutableStateFlow(prefs.getFloat(KEY_STANDBY_DIM, 0.35f).coerceIn(0.1f, 1.0f))
  val standByDimLevel: StateFlow<Float> = _standByDimLevel.asStateFlow()

  private val _standByCalendarEnabled = MutableStateFlow(prefs.getBoolean(KEY_STANDBY_CAL_ENABLED, true))
  val standByCalendarEnabled: StateFlow<Boolean> = _standByCalendarEnabled.asStateFlow()

  private val _selectedCalendarIds = MutableStateFlow(loadSelectedCalendarIds())
  val selectedCalendarIds: StateFlow<Set<Long>> = _selectedCalendarIds.asStateFlow()

  private val _standByMaxEvents = MutableStateFlow(prefs.getInt(KEY_STANDBY_MAX_EVENTS, 4).coerceIn(2, 6))
  val standByMaxEvents: StateFlow<Int> = _standByMaxEvents.asStateFlow()

  private val _standByCalendarWindow = MutableStateFlow(
    StandByCalendarWindow.fromId(prefs.getString(KEY_STANDBY_CAL_WINDOW, StandByCalendarWindow.TODAY_AND_TOMORROW.id)),
  )
  val standByCalendarWindow: StateFlow<StandByCalendarWindow> = _standByCalendarWindow.asStateFlow()

  private val _standByShowEventTitles = MutableStateFlow(prefs.getBoolean(KEY_STANDBY_SHOW_TITLES, true))
  val standByShowEventTitles: StateFlow<Boolean> = _standByShowEventTitles.asStateFlow()

  private fun loadThemeColor(): AuraThemeColor =
    AuraThemeColor.fromId(prefs.getString(KEY_THEME_COLOR, AuraThemeColor.COBALT.id))

  private fun loadQuickPresets(): List<Int> {
    val raw = prefs.getString(KEY_QUICK_PRESETS, null) ?: return DEFAULT_PRESETS
    val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull()?.coerceIn(1, 5999) }
    return if (parsed.size == 5) parsed else DEFAULT_PRESETS
  }

  /** Persists the selected app accent colour. */
  fun setThemeColor(color: AuraThemeColor) {
    prefs.edit().putString(KEY_THEME_COLOR, color.id).apply()
    _themeColor.value = color
  }

  /** Persists the active countdown duration in seconds. */
  fun setTimerDurationSeconds(seconds: Int) {
    val clamped = seconds.coerceIn(1, 5999)
    prefs.edit().putInt(KEY_TIMER_SECONDS, clamped).apply()
    _timerDurationSeconds.value = clamped
  }

  /** Updates a single quick-preset chip slot (0..4) with a new duration in seconds. */
  fun updateQuickPreset(index: Int, seconds: Int) {
    if (index !in 0..4) return
    val updated = _quickPresets.value.toMutableList()
    updated[index] = seconds.coerceIn(1, 5999)
    prefs.edit().putString(KEY_QUICK_PRESETS, updated.joinToString(",")).apply()
    _quickPresets.value = updated
  }

  /** Toggles classroom Rounds mode on or off. */
  fun setRoundsMode(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_ROUNDS_MODE, enabled).apply()
    _roundsMode.value = enabled
  }

  /** Sets the gap wait (0 to 3 seconds) between automatic rounds. */
  fun setRoundGapSeconds(seconds: Int) {
    val clamped = seconds.coerceIn(0, 3)
    prefs.edit().putInt(KEY_ROUND_GAP_SECONDS, clamped).apply()
    _roundGapSeconds.value = clamped
  }

  /** Toggles the optional speaker turn indicator on or off. */
  fun setTurnIndicatorEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_TURN_ENABLED, enabled).apply()
    _turnIndicatorEnabled.value = enabled
  }

  /** Sets the speaker group size (2 to 10) for turn cycling. */
  fun setTurnGroupSize(size: Int) {
    val clamped = size.coerceIn(2, 10)
    prefs.edit().putInt(KEY_TURN_GROUP_SIZE, clamped).apply()
    _turnGroupSize.value = clamped
  }

  /** Sets beep length in milliseconds (500 to 5000 ms). */
  fun setBeepDurationMs(ms: Int) {
    val clamped = ms.coerceIn(500, 5000)
    prefs.edit().putInt(KEY_BEEP_DURATION_MS, clamped).apply()
    _beepDurationMs.value = clamped
  }

  /** Selects alarm audio stream (true) or media audio stream (false) for timer beeps. */
  fun setUseAlarmStream(useAlarm: Boolean) {
    prefs.edit().putBoolean(KEY_USE_ALARM_STREAM, useAlarm).apply()
    _useAlarmStream.value = useAlarm
  }

  /** Persists the selected custom ringtone/audio URI and display name. */
  fun setTimerSound(uriString: String?, displayName: String) {
    prefs.edit()
      .putString(KEY_SOUND_URI, uriString)
      .putString(KEY_SOUND_NAME, displayName)
      .apply()
    _soundUri.value = uriString
    _soundName.value = displayName
  }

  /** Persists the active watch face ID. */
  fun setSelectedFaceId(faceId: String) {
    prefs.edit().putString(KEY_SELECTED_FACE, faceId).apply()
    _selectedFaceId.value = faceId
  }

  /** Persists whether the Bluetooth earbud media listener should run. */
  fun setEarbudEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_EARBUD_ENABLED, enabled).apply()
    _earbudEnabled.value = enabled
  }

  /** Assigns an action ID to single Play/Pause earbud presses. */
  fun setPlayPauseActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_PLAY_PAUSE, actionId).apply()
    _playPauseActionId.value = actionId
  }

  /** Assigns an action ID to double Play/Pause earbud presses ("none" disables delay). */
  fun setPlayPauseDoubleActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_PLAY_PAUSE_DOUBLE, actionId).apply()
    _playPauseDoubleActionId.value = actionId
  }

  /** Assigns an action ID to the Next (held volume up) earbud key. */
  fun setNextActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_NEXT, actionId).apply()
    _nextActionId.value = actionId
  }

  /** Assigns an action ID to the Previous (held volume down) earbud key. */
  fun setPreviousActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_PREVIOUS, actionId).apply()
    _previousActionId.value = actionId
  }

  /** Configures whether the Stand By screensaver mirrors the selected watch face or uses Big Digital. */
  fun setStandByUseSelectedFace(useSelected: Boolean) {
    prefs.edit().putBoolean(KEY_STANDBY_USE_SELECTED, useSelected).apply()
    _standByUseSelectedFace.value = useSelected
  }

  /** Sets the dimmed brightness factor (0.1 to 1.0) for the Stand By screensaver. */
  fun setStandByDimLevel(level: Float) {
    val clamped = level.coerceIn(0.1f, 1.0f)
    prefs.edit().putFloat(KEY_STANDBY_DIM, clamped).apply()
    _standByDimLevel.value = clamped
  }

  private fun loadSelectedCalendarIds(): Set<Long> =
    prefs.getStringSet(KEY_STANDBY_CAL_IDS, emptySet())
      ?.mapNotNull { it.toLongOrNull() }
      ?.toSet()
      ?: emptySet()

  /** Returns true once default Google calendar selection has been initialized. */
  fun hasInitializedCalendarSelection(): Boolean =
    prefs.getBoolean(KEY_STANDBY_CAL_INITIALIZED, false)

  /** Enables or disables the Stand By calendar event list. */
  fun setStandByCalendarEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_STANDBY_CAL_ENABLED, enabled).apply()
    _standByCalendarEnabled.value = enabled
  }

  /** Persists the set of selected device calendar IDs and marks initialization complete. */
  fun setSelectedCalendarIds(ids: Set<Long>) {
    prefs.edit()
      .putStringSet(KEY_STANDBY_CAL_IDS, ids.map { it.toString() }.toSet())
      .putBoolean(KEY_STANDBY_CAL_INITIALIZED, true)
      .apply()
    _selectedCalendarIds.value = ids
  }

  /** Sets the maximum number of calendar events shown on Stand By (2 to 6). */
  fun setStandByMaxEvents(count: Int) {
    val clamped = count.coerceIn(2, 6)
    prefs.edit().putInt(KEY_STANDBY_MAX_EVENTS, clamped).apply()
    _standByMaxEvents.value = clamped
  }

  /** Sets the Stand By calendar lookahead window. */
  fun setStandByCalendarWindow(window: StandByCalendarWindow) {
    prefs.edit().putString(KEY_STANDBY_CAL_WINDOW, window.id).apply()
    _standByCalendarWindow.value = window
  }

  /** Sets whether event titles are shown or hidden behind "Event". */
  fun setStandByShowEventTitles(show: Boolean) {
    prefs.edit().putBoolean(KEY_STANDBY_SHOW_TITLES, show).apply()
    _standByShowEventTitles.value = show
  }

  /** Saves widget configuration for a specific appWidgetId. */
  fun saveWidgetConfig(widgetId: Int, styleId: String, colorId: String) {
    prefs.edit()
      .putString("widget_style_$widgetId", styleId)
      .putString("widget_color_$widgetId", colorId)
      .apply()
  }

  /** Reads widget style ID for a specific appWidgetId. */
  fun getWidgetStyleId(widgetId: Int): String =
    prefs.getString("widget_style_$widgetId", "motorsport_m") ?: "motorsport_m"

  /** Reads widget accent colour ID for a specific appWidgetId. */
  fun getWidgetColorId(widgetId: Int): String =
    prefs.getString("widget_color_$widgetId", AuraThemeColor.COBALT.id) ?: AuraThemeColor.COBALT.id

  /** Removes widget configuration when a widget instance is deleted from the home screen. */
  fun deleteWidgetConfig(widgetId: Int) {
    prefs.edit()
      .remove("widget_style_$widgetId")
      .remove("widget_color_$widgetId")
      .apply()
  }

  companion object {
    private const val PREFS_NAME = "auraclock_prefs"
    private val DEFAULT_PRESETS = listOf(5, 10, 20, 30, 60)

    const val ACTION_NONE = "none"
    const val ACTION_SPEAK_TIME = "speak_time"
    const val ACTION_TIMER_TOGGLE = "timer_toggle"
    const val ACTION_STOPWATCH_TOGGLE = "stopwatch_toggle"
    const val ACTION_NEXT_FACE = "next_face"

    private const val KEY_THEME_COLOR = "theme_color"
    private const val KEY_TIMER_SECONDS = "timer_seconds"
    private const val KEY_QUICK_PRESETS = "quick_presets"
    private const val KEY_ROUNDS_MODE = "rounds_mode"
    private const val KEY_ROUND_GAP_SECONDS = "round_gap_seconds"
    private const val KEY_TURN_ENABLED = "turn_enabled"
    private const val KEY_TURN_GROUP_SIZE = "turn_group_size"
    private const val KEY_BEEP_DURATION_MS = "beep_duration_ms"
    private const val KEY_USE_ALARM_STREAM = "use_alarm_stream"
    private const val KEY_SOUND_URI = "sound_uri"
    private const val KEY_SOUND_NAME = "sound_name"
    private const val KEY_SELECTED_FACE = "selected_face"
    private const val KEY_EARBUD_ENABLED = "earbud_enabled"
    private const val KEY_ACTION_PLAY_PAUSE = "action_play_pause"
    private const val KEY_ACTION_PLAY_PAUSE_DOUBLE = "action_play_pause_double"
    private const val KEY_ACTION_NEXT = "action_next"
    private const val KEY_ACTION_PREVIOUS = "action_previous"
    private const val KEY_STANDBY_USE_SELECTED = "standby_use_selected"
    private const val KEY_STANDBY_DIM = "standby_dim"
    private const val KEY_STANDBY_CAL_ENABLED = "standby_cal_enabled"
    private const val KEY_STANDBY_CAL_IDS = "standby_cal_ids"
    private const val KEY_STANDBY_CAL_INITIALIZED = "standby_cal_initialized"
    private const val KEY_STANDBY_MAX_EVENTS = "standby_max_events"
    private const val KEY_STANDBY_CAL_WINDOW = "standby_cal_window"
    private const val KEY_STANDBY_SHOW_TITLES = "standby_show_titles"
  }
}

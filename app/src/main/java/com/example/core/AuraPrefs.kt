package com.example.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single shared SharedPreferences store for all AuraClock settings.
 * Exposes reactive StateFlows so screens update automatically without recreating listeners.
 */
class AuraPrefs(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _themeColor = MutableStateFlow(loadThemeColor())
  val themeColor: StateFlow<AuraThemeColor> = _themeColor.asStateFlow()

  private val _timerDurationSeconds = MutableStateFlow(safeInt(KEY_TIMER_SECONDS, 60).coerceIn(1, 5999))
  val timerDurationSeconds: StateFlow<Int> = _timerDurationSeconds.asStateFlow()

  private val _quickPresets = MutableStateFlow(loadQuickPresets())
  val quickPresets: StateFlow<List<Int>> = _quickPresets.asStateFlow()

  private val _roundsMode = MutableStateFlow(safeBoolean(KEY_ROUNDS_MODE, false))
  val roundsMode: StateFlow<Boolean> = _roundsMode.asStateFlow()

  private val _roundGapSeconds = MutableStateFlow(safeInt(KEY_ROUND_GAP_SECONDS, 1).coerceIn(0, 3))
  val roundGapSeconds: StateFlow<Int> = _roundGapSeconds.asStateFlow()

  private val _turnIndicatorEnabled = MutableStateFlow(safeBoolean(KEY_TURN_ENABLED, false))
  val turnIndicatorEnabled: StateFlow<Boolean> = _turnIndicatorEnabled.asStateFlow()

  private val _turnGroupSize = MutableStateFlow(safeInt(KEY_TURN_GROUP_SIZE, 4).coerceIn(2, 10))
  val turnGroupSize: StateFlow<Int> = _turnGroupSize.asStateFlow()

  private val _beepDurationMs = MutableStateFlow(safeInt(KEY_BEEP_DURATION_MS, 1500).coerceIn(500, 5000))
  val beepDurationMs: StateFlow<Int> = _beepDurationMs.asStateFlow()

  private val _useAlarmStream = MutableStateFlow(safeBoolean(KEY_USE_ALARM_STREAM, true))
  val useAlarmStream: StateFlow<Boolean> = _useAlarmStream.asStateFlow()

  private val _soundUri = MutableStateFlow(safeString(KEY_SOUND_URI, null))
  val soundUri: StateFlow<String?> = _soundUri.asStateFlow()

  private val _soundName = MutableStateFlow(safeString(KEY_SOUND_NAME, "Default Tone") ?: "Default Tone")
  val soundName: StateFlow<String> = _soundName.asStateFlow()

  private val _selectedFaceId = MutableStateFlow(safeString(KEY_SELECTED_FACE, "motorsport_m") ?: "motorsport_m")
  val selectedFaceId: StateFlow<String> = _selectedFaceId.asStateFlow()

  private val _earbudEnabled = MutableStateFlow(safeBoolean(KEY_EARBUD_ENABLED, true))
  val earbudEnabled: StateFlow<Boolean> = _earbudEnabled.asStateFlow()

  private val _earbudTakeOverDuringAudio = MutableStateFlow(safeBoolean(KEY_EARBUD_TAKE_OVER_AUDIO, true))
  val earbudTakeOverDuringAudio: StateFlow<Boolean> = _earbudTakeOverDuringAudio.asStateFlow()

  private val _playPauseActionId = MutableStateFlow(
    safeString(KEY_ACTION_PLAY_PAUSE, ACTION_FORWARD_TO_MUSIC) ?: ACTION_FORWARD_TO_MUSIC,
  )
  val playPauseActionId: StateFlow<String> = _playPauseActionId.asStateFlow()

  private val _playPauseDoubleActionId = MutableStateFlow(
    safeString(KEY_ACTION_PLAY_PAUSE_DOUBLE, ACTION_NONE) ?: ACTION_NONE,
  )
  val playPauseDoubleActionId: StateFlow<String> = _playPauseDoubleActionId.asStateFlow()

  private val _playPauseTripleActionId = MutableStateFlow(
    safeString(KEY_ACTION_PLAY_PAUSE_TRIPLE, ACTION_SPEAK_TIME) ?: ACTION_SPEAK_TIME,
  )
  val playPauseTripleActionId: StateFlow<String> = _playPauseTripleActionId.asStateFlow()

  private val _earbudPressWindowMs = MutableStateFlow(safeInt(KEY_EARBUD_PRESS_WINDOW_MS, 400).coerceIn(300, 600))
  val earbudPressWindowMs: StateFlow<Int> = _earbudPressWindowMs.asStateFlow()

  private val _learnedPatterns = MutableStateFlow(loadLearnedPatterns())
  val learnedPatterns: StateFlow<List<LearnedPressPattern>> = _learnedPatterns.asStateFlow()

  private val _nextActionId = MutableStateFlow(safeString(KEY_ACTION_NEXT, ACTION_TIMER_TOGGLE) ?: ACTION_TIMER_TOGGLE)
  val nextActionId: StateFlow<String> = _nextActionId.asStateFlow()

  private val _previousActionId = MutableStateFlow(safeString(KEY_ACTION_PREVIOUS, ACTION_NEXT_FACE) ?: ACTION_NEXT_FACE)
  val previousActionId: StateFlow<String> = _previousActionId.asStateFlow()

  private val _standByUseSelectedFace = MutableStateFlow(safeBoolean(KEY_STANDBY_USE_SELECTED, false))
  val standByUseSelectedFace: StateFlow<Boolean> = _standByUseSelectedFace.asStateFlow()

  private val _standByDimLevel = MutableStateFlow(safeFloat(KEY_STANDBY_DIM, 0.35f).coerceIn(0.1f, 1.0f))
  val standByDimLevel: StateFlow<Float> = _standByDimLevel.asStateFlow()

  private val _standByCalendarEnabled = MutableStateFlow(safeBoolean(KEY_STANDBY_CAL_ENABLED, true))
  val standByCalendarEnabled: StateFlow<Boolean> = _standByCalendarEnabled.asStateFlow()

  private val _selectedCalendarIds = MutableStateFlow(loadSelectedCalendarIds())
  val selectedCalendarIds: StateFlow<Set<Long>> = _selectedCalendarIds.asStateFlow()

  private val _standByMaxEvents = MutableStateFlow(safeInt(KEY_STANDBY_MAX_EVENTS, 4).coerceIn(2, 6))
  val standByMaxEvents: StateFlow<Int> = _standByMaxEvents.asStateFlow()

  private val _standByCalendarWindow = MutableStateFlow(
    StandByCalendarWindow.fromId(safeString(KEY_STANDBY_CAL_WINDOW, StandByCalendarWindow.TODAY_AND_TOMORROW.id)),
  )
  val standByCalendarWindow: StateFlow<StandByCalendarWindow> = _standByCalendarWindow.asStateFlow()

  private val _standByShowEventTitles = MutableStateFlow(safeBoolean(KEY_STANDBY_SHOW_TITLES, true))
  val standByShowEventTitles: StateFlow<Boolean> = _standByShowEventTitles.asStateFlow()

  private val _timerFullScreenByDefault = MutableStateFlow(safeBoolean(KEY_TIMER_FULLSCREEN_DEFAULT, false))
  val timerFullScreenByDefault: StateFlow<Boolean> = _timerFullScreenByDefault.asStateFlow()

  private val _timerGoFullScreenOnStart = MutableStateFlow(safeBoolean(KEY_TIMER_FULLSCREEN_ON_START, false))
  val timerGoFullScreenOnStart: StateFlow<Boolean> = _timerGoFullScreenOnStart.asStateFlow()

  private val _stepGoal = MutableStateFlow(safeInt(KEY_STEP_GOAL, 10_000).coerceIn(1_000, 100_000))
  val stepGoal: StateFlow<Int> = _stepGoal.asStateFlow()

  private val _lastDailySteps = MutableStateFlow(safeInt(KEY_STEP_LAST_DAILY, 0).coerceAtLeast(0))
  val lastDailySteps: StateFlow<Int> = _lastDailySteps.asStateFlow()

  private fun safeInt(key: String, default: Int): Int =
    runCatching { prefs.getInt(key, default) }.getOrDefault(default)

  private fun safeFloat(key: String, default: Float): Float =
    runCatching { prefs.getFloat(key, default) }.getOrDefault(default)

  private fun safeBoolean(key: String, default: Boolean): Boolean =
    runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

  private fun safeString(key: String, default: String?): String? =
    runCatching { prefs.getString(key, default) }.getOrDefault(default)

  private fun safeStringSet(key: String): Set<String>? =
    runCatching { prefs.getStringSet(key, emptySet()) }.getOrNull()

  private fun loadThemeColor(): AuraThemeColor =
    AuraThemeColor.fromId(safeString(KEY_THEME_COLOR, AuraThemeColor.COBALT.id))

  private fun loadQuickPresets(): List<Int> {
    val raw = safeString(KEY_QUICK_PRESETS, null) ?: return DEFAULT_PRESETS
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

  /**
   * Configures whether AuraClock re-primes media buttons when another app plays audio (true),
   * or lets the music app keep media buttons (false).
   */
  fun setEarbudTakeOverDuringAudio(takeOver: Boolean) {
    prefs.edit().putBoolean(KEY_EARBUD_TAKE_OVER_AUDIO, takeOver).apply()
    _earbudTakeOverDuringAudio.value = takeOver
  }

  /** Assigns an action ID to single Play/Pause earbud presses. */
  fun setPlayPauseActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_PLAY_PAUSE, actionId).apply()
    _playPauseActionId.value = actionId
  }

  /** Assigns an action ID to double Play/Pause earbud presses. */
  fun setPlayPauseDoubleActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_PLAY_PAUSE_DOUBLE, actionId).apply()
    _playPauseDoubleActionId.value = actionId
  }

  /** Assigns an action ID to triple Play/Pause earbud presses. */
  fun setPlayPauseTripleActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_PLAY_PAUSE_TRIPLE, actionId).apply()
    _playPauseTripleActionId.value = actionId
  }

  /** Sets the multi-press detection window in milliseconds (300 to 600 ms, default 400 ms). */
  fun setEarbudPressWindowMs(windowMs: Int) {
    val clamped = windowMs.coerceIn(300, 600)
    prefs.edit().putInt(KEY_EARBUD_PRESS_WINDOW_MS, clamped).apply()
    _earbudPressWindowMs.value = clamped
  }

  /** Saves or updates a learned earbud press pattern keyed by (keyCode, count). */
  fun saveLearnedPattern(pattern: LearnedPressPattern) {
    val updated = _learnedPatterns.value
      .filterNot { it.keyCode == pattern.keyCode && it.count == pattern.count } + pattern
    persistLearnedPatterns(updated)
  }

  /** Removes a previously learned earbud press pattern matching (keyCode, count). */
  fun removeLearnedPattern(keyCode: Int, count: Int) {
    val updated = _learnedPatterns.value
      .filterNot { it.keyCode == keyCode && it.count == count }
    persistLearnedPatterns(updated)
  }

  private fun persistLearnedPatterns(patterns: List<LearnedPressPattern>) {
    val arr = JSONArray()
    patterns.forEach { p ->
      arr.put(
        JSONObject().apply {
          put("keyCode", p.keyCode)
          put("count", p.count.coerceIn(1, 4))
          put("actionId", p.actionId)
          put("keyLabel", p.keyLabel)
        },
      )
    }
    prefs.edit().putString(KEY_LEARNED_PATTERNS, arr.toString()).apply()
    _learnedPatterns.value = patterns
  }

  private fun loadLearnedPatterns(): List<LearnedPressPattern> {
    val raw = safeString(KEY_LEARNED_PATTERNS, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val keyCode = obj.optInt("keyCode", 0)
          val count = obj.optInt("count", 1).coerceIn(1, 4)
          val actionId = obj.optString("actionId", ACTION_NONE)
          val keyLabel = obj.optString("keyLabel", "Media Key")
          if (keyCode != 0) {
            add(LearnedPressPattern(keyCode = keyCode, count = count, actionId = actionId, keyLabel = keyLabel))
          }
        }
      }
    }.getOrDefault(emptyList())
  }

  /** Assigns an action ID to the MEDIA_NEXT earbud key. */
  fun setNextActionId(actionId: String) {
    prefs.edit().putString(KEY_ACTION_NEXT, actionId).apply()
    _nextActionId.value = actionId
  }

  /** Assigns an action ID to the MEDIA_PREVIOUS earbud key. */
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
    safeStringSet(KEY_STANDBY_CAL_IDS)
      ?.mapNotNull { it.toLongOrNull() }
      ?.toSet()
      ?: emptySet()

  /** Returns true once a non-empty calendar selection has been initialized. */
  fun hasInitializedCalendarSelection(): Boolean =
    safeBoolean(KEY_STANDBY_CAL_INITIALIZED, false)

  /** Returns true if the user manually edited calendar checkboxes in Settings. */
  fun hasUserEditedCalendarSelection(): Boolean =
    safeBoolean(KEY_STANDBY_CAL_USER_EDITED, false)

  /** Enables or disables the Stand By calendar event list. */
  fun setStandByCalendarEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_STANDBY_CAL_ENABLED, enabled).apply()
    _standByCalendarEnabled.value = enabled
  }

  /**
   * Persists the set of selected device calendar IDs.
   * Never marks an empty selection as initialized so default selection re-runs when calendars exist.
   */
  fun setSelectedCalendarIds(ids: Set<Long>, userEdited: Boolean = false) {
    val editor = prefs.edit()
      .putStringSet(KEY_STANDBY_CAL_IDS, ids.map { it.toString() }.toSet())
      .putBoolean(KEY_STANDBY_CAL_INITIALIZED, ids.isNotEmpty())
    if (userEdited) {
      editor.putBoolean(KEY_STANDBY_CAL_USER_EDITED, true)
    }
    editor.apply()
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

  /** Sets whether the Timer tab opens in full-screen mode by default. */
  fun setTimerFullScreenByDefault(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_TIMER_FULLSCREEN_DEFAULT, enabled).apply()
    _timerFullScreenByDefault.value = enabled
  }

  /** Sets whether starting the timer automatically switches to full-screen mode. */
  fun setTimerGoFullScreenOnStart(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_TIMER_FULLSCREEN_ON_START, enabled).apply()
    _timerGoFullScreenOnStart.value = enabled
  }

  /** Reads saved (dayKey, bootCumulativeBaseline) for daily step computation. */
  fun readStepBaseline(): Pair<Int, Float> =
    safeInt(KEY_STEP_BASELINE_DAY, -1) to safeFloat(KEY_STEP_BASELINE_VAL, -1f)

  /** Persists updated daily step baseline and current daily step total. */
  fun saveStepState(dayKey: Int, baseline: Float, dailySteps: Int) {
    prefs.edit()
      .putInt(KEY_STEP_BASELINE_DAY, dayKey)
      .putFloat(KEY_STEP_BASELINE_VAL, baseline)
      .putInt(KEY_STEP_LAST_DAILY, dailySteps.coerceAtLeast(0))
      .apply()
    _lastDailySteps.value = dailySteps.coerceAtLeast(0)
  }

  private val _wffFaceConfigs = MutableStateFlow(loadAllWffConfigs())
  val wffFaceConfigs: StateFlow<Map<String, Map<String, String>>> = _wffFaceConfigs.asStateFlow()

  private val _wffComplicationProviders = MutableStateFlow(loadPrefixedTwoPartMap(KEY_WFF_COMP_PREFIX))
  val wffComplicationProviders: StateFlow<Map<String, Map<String, String>>> = _wffComplicationProviders.asStateFlow()

  private val _wffSelectedFlavors = MutableStateFlow(loadPrefixedOnePartMap(KEY_WFF_FLAVOR_PREFIX))
  val wffSelectedFlavors: StateFlow<Map<String, String>> = _wffSelectedFlavors.asStateFlow()

  private val _wffFacePhotos = MutableStateFlow(loadAllWffPhotos())
  val wffFacePhotos: StateFlow<Map<String, Map<String, List<String>>>> = _wffFacePhotos.asStateFlow()

  private val _faceDigitalFonts = MutableStateFlow(loadPrefixedOnePartMap(KEY_FACE_FONT_PREFIX))
  val faceDigitalFonts: StateFlow<Map<String, String>> = _faceDigitalFonts.asStateFlow()

  private fun loadAllWffConfigs(): Map<String, Map<String, String>> =
    loadPrefixedTwoPartMap(KEY_WFF_CONFIG_PREFIX)

  private fun loadPrefixedTwoPartMap(keyPrefix: String): Map<String, Map<String, String>> {
    val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
    for ((rawKey, rawVal) in prefs.all) {
      if (!rawKey.startsWith(keyPrefix)) continue
      val remainder = rawKey.removePrefix(keyPrefix)
      val sep = remainder.indexOf("::")
      if (sep <= 0) continue
      val faceId = remainder.substring(0, sep)
      val subId = remainder.substring(sep + 2)
      val value = rawVal as? String ?: continue
      result.getOrPut(faceId) { LinkedHashMap() }[subId] = value
    }
    return result
  }

  private fun loadPrefixedOnePartMap(keyPrefix: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for ((rawKey, rawVal) in prefs.all) {
      if (!rawKey.startsWith(keyPrefix)) continue
      val faceId = rawKey.removePrefix(keyPrefix)
      val value = rawVal as? String ?: continue
      result[faceId] = value
    }
    return result
  }

  private fun loadAllWffPhotos(): Map<String, Map<String, List<String>>> {
    val rawMap = loadPrefixedTwoPartMap(KEY_WFF_PHOTOS_PREFIX)
    return rawMap.mapValues { (_, configs) ->
      configs.mapValues { (_, joined) ->
        joined.split('|').map { it.trim() }.filter { it.isNotEmpty() }
      }
    }
  }

  /** Saves a single UserConfiguration choice for an imported WFF watch face. */
  fun setWffConfigValue(faceId: String, configId: String, value: String) {
    prefs.edit().putString("$KEY_WFF_CONFIG_PREFIX$faceId::$configId", value).apply()
    val updated = _wffFaceConfigs.value.toMutableMap()
    val faceMap = updated[faceId]?.toMutableMap() ?: LinkedHashMap()
    faceMap[configId] = value
    updated[faceId] = faceMap
    _wffFaceConfigs.value = updated
  }

  /** Returns all saved UserConfiguration values for [faceId]. */
  fun getWffFaceConfig(faceId: String): Map<String, String> =
    _wffFaceConfigs.value[faceId].orEmpty()

  /** Saves the chosen AuraClock provider ID for a ComplicationSlot on [faceId]. */
  fun setWffComplicationProvider(faceId: String, slotId: String, providerId: String) {
    prefs.edit().putString("$KEY_WFF_COMP_PREFIX$faceId::$slotId", providerId).apply()
    val updated = _wffComplicationProviders.value.toMutableMap()
    val faceMap = updated[faceId]?.toMutableMap() ?: LinkedHashMap()
    faceMap[slotId] = providerId
    updated[faceId] = faceMap
    _wffComplicationProviders.value = updated
  }

  /** Returns saved ComplicationSlot provider IDs keyed by slotId for [faceId]. */
  fun getWffComplicationProviders(faceId: String): Map<String, String> =
    _wffComplicationProviders.value[faceId].orEmpty()

  /** Persists the currently active Flavor preset ID for [faceId]. */
  fun setWffSelectedFlavor(faceId: String, flavorId: String) {
    prefs.edit().putString("$KEY_WFF_FLAVOR_PREFIX$faceId", flavorId).apply()
    val updated = _wffSelectedFlavors.value.toMutableMap()
    updated[faceId] = flavorId
    _wffSelectedFlavors.value = updated
  }

  /** Saves ordered private photo filenames chosen for [configId] on [faceId]. */
  fun setWffPhotos(faceId: String, configId: String, fileNames: List<String>) {
    prefs.edit().putString("$KEY_WFF_PHOTOS_PREFIX$faceId::$configId", fileNames.joinToString("|")).apply()
    val updated = _wffFacePhotos.value.toMutableMap()
    val faceMap = updated[faceId]?.toMutableMap() ?: LinkedHashMap()
    if (fileNames.isEmpty()) faceMap.remove(configId) else faceMap[configId] = fileNames
    updated[faceId] = faceMap
    _wffFacePhotos.value = updated
  }

  /** Returns saved private photo filenames per PhotosConfiguration ID for [faceId]. */
  fun getWffPhotos(faceId: String): Map<String, List<String>> =
    _wffFacePhotos.value[faceId].orEmpty()

  /** Removes all saved WFF settings, complications, flavors, and photo refs when deleting [faceId]. */
  fun deleteWffFaceConfigs(faceId: String) {
    val prefixes = listOf(
      "$KEY_WFF_CONFIG_PREFIX$faceId::",
      "$KEY_WFF_COMP_PREFIX$faceId::",
      "$KEY_WFF_PHOTOS_PREFIX$faceId::",
    )
    val flavorKey = "$KEY_WFF_FLAVOR_PREFIX$faceId"
    val editor = prefs.edit()
    var removedAny = false
    for (k in prefs.all.keys) {
      if (k == flavorKey || prefixes.any { k.startsWith(it) }) {
        editor.remove(k)
        removedAny = true
      }
    }
    if (removedAny) {
      editor.apply()
      _wffFaceConfigs.value = _wffFaceConfigs.value.toMutableMap().apply { remove(faceId) }
      _wffComplicationProviders.value = _wffComplicationProviders.value.toMutableMap().apply { remove(faceId) }
      _wffSelectedFlavors.value = _wffSelectedFlavors.value.toMutableMap().apply { remove(faceId) }
      _wffFacePhotos.value = _wffFacePhotos.value.toMutableMap().apply { remove(faceId) }
    }
  }

  /** Returns the selected imported custom font ID for [faceId], or null when Default is used. */
  fun getFaceDigitalFontId(faceId: String): String? =
    _faceDigitalFonts.value[faceId]?.takeIf { it.isNotBlank() && it != FONT_DEFAULT_ID }

  /** Sets or clears (via null/"default") the imported digital font ID for [faceId]. */
  fun setFaceDigitalFontId(faceId: String, fontId: String?) {
    val normalized = fontId?.trim()?.takeIf { it.isNotEmpty() && it != FONT_DEFAULT_ID }
    val key = "$KEY_FACE_FONT_PREFIX$faceId"
    val updated = _faceDigitalFonts.value.toMutableMap()
    if (normalized == null) {
      prefs.edit().remove(key).apply()
      updated.remove(faceId)
    } else {
      prefs.edit().putString(key, normalized).apply()
      updated[faceId] = normalized
    }
    _faceDigitalFonts.value = updated
  }

  /** Removes any saved per-face references to a deleted custom font [fontId]. */
  fun clearFontReferences(fontId: String) {
    val matchingFaces = _faceDigitalFonts.value.filterValues { it == fontId }.keys.toList()
    if (matchingFaces.isEmpty()) return
    val editor = prefs.edit()
    val updated = _faceDigitalFonts.value.toMutableMap()
    matchingFaces.forEach { faceId ->
      editor.remove("$KEY_FACE_FONT_PREFIX$faceId")
      updated.remove(faceId)
    }
    editor.apply()
    _faceDigitalFonts.value = updated
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
    safeString("widget_style_$widgetId", "motorsport_m") ?: "motorsport_m"

  /** Reads widget accent colour ID for a specific appWidgetId. */
  fun getWidgetColorId(widgetId: Int): String =
    safeString("widget_color_$widgetId", AuraThemeColor.COBALT.id) ?: AuraThemeColor.COBALT.id

  /** Removes widget configuration when a widget instance is deleted from the home screen. */
  fun deleteWidgetConfig(widgetId: Int) {
    prefs.edit()
      .remove("widget_style_$widgetId")
      .remove("widget_color_$widgetId")
      .apply()
  }

  /** Synchronously wipes all persisted preferences and resets reactive StateFlows to clean defaults. */
  fun clearAndReset() {
    prefs.edit().clear().commit()
    _themeColor.value = AuraThemeColor.COBALT
    _timerDurationSeconds.value = 60
    _quickPresets.value = DEFAULT_PRESETS
    _roundsMode.value = false
    _roundGapSeconds.value = 1
    _turnIndicatorEnabled.value = false
    _turnGroupSize.value = 4
    _beepDurationMs.value = 1500
    _useAlarmStream.value = true
    _soundUri.value = null
    _soundName.value = "Default Tone"
    _selectedFaceId.value = "motorsport_m"
    _earbudEnabled.value = true
    _earbudTakeOverDuringAudio.value = true
    _playPauseActionId.value = ACTION_FORWARD_TO_MUSIC
    _playPauseDoubleActionId.value = ACTION_NONE
    _playPauseTripleActionId.value = ACTION_SPEAK_TIME
    _earbudPressWindowMs.value = 400
    _learnedPatterns.value = emptyList()
    _nextActionId.value = ACTION_TIMER_TOGGLE
    _previousActionId.value = ACTION_NEXT_FACE
    _standByUseSelectedFace.value = false
    _standByDimLevel.value = 0.35f
    _standByCalendarEnabled.value = true
    _selectedCalendarIds.value = emptySet()
    _standByMaxEvents.value = 4
    _standByCalendarWindow.value = StandByCalendarWindow.TODAY_AND_TOMORROW
    _standByShowEventTitles.value = true
    _timerFullScreenByDefault.value = false
    _timerGoFullScreenOnStart.value = false
    _stepGoal.value = 10_000
    _lastDailySteps.value = 0
    _wffFaceConfigs.value = emptyMap()
    _wffComplicationProviders.value = emptyMap()
    _wffSelectedFlavors.value = emptyMap()
    _wffFacePhotos.value = emptyMap()
    _faceDigitalFonts.value = emptyMap()
  }

  companion object {
    private const val PREFS_NAME = "auraclock_prefs"
    private val DEFAULT_PRESETS = listOf(5, 10, 20, 30, 60)

    const val ACTION_NONE = "none"
    const val ACTION_FORWARD_TO_MUSIC = "forward_to_music"
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
    private const val KEY_EARBUD_TAKE_OVER_AUDIO = "earbud_take_over_audio"
    private const val KEY_ACTION_PLAY_PAUSE = "action_play_pause"
    private const val KEY_ACTION_PLAY_PAUSE_DOUBLE = "action_play_pause_double"
    private const val KEY_ACTION_PLAY_PAUSE_TRIPLE = "action_play_pause_triple"
    private const val KEY_EARBUD_PRESS_WINDOW_MS = "earbud_press_window_ms"
    private const val KEY_LEARNED_PATTERNS = "earbud_learned_patterns"
    private const val KEY_ACTION_NEXT = "action_next"
    private const val KEY_ACTION_PREVIOUS = "action_previous"
    private const val KEY_STANDBY_USE_SELECTED = "standby_use_selected"
    private const val KEY_STANDBY_DIM = "standby_dim"
    private const val KEY_STANDBY_CAL_ENABLED = "standby_cal_enabled"
    private const val KEY_STANDBY_CAL_IDS = "standby_cal_ids"
    private const val KEY_STANDBY_CAL_INITIALIZED = "standby_cal_initialized"
    private const val KEY_STANDBY_CAL_USER_EDITED = "standby_cal_user_edited"
    private const val KEY_STANDBY_MAX_EVENTS = "standby_max_events"
    private const val KEY_STANDBY_CAL_WINDOW = "standby_cal_window"
    private const val KEY_STANDBY_SHOW_TITLES = "standby_show_titles"
    private const val KEY_TIMER_FULLSCREEN_DEFAULT = "timer_fullscreen_default"
    private const val KEY_TIMER_FULLSCREEN_ON_START = "timer_fullscreen_on_start"
    private const val KEY_WFF_CONFIG_PREFIX = "wff_cfg_"
    private const val KEY_WFF_COMP_PREFIX = "wff_comp_"
    private const val KEY_WFF_FLAVOR_PREFIX = "wff_flavor_"
    private const val KEY_WFF_PHOTOS_PREFIX = "wff_photos_"
    private const val KEY_STEP_GOAL = "step_goal"
    private const val KEY_STEP_BASELINE_DAY = "step_baseline_day"
    private const val KEY_STEP_BASELINE_VAL = "step_baseline_val"
    private const val KEY_STEP_LAST_DAILY = "step_last_daily"
    private const val KEY_FACE_FONT_PREFIX = "face_font_"
    const val FONT_DEFAULT_ID = "default"
  }
}

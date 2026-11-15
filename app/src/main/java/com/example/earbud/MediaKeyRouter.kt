package com.example.earbud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.example.core.AuraPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Routes Bluetooth/wired media key events to the user's configured EarbudAction.
 * Fires immediately on first press with 0 ms delay unless a double-press action is set (max 250 ms).
 */
internal class MediaKeyRouter(
  private val context: Context,
  private val prefs: AuraPrefs,
  private val onKeyDiagnostics: (keyLabel: String, timeLabel: String, actionLabel: String) -> Unit,
) {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private var pendingSinglePress: Runnable? = null
  private var lastHandledKeyTimeMs: Long = 0L

  /** Handles a raw Android media KeyEvent, returning true if consumed. */
  fun handleKeyEvent(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
      return isSupportedMediaKey(event.keyCode)
    }
    return routeKeyCode(event.keyCode)
  }

  /** Routes a media key code directly (from KeyEvent or MediaSession transport fallback). */
  fun routeKeyCode(keyCode: Int, allowDedup: Boolean = false): Boolean {
    val nowMs = System.currentTimeMillis()
    if (allowDedup && nowMs - lastHandledKeyTimeMs < 120L) {
      return true
    }
    lastHandledKeyTimeMs = nowMs

    val keyLabel = "${readableKeyName(keyCode)} ($keyCode)"
    val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs))

    return when (keyCode) {
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE,
      KeyEvent.KEYCODE_HEADSETHOOK -> {
        handlePlayPausePress(keyLabel, timeLabel)
        true
      }
      KeyEvent.KEYCODE_MEDIA_NEXT -> {
        executeAction(prefs.nextActionId.value, keyLabel, timeLabel)
        true
      }
      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
        executeAction(prefs.previousActionId.value, keyLabel, timeLabel)
        true
      }
      else -> false
    }
  }

  private fun handlePlayPausePress(keyLabel: String, timeLabel: String) {
    val doubleActionId = prefs.playPauseDoubleActionId.value
    if (doubleActionId == AuraPrefs.ACTION_NONE) {
      // No double-press configured: execute immediately on first press with zero wait.
      executeAction(prefs.playPauseActionId.value, keyLabel, timeLabel)
      return
    }

    val pending = pendingSinglePress
    if (pending != null) {
      mainHandler.removeCallbacks(pending)
      pendingSinglePress = null
      executeAction(doubleActionId, keyLabel, timeLabel)
    } else {
      val runnable = Runnable {
        pendingSinglePress = null
        executeAction(prefs.playPauseActionId.value, keyLabel, timeLabel)
      }
      pendingSinglePress = runnable
      mainHandler.postDelayed(runnable, DOUBLE_PRESS_WINDOW_MS)
    }
  }

  private fun executeAction(actionId: String, keyLabel: String, timeLabel: String) {
    val action = EarbudActionRegistry.findById(actionId)
    onKeyDiagnostics(keyLabel, timeLabel, action.label)
    action.execute(appContext)
  }

  private fun readableKeyName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
    KeyEvent.KEYCODE_MEDIA_PLAY -> "KEYCODE_MEDIA_PLAY"
    KeyEvent.KEYCODE_MEDIA_PAUSE -> "KEYCODE_MEDIA_PAUSE"
    KeyEvent.KEYCODE_HEADSETHOOK -> "KEYCODE_HEADSETHOOK"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "KEYCODE_MEDIA_NEXT"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
    else -> KeyEvent.keyCodeToString(keyCode)
  }

  private fun isSupportedMediaKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
      keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
      keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
      keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
      keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
      keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

  companion object {
    private const val DOUBLE_PRESS_WINDOW_MS = 250L
  }
}

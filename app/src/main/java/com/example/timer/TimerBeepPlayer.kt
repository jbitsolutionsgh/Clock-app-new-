package com.example.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.core.AuraPrefs

/**
 * Plays short, loud classroom timer beeps on either the alarm or media stream.
 * Guarantees playback stops after the configured duration (0.5 to 5 s) and
 * falls back to the system default tone if a custom file is missing.
 */
internal class TimerBeepPlayer(
  private val context: Context,
  private val prefs: AuraPrefs,
) {
  private val appContext = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private var mediaPlayer: MediaPlayer? = null
  private var toneGenerator: ToneGenerator? = null
  private var stopRunnable: Runnable? = null

  /** Starts playing the configured sound for beepDurationMs and calls onFinished when stopped. */
  fun playBeep(onFinished: () -> Unit = {}) {
    stopBeep()
    val durationMs = prefs.beepDurationMs.value.toLong().coerceIn(500L, 5000L)
    val useAlarm = prefs.useAlarmStream.value
    val customUri = prefs.soundUri.value?.let { runCatching { Uri.parse(it) }.getOrNull() }

    val started = tryPlayUri(customUri, useAlarm) || tryPlayUri(defaultToneUri(), useAlarm)
    if (!started) {
      playToneGeneratorFallback(useAlarm, durationMs.toInt())
    }

    val stopTask = Runnable {
      stopBeep()
      onFinished()
    }
    stopRunnable = stopTask
    handler.postDelayed(stopTask, durationMs)
  }

  /** Stops any currently playing beep immediately. */
  fun stopBeep() {
    stopRunnable?.let { handler.removeCallbacks(it) }
    stopRunnable = null
    runCatching {
      mediaPlayer?.let { player ->
        if (player.isPlaying) player.stop()
        player.reset()
        player.release()
      }
    }
    mediaPlayer = null
    runCatching {
      toneGenerator?.stopTone()
      toneGenerator?.release()
    }
    toneGenerator = null
  }

  private fun tryPlayUri(uri: Uri?, useAlarm: Boolean): Boolean {
    if (uri == null) return false
    return runCatching {
      val attrs = AudioAttributes.Builder()
        .setUsage(if (useAlarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

      val player = MediaPlayer().apply {
        setAudioAttributes(attrs)
        setDataSource(appContext, uri)
        isLooping = true
        prepare()
        start()
      }
      mediaPlayer = player
      true
    }.getOrElse {
      runCatching { mediaPlayer?.release() }
      mediaPlayer = null
      false
    }
  }

  private fun defaultToneUri(): Uri? =
    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

  private fun playToneGeneratorFallback(useAlarm: Boolean, durationMs: Int) {
    runCatching {
      val stream = if (useAlarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
      val tg = ToneGenerator(stream, 100)
      toneGenerator = tg
      tg.startTone(ToneGenerator.TONE_PROP_BEEP2, durationMs)
    }
  }
}

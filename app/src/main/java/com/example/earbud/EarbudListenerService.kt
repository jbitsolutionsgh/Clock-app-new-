package com.example.earbud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.core.CoreSingletons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground MediaSession listener service that receives Bluetooth earbud button presses
 * and restarts via START_STICKY / onTaskRemoved if stopped by the system.
 */
class EarbudListenerService : Service() {

  private var mediaSession: MediaSession? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
  private var pendingReprimeRunnable: Runnable? = null
  private var lastOwnPrimeElapsedMs: Long = -1L
  private var lastReprimeElapsedMs: Long = -1L
  private var isDestroyed: Boolean = false

  override fun onCreate() {
    super.onCreate()
    isDestroyed = false
    ensureChannel()
    detectInitialExternalPlayback()
    setupMediaSession()
    registerPlaybackCallback()
    EarbudChiplet.updateServiceRunning(true)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val prefs = CoreSingletons.prefs(this)
    if (!prefs.earbudEnabled.value) {
      stopSelf()
      return START_NOT_STICKY
    }

    startInForeground(buildNotification())

    if (intent?.action == ACTION_FORWARD_MEDIA_BUTTON) {
      val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      if (keyEvent != null) {
        EarbudChiplet.router(this).handleKeyEvent(keyEvent)
      }
    } else if (intent?.action == ACTION_RECHECK_PLAYBACK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      handlePlaybackConfigurationsChanged(audioManager.activePlaybackConfigurations)
    }

    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    if (CoreSingletons.prefs(this).earbudEnabled.value) {
      runCatching {
        val restartIntent = Intent(applicationContext, EarbudListenerService::class.java)
        ContextCompat.startForegroundService(applicationContext, restartIntent)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    isDestroyed = true
    cancelPendingReprime()
    unregisterPlaybackCallback()
    EarbudChiplet.updateServiceRunning(false)
    runCatching {
      mediaSession?.isActive = false
      mediaSession?.release()
    }
    mediaSession = null
    super.onDestroy()
  }

  private fun setupMediaSession() {
    val router = EarbudChiplet.router(this)
    val mediaButtonPendingIntent = PendingIntent.getBroadcast(
      this,
      20,
      Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        component = ComponentName(this@EarbudListenerService, EarbudMediaButtonReceiver::class.java)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val session = MediaSession(this, "AuraClockEarbudSession").apply {
      @Suppress("DEPRECATION")
      setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
          MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
      )
      setMediaButtonReceiver(mediaButtonPendingIntent)
      setPlaybackState(
        PlaybackState.Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SKIP_TO_NEXT or
              PlaybackState.ACTION_SKIP_TO_PREVIOUS,
          )
          .setState(PlaybackState.STATE_PAUSED, 0L, 1.0f)
          .build(),
      )
      setCallback(object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
          val event = IntentCompat.getParcelableExtra(
            mediaButtonIntent,
            Intent.EXTRA_KEY_EVENT,
            KeyEvent::class.java,
          ) ?: return super.onMediaButtonEvent(mediaButtonIntent)
          return if (router.handleKeyEvent(event)) true else super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onPlay() {
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY, allowDedup = true)
        }

        override fun onPause() {
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE, allowDedup = true)
        }

        override fun onSkipToNext() {
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT, allowDedup = true)
        }

        override fun onSkipToPrevious() {
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS, allowDedup = true)
        }
      })
      isActive = true
    }
    mediaSession = session
    primeMediaButtonRouting(isReprime = false)
  }

  /** Checks active audio before initial priming so Diagnostics starts with an accurate reading. */
  private fun detectInitialExternalPlayback() {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val playing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      hasRelevantExternalAudio(audioManager.activePlaybackConfigurations)
    } else {
      @Suppress("DEPRECATION")
      audioManager.isMusicActive
    }
    EarbudChiplet.updateOtherAppPlaying(playing)
  }

  /** Registers an AudioManager playback callback on the main handler (API 26+). */
  private fun registerPlaybackCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val cb = object : AudioManager.AudioPlaybackCallback() {
      override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
        handlePlaybackConfigurationsChanged(configs.orEmpty())
      }
    }
    playbackCallback = cb
    audioManager.registerAudioPlaybackCallback(cb, mainHandler)
  }

  /** Unregisters the AudioManager playback callback cleanly when the service stops. */
  private fun unregisterPlaybackCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val cb = playbackCallback ?: return
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    runCatching { audioManager.unregisterAudioPlaybackCallback(cb) }
    playbackCallback = null
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun hasRelevantExternalAudio(configs: List<AudioPlaybackConfiguration>): Boolean =
    configs.any { cfg ->
      val usage = cfg.audioAttributes.usage
      usage == AudioAttributes.USAGE_MEDIA ||
        usage == AudioAttributes.USAGE_GAME ||
        usage == AudioAttributes.USAGE_UNKNOWN
    }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun handlePlaybackConfigurationsChanged(configs: List<AudioPlaybackConfiguration>) {
    if (isDestroyed) return
    val prefs = CoreSingletons.prefs(this)
    val speaker = CoreSingletons.speaker(this)
    val nowMs = SystemClock.elapsedRealtime()
    val lastSelfAudioMs = maxOf(lastOwnPrimeElapsedMs, speaker.lastSpeechElapsedMs())

    val plan = PlaybackReprimeLogic.evaluatePlaybackChange(
      nowMs = nowMs,
      hasExternalAudio = hasRelevantExternalAudio(configs),
      takeOverEnabled = prefs.earbudTakeOverDuringAudio.value,
      lastSelfAudioMs = lastSelfAudioMs,
      lastReprimeMs = lastReprimeElapsedMs,
      isCurrentlySpeaking = speaker.isCurrentlySpeaking(),
    )

    if (plan.ignoredSelfAudio) {
      return
    }

    EarbudChiplet.updateOtherAppPlaying(plan.isExternalPlaying)

    when (plan.action) {
      ReprimePlanAction.IGNORE,
      ReprimePlanAction.CANCEL_PENDING -> cancelPendingReprime()
      ReprimePlanAction.SCHEDULE -> scheduleDebouncedReprime(plan.delayMs)
    }
  }

  private fun cancelPendingReprime() {
    pendingReprimeRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingReprimeRunnable = null
  }

  private fun scheduleDebouncedReprime(delayMs: Long) {
    cancelPendingReprime()
    val runnable = Runnable {
      pendingReprimeRunnable = null
      if (isDestroyed) return@Runnable
      val prefs = CoreSingletons.prefs(this@EarbudListenerService)
      val speaker = CoreSingletons.speaker(this@EarbudListenerService)
      val nowMs = SystemClock.elapsedRealtime()
      val lastSelfAudioMs = maxOf(lastOwnPrimeElapsedMs, speaker.lastSpeechElapsedMs())
      if (PlaybackReprimeLogic.canExecuteReprimeNow(
          nowMs = nowMs,
          takeOverEnabled = prefs.earbudTakeOverDuringAudio.value,
          lastSelfAudioMs = lastSelfAudioMs,
          lastReprimeMs = lastReprimeElapsedMs,
          isCurrentlySpeaking = speaker.isCurrentlySpeaking(),
        )
      ) {
        primeMediaButtonRouting(isReprime = true)
      }
    }
    pendingReprimeRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  /**
   * Plays a tiny silent 30 ms PCM buffer on USAGE_MEDIA (without requesting audio focus)
   * so Android routes Bluetooth AVRCP media buttons to this session even when idle or while music plays.
   */
  private fun primeMediaButtonRouting(isReprime: Boolean) {
    lastOwnPrimeElapsedMs = SystemClock.elapsedRealtime()
    var played = false
    runCatching {
      val sampleRate = 8000
      val numSamples = 240
      val minBuf = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(numSamples * 2)

      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setBufferSizeInBytes(minBuf)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

      track.write(ShortArray(numSamples), 0, numSamples)
      track.play()
      track.stop()
      track.release()
      played = true
    }
    val finishedMs = SystemClock.elapsedRealtime()
    lastOwnPrimeElapsedMs = finishedMs
    if (isReprime && played) {
      lastReprimeElapsedMs = finishedMs
      val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
      EarbudChiplet.recordReprime(timeLabel)
    }
  }

  private fun startInForeground(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun buildNotification(): Notification {
    val openIntent = PendingIntent.getActivity(
      this,
      21,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Earbud Time Announcer")
      .setContentText("Listening for Bluetooth media button presses")
      .setContentIntent(openIntent)
      .setOngoing(true)
      .setSilent(true)
      .build()
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "Earbud Button Listener",
          NotificationManager.IMPORTANCE_MIN,
        ).apply {
          description = "Allows Bluetooth earbud presses to announce the time."
          setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
      }
    }
  }

  companion object {
    const val ACTION_FORWARD_MEDIA_BUTTON = "com.example.earbud.FORWARD_MEDIA_BUTTON"
    const val ACTION_RECHECK_PLAYBACK = "com.example.earbud.RECHECK_PLAYBACK"
    private const val CHANNEL_ID = "auraclock_earbud_channel"
    private const val NOTIFICATION_ID = 1002
  }
}

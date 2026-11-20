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
  private var reactivateSessionRunnable: Runnable? = null
  private var returnToPausedRunnable: Runnable? = null
  private var lastOwnPrimeElapsedMs: Long = -1L
  private var lastReprimeElapsedMs: Long = -1L
  private var currentSessionPlaybackState: Int = PlaybackState.STATE_PAUSED
  private var lastAppliedPlaybackState: PlaybackState? = null
  @Volatile
  private var isForwardingSessionInactive: Boolean = false
  private var isDestroyed: Boolean = false

  override fun onCreate() {
    super.onCreate()
    isDestroyed = false
    runCatching { ensureChannel() }
    val initialExternalPlaying = runCatching { detectInitialExternalPlayback() }.getOrDefault(false)
    runCatching { setupMediaSession(initialExternalPlaying) }
    runCatching { registerPlaybackCallback() }
    EarbudChiplet.updateServiceRunning(true)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Satisfy Android's startForegroundService contract on every path before any early return or stopSelf().
    runCatching { startInForeground(buildNotification()) }

    val prefs = CoreSingletons.prefs(this)
    if (!prefs.earbudEnabled.value) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
      stopSelf()
      return START_NOT_STICKY
    }

    if (intent?.action == ACTION_FORWARD_MEDIA_BUTTON) {
      val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      if (keyEvent != null) {
        EarbudChiplet.router(this).handleKeyEvent(keyEvent, route = KeyArrivalRoute.SERVICE_FORWARD.label)
      }
    } else if (intent?.action == ACTION_RECHECK_PLAYBACK) {
      val playing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
          handlePlaybackConfigurationsChanged(audioManager.activePlaybackConfigurations)
          hasRelevantExternalAudio(audioManager.activePlaybackConfigurations)
        } else {
          false
        }
      } else {
        detectInitialExternalPlayback()
      }
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && playing) {
        triggerPlayingFlipForTakeOverIfAllowed(externalPlaying = true)
      }
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
    isForwardingSessionInactive = false
    cancelPendingReprime()
    cancelReturnToPaused()
    reactivateSessionRunnable?.let { mainHandler.removeCallbacks(it) }
    reactivateSessionRunnable = null
    unregisterPlaybackCallback()
    EarbudChiplet.router(this).setSessionForwarder(null)
    EarbudChiplet.updateSessionPlaybackState("Inactive")
    EarbudChiplet.updateServiceRunning(false)
    runCatching {
      mediaSession?.isActive = false
      mediaSession?.release()
    }
    mediaSession = null
    super.onDestroy()
  }

  private fun setupMediaSession(initialExternalPlaying: Boolean) {
    val router = EarbudChiplet.router(this)
    val mediaButtonPendingIntent = PendingIntent.getBroadcast(
      this,
      20,
      Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        component = ComponentName(this@EarbudListenerService, EarbudMediaButtonReceiver::class.java)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Intentionally set NO metadata so Android shows no media card or lock-screen controls.
    val session = MediaSession(this, "AuraClockEarbudSession").apply {
      @Suppress("DEPRECATION")
      setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
          MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
      )
      setMediaButtonReceiver(mediaButtonPendingIntent)
      applyPlaybackState(this, buildPlaybackState(PlaybackState.STATE_PAUSED))
      setCallback(object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
          val event = IntentCompat.getParcelableExtra(
            mediaButtonIntent,
            Intent.EXTRA_KEY_EVENT,
            KeyEvent::class.java,
          )
          if (isForwardingSessionInactive) {
            if (event != null) {
              RawKeyLog.record(
                keyCode = event.keyCode,
                isDown = event.action != KeyEvent.ACTION_UP,
                route = KeyArrivalRoute.MEDIA_SESSION_EVENT.label,
                outcome = "Ignored as its own key (session forwarding)",
              )
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
          }
          if (event == null) return super.onMediaButtonEvent(mediaButtonIntent)
          return if (router.handleKeyEvent(event, route = KeyArrivalRoute.MEDIA_SESSION_EVENT.label)) {
            true
          } else {
            super.onMediaButtonEvent(mediaButtonIntent)
          }
        }

        override fun onPlay() {
          if (isForwardingSessionInactive) {
            RawKeyLog.record(
              keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
              isDown = true,
              route = KeyArrivalRoute.MEDIA_SESSION_PLAY.label,
              outcome = "Ignored as its own key (session forwarding)",
            )
            return
          }
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY, allowDedup = true, route = KeyArrivalRoute.MEDIA_SESSION_PLAY.label)
        }

        override fun onPause() {
          if (isForwardingSessionInactive) {
            RawKeyLog.record(
              keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
              isDown = true,
              route = KeyArrivalRoute.MEDIA_SESSION_PAUSE.label,
              outcome = "Ignored as its own key (session forwarding)",
            )
            return
          }
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE, allowDedup = true, route = KeyArrivalRoute.MEDIA_SESSION_PAUSE.label)
        }

        override fun onSkipToNext() {
          if (isForwardingSessionInactive) {
            RawKeyLog.record(
              keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
              isDown = true,
              route = KeyArrivalRoute.MEDIA_SESSION_NEXT.label,
              outcome = "Ignored as its own key (session forwarding)",
            )
            return
          }
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT, allowDedup = true, route = KeyArrivalRoute.MEDIA_SESSION_NEXT.label)
        }

        override fun onSkipToPrevious() {
          if (isForwardingSessionInactive) {
            RawKeyLog.record(
              keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
              isDown = true,
              route = KeyArrivalRoute.MEDIA_SESSION_PREVIOUS.label,
              outcome = "Ignored as its own key (session forwarding)",
            )
            return
          }
          router.routeKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS, allowDedup = true, route = KeyArrivalRoute.MEDIA_SESSION_PREVIOUS.label)
        }
      })
      isActive = true
    }
    mediaSession = session
    currentSessionPlaybackState = PlaybackState.STATE_PAUSED
    EarbudChiplet.updateSessionPlaybackState("STATE_PAUSED")
    router.setSessionForwarder { keyCode -> forwardMediaKeyFromSession(keyCode) }
    primeMediaButtonRouting(isReprime = false, externalAudioPlaying = initialExternalPlaying)
  }

  /**
   * Deactivates AuraClock's MediaSession temporarily, dispatches DOWN+UP via AudioManager to the
   * underlying music app, and re-activates the session ~300 ms later.
   * After reactivation, flips the session briefly to STATE_PLAYING (~1.5 s) when take-over is enabled
   * so any subsequent press in a burst still routes back to AuraClock.
   */
  internal fun forwardMediaKeyFromSession(keyCode: Int): Boolean {
    val session = mediaSession
    if (session == null) {
      return MediaKeyRouter.defaultDispatchMediaKey(this, keyCode)
    }
    reactivateSessionRunnable?.let { mainHandler.removeCallbacks(it) }
    cancelReturnToPaused()
    isForwardingSessionInactive = true
    setSessionStatePaused(recordFlip = false)
    runCatching { session.isActive = false }

    val dispatched = MediaKeyRouter.defaultDispatchMediaKey(this, keyCode)

    val reactivate = Runnable {
      reactivateSessionRunnable = null
      isForwardingSessionInactive = false
      if (!isDestroyed) {
        val activeSession = mediaSession ?: return@Runnable
        runCatching { activeSession.isActive = true }
        val prefs = CoreSingletons.prefs(this@EarbudListenerService)
        val speaker = CoreSingletons.speaker(this@EarbudListenerService)
        val nowMs = SystemClock.elapsedRealtime()
        val lastSelfAudioMs = maxOf(lastOwnPrimeElapsedMs, speaker.lastSpeechElapsedMs())
        if (PlaybackReprimeLogic.shouldFlipPlaybackState(
            nowMs = nowMs,
            takeOverEnabled = prefs.earbudTakeOverDuringAudio.value,
            lastSelfAudioMs = lastSelfAudioMs,
            isCurrentlySpeaking = speaker.isCurrentlySpeaking(),
            hasRelevantTrigger = true,
          )
        ) {
          flipSessionToPlayingTemporarily()
        }
      }
    }
    reactivateSessionRunnable = reactivate
    mainHandler.postDelayed(reactivate, SESSION_REACTIVATE_DELAY_MS)
    return dispatched
  }

  /** Checks active audio before initial priming so Diagnostics starts with an accurate reading. */
  private fun detectInitialExternalPlayback(): Boolean {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    val playing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      hasRelevantExternalAudio(audioManager.activePlaybackConfigurations)
    } else {
      @Suppress("DEPRECATION")
      audioManager.isMusicActive
    }
    EarbudChiplet.updateOtherAppPlaying(playing)
    return playing
  }

  /** Registers an AudioManager playback callback on the main handler (API 26+). */
  private fun registerPlaybackCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
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
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    runCatching { audioManager?.unregisterAudioPlaybackCallback(cb) }
    playbackCallback = null
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun hasRelevantExternalAudio(configs: List<AudioPlaybackConfiguration>): Boolean =
    configs.any { cfg ->
      val usage = cfg.audioAttributes?.usage
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

  private fun triggerPlayingFlipForTakeOverIfAllowed(externalPlaying: Boolean) {
    val prefs = CoreSingletons.prefs(this)
    val speaker = CoreSingletons.speaker(this)
    val nowMs = SystemClock.elapsedRealtime()
    val lastSelfAudioMs = maxOf(lastOwnPrimeElapsedMs, speaker.lastSpeechElapsedMs())
    if (PlaybackReprimeLogic.shouldFlipPlaybackState(
        nowMs = nowMs,
        takeOverEnabled = prefs.earbudTakeOverDuringAudio.value,
        lastSelfAudioMs = lastSelfAudioMs,
        isCurrentlySpeaking = speaker.isCurrentlySpeaking(),
        hasRelevantTrigger = externalPlaying,
      )
    ) {
      primeMediaButtonRouting(isReprime = true, externalAudioPlaying = true)
    }
  }

  private fun cancelPendingReprime() {
    pendingReprimeRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingReprimeRunnable = null
  }

  private fun cancelReturnToPaused() {
    returnToPausedRunnable?.let { mainHandler.removeCallbacks(it) }
    returnToPausedRunnable = null
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
        primeMediaButtonRouting(isReprime = true, externalAudioPlaying = true)
      }
    }
    pendingReprimeRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  /**
   * When re-priming or taking over from active external audio with take-over enabled:
   * 1. Flips MediaSession PlaybackState to STATE_PLAYING (position 0, speed 1.0).
   * 2. Plays the silent 30 ms AudioTrack on USAGE_MEDIA (no audio focus requested).
   * 3. Schedules a return to STATE_PAUSED after ~1.5 seconds.
   * When nothing is playing at initial start or take-over is off, leaves state untouched at STATE_PAUSED.
   */
  internal fun primeMediaButtonRouting(
    isReprime: Boolean,
    externalAudioPlaying: Boolean = isReprime,
  ) {
    val prefs = CoreSingletons.prefs(this)
    val speaker = CoreSingletons.speaker(this)
    val checkMs = SystemClock.elapsedRealtime()
    val lastSelfAudioMs = maxOf(lastOwnPrimeElapsedMs, speaker.lastSpeechElapsedMs())
    val shouldFlip = PlaybackReprimeLogic.shouldFlipPlaybackState(
      nowMs = checkMs,
      takeOverEnabled = prefs.earbudTakeOverDuringAudio.value,
      lastSelfAudioMs = lastSelfAudioMs,
      isCurrentlySpeaking = speaker.isCurrentlySpeaking(),
      hasRelevantTrigger = isReprime || externalAudioPlaying,
    )

    if (shouldFlip) {
      setSessionStatePlaying()
    }

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

    if (shouldFlip) {
      scheduleReturnToPaused()
    }
    if (isReprime && played) {
      lastReprimeElapsedMs = finishedMs
      val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
      EarbudChiplet.recordReprime(timeLabel)
    }
  }

  private fun flipSessionToPlayingTemporarily() {
    setSessionStatePlaying()
    scheduleReturnToPaused()
  }

  private fun setSessionStatePlaying() {
    val session = mediaSession ?: return
    applyPlaybackState(session, buildPlaybackState(PlaybackState.STATE_PLAYING))
    val flippedFromNonPlaying = currentSessionPlaybackState != PlaybackState.STATE_PLAYING
    currentSessionPlaybackState = PlaybackState.STATE_PLAYING
    if (flippedFromNonPlaying) {
      val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
      EarbudChiplet.recordSessionStateFlip(stateLabel = "STATE_PLAYING", timeLabel = timeLabel)
    } else {
      EarbudChiplet.updateSessionPlaybackState("STATE_PLAYING")
    }
  }

  private fun setSessionStatePaused(recordFlip: Boolean = true) {
    val session = mediaSession ?: return
    applyPlaybackState(session, buildPlaybackState(PlaybackState.STATE_PAUSED))
    currentSessionPlaybackState = PlaybackState.STATE_PAUSED
    if (recordFlip) {
      EarbudChiplet.updateSessionPlaybackState("STATE_PAUSED")
    }
  }

  private fun applyPlaybackState(session: MediaSession, state: PlaybackState) {
    lastAppliedPlaybackState = state
    runCatching { session.setPlaybackState(state) }
  }

  private fun scheduleReturnToPaused() {
    cancelReturnToPaused()
    val runnable = Runnable {
      returnToPausedRunnable = null
      if (!isDestroyed && !isForwardingSessionInactive) {
        setSessionStatePaused(recordFlip = true)
      }
    }
    returnToPausedRunnable = runnable
    mainHandler.postDelayed(runnable, PlaybackReprimeLogic.HOLD_PLAYING_DURATION_MS)
  }

  private fun buildPlaybackState(state: Int): PlaybackState =
    PlaybackState.Builder()
      .setActions(SUPPORTED_MEDIA_ACTIONS)
      .setState(state, 0L, 1.0f)
      .build()

  internal fun mediaSessionForTest(): MediaSession? = mediaSession

  internal fun lastAppliedPlaybackStateForTest(): PlaybackState? = lastAppliedPlaybackState

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
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setVisibility(NotificationCompat.VISIBILITY_SECRET)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
          lockscreenVisibility = Notification.VISIBILITY_SECRET
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
    private const val SESSION_REACTIVATE_DELAY_MS = 300L
    private const val SUPPORTED_MEDIA_ACTIONS =
      PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS
  }
}

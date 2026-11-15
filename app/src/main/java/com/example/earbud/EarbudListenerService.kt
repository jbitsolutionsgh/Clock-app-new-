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
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.core.CoreSingletons

/**
 * Foreground MediaSession listener service that receives Bluetooth earbud button presses
 * and restarts via START_STICKY / onTaskRemoved if stopped by the system.
 */
class EarbudListenerService : Service() {

  private var mediaSession: MediaSession? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    setupMediaSession()
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
    primeMediaButtonRouting()
  }

  /**
   * Plays a tiny silent 30 ms PCM buffer on USAGE_MEDIA (without requesting audio focus)
   * so Android routes Bluetooth AVRCP media buttons to this session even when idle.
   */
  private fun primeMediaButtonRouting() {
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
    private const val CHANNEL_ID = "auraclock_earbud_channel"
    private const val NOTIFICATION_ID = 1002
  }
}

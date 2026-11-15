package com.example.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.CoreSingletons
import com.example.core.TimerPhase
import com.example.core.TimerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the classroom countdown alive when the screen turns off
 * or the teacher switches apps, displaying a live notification with a Stop button.
 */
class TimerForegroundService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observerJob: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var lastNotifiedSecond: Long = -1L
  private var lastNotifiedPhase: TimerPhase? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_USER_STOP -> {
        CoreSingletons.timerController?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_STOP_SERVICE -> {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        acquireWakeLock()
        val initial = CoreSingletons.timerController?.state?.value ?: TimerSnapshot()
        startInForeground(buildNotification(initial))
        observeTimerState()
        return START_NOT_STICKY
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    observerJob?.cancel()
    serviceScope.cancel()
    releaseWakeLock()
    super.onDestroy()
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

  private fun observeTimerState() {
    if (observerJob != null) return
    val controller = CoreSingletons.timerController ?: return
    observerJob = serviceScope.launch {
      controller.state.collectLatest { snap ->
        val displaySecond = (snap.remainingMs + 999L) / 1000L
        if (displaySecond != lastNotifiedSecond || snap.phase != lastNotifiedPhase) {
          lastNotifiedSecond = displaySecond
          lastNotifiedPhase = snap.phase
          val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          mgr.notify(NOTIFICATION_ID, buildNotification(snap))
        }
      }
    }
  }

  private fun buildNotification(snap: TimerSnapshot): Notification {
    val openAppIntent = PendingIntent.getActivity(
      this,
      10,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val stopIntent = PendingIntent.getService(
      this,
      11,
      Intent(this, TimerForegroundService::class.java).apply {
        action = ACTION_USER_STOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val totalSec = ((snap.remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val timeFormatted = "%02d:%02d".format(mins, secs)

    val title = when (snap.phase) {
      TimerPhase.GAP_WAIT -> "Round ${snap.roundNumber} complete — Next round"
      TimerPhase.FINISHED -> "Timer complete"
      else -> if (CoreSingletons.prefs(this).roundsMode.value) {
        "Classroom Timer • Round ${snap.roundNumber}"
      } else {
        "Classroom Timer"
      }
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(timeFormatted)
      .setContentIntent(openAppIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .addAction(0, getString(R.string.timer_stop), stopIntent)
      .build()
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "Classroom Timer",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Keeps the classroom countdown running accurately in the background."
          setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
      }
    }
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraClock::TimerWakeLock").apply {
      setReferenceCounted(false)
      acquire(2 * 60 * 60 * 1000L)
    }
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    wakeLock = null
  }

  companion object {
    const val ACTION_START = "com.example.timer.ACTION_START"
    const val ACTION_STOP_SERVICE = "com.example.timer.ACTION_STOP_SERVICE"
    const val ACTION_USER_STOP = "com.example.timer.ACTION_USER_STOP"
    private const val CHANNEL_ID = "auraclock_timer_channel"
    private const val NOTIFICATION_ID = 1001
  }
}

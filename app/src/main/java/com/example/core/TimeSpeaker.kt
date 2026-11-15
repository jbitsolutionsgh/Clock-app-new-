package com.example.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single shared TextToSpeech engine for AuraClock.
 * Requests transient audio focus only while speaking and releases it immediately afterward.
 */
class TimeSpeaker(context: Context) {

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private val speechAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

  private var focusRequest: AudioFocusRequest? = null
  private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

  private val _isReady = MutableStateFlow(false)
  val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

  private val _statusMessage = MutableStateFlow("Initializing TTS...")
  val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

  private var tts: TextToSpeech? = null
  private var pendingUtterance: String? = null

  private val safetyReleaseRunnable = Runnable { abandonFocus() }

  init {
    initTts()
  }

  /** Initializes the shared TextToSpeech instance with media/speech AudioAttributes. */
  private fun initTts() {
    tts = TextToSpeech(appContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val engine = tts ?: return@TextToSpeech
        val langResult = engine.setLanguage(Locale.getDefault())
        engine.setAudioAttributes(speechAttributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {}
          override fun onDone(utteranceId: String?) = abandonFocus()
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) = abandonFocus()
          override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
        })
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
          _isReady.value = true
          _statusMessage.value = "Ready (default locale)"
        } else {
          _isReady.value = true
          _statusMessage.value = "Ready"
        }
        pendingUtterance?.let { queued ->
          pendingUtterance = null
          speakText(queued)
        }
      } else {
        _isReady.value = false
        _statusMessage.value = "TTS init failed (code $status)"
      }
    }
  }

  /** Speaks the current local time and day of week (for example, "The time is 9:41 PM, Saturday"). */
  fun speakCurrentTime() {
    val now = Date()
    val timeText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
    val dayText = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
    speakText("The time is $timeText, $dayText")
  }

  /** Speaks arbitrary text after requesting short transient audio focus. */
  fun speakText(text: String) {
    if (!_isReady.value || tts == null) {
      pendingUtterance = text
      if (tts == null) initTts()
      return
    }
    requestTransientFocus()
    mainHandler.removeCallbacks(safetyReleaseRunnable)
    mainHandler.postDelayed(safetyReleaseRunnable, 8_000L)
    val utteranceId = "aura_tts_${System.currentTimeMillis()}"
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
  }

  /** Requests short transient audio focus that ducks background audio only while speaking. */
  private fun requestTransientFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()
        .also { focusRequest = it }
      audioManager.requestAudioFocus(req)
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        focusChangeListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
      )
    }
  }

  /** Releases transient audio focus immediately once speech ends. */
  private fun abandonFocus() {
    mainHandler.removeCallbacks(safetyReleaseRunnable)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusChangeListener)
    }
  }

  /** Shuts down TTS cleanly if the application process terminates. */
  fun shutdown() {
    abandonFocus()
    tts?.stop()
    tts?.shutdown()
    tts = null
    _isReady.value = false
  }
}

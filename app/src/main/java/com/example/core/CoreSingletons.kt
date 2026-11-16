package com.example.core

import android.content.Context

/**
 * Holds the single application-wide instances of preferences, TextToSpeech,
 * and cross-chiplet controller interfaces. Never recreated per screen or click.
 */
object CoreSingletons {

  @Volatile
  private var prefsInstance: AuraPrefs? = null

  @Volatile
  private var speakerInstance: TimeSpeaker? = null

  @Volatile
  private var faceDataSourceInstance: FaceDataSource? = null

  @Volatile
  var timerController: TimerController? = null

  @Volatile
  var stopwatchController: StopwatchController? = null

  @Volatile
  var faceNavigator: FaceNavigator? = null

  @Volatile
  var earbudController: EarbudController? = null

  @Volatile
  var standByClockRenderer: StandByClockRenderer? = null

  @Volatile
  var standByDiagnosticsProvider: StandByDiagnosticsProvider? = null

  @Volatile
  var nextEventSource: NextEventSource? = null

  @Volatile
  var faceDiagnosticsProvider: FaceDiagnosticsProvider? = null

  /** Returns the single shared AuraPrefs instance. */
  fun prefs(context: Context): AuraPrefs =
    prefsInstance ?: synchronized(this) {
      prefsInstance ?: AuraPrefs(context.applicationContext).also { prefsInstance = it }
    }

  /** Returns the single shared TimeSpeaker instance. */
  fun speaker(context: Context): TimeSpeaker =
    speakerInstance ?: synchronized(this) {
      speakerInstance ?: TimeSpeaker(context.applicationContext).also { speakerInstance = it }
    }

  /** Returns the single shared FaceDataSource instance. */
  fun faceDataSource(context: Context): FaceDataSource =
    faceDataSourceInstance ?: synchronized(this) {
      faceDataSourceInstance ?: CoreFaceDataSource(context.applicationContext).also {
        faceDataSourceInstance = it
      }
    }
}

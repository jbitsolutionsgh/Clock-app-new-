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
  private var sensorSourceInstance: FaceSensorSource? = null

  private val _tabRequests = kotlinx.coroutines.flow.MutableSharedFlow<AppTabTarget>(extraBufferCapacity = 4)
  val tabRequests: kotlinx.coroutines.flow.SharedFlow<AppTabTarget> = _tabRequests

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

  /** Requests MainActivity to switch tabs (e.g. when tapping a complication slot). */
  fun requestTab(target: AppTabTarget) {
    _tabRequests.tryEmit(target)
  }

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

  /** Returns the single shared FaceSensorSource instance. */
  fun sensorSource(context: Context): FaceSensorSource =
    sensorSourceInstance ?: synchronized(this) {
      sensorSourceInstance ?: CoreFaceSensorSource(
        context = context.applicationContext,
        prefs = prefs(context),
      ).also { sensorSourceInstance = it }
    }
}

package com.example

import android.app.Application
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.core.StartupSafetyNet
import com.example.earbud.EarbudChiplet
import com.example.faces.FacesChiplet
import com.example.standby.StandByChiplet
import com.example.stopwatch.StopwatchChiplet
import com.example.timer.TimerChiplet

/**
 * Application entry point that installs synchronous crash logging, isolates optional startup steps,
 * and tracks crash-loop recovery state before any chiplet initializes.
 */
class AuraClockApp : Application() {

  override fun onCreate() {
    super.onCreate()
    initializeApplication()
  }

  internal fun initializeApplication() {
    CrashCatcher.install(this)
    StartupSafetyNet.resetSessionStepFailures()
    StartupSafetyNet.beginStartupAttempt(this)
    StartupSafetyNet.maybeRunStartupCrashHookForTest()

    StartupSafetyNet.runOptionalStep(this, "Preferences") {
      CoreSingletons.prefs(this)
    }

    if (StartupSafetyNet.shouldShowRecoveryScreen(this) || StartupSafetyNet.isSafeModeActive) {
      return
    }

    StartupSafetyNet.runOptionalStep(this, "Time speaker (Text-to-Speech)") {
      CoreSingletons.speaker(this)
    }
    StartupSafetyNet.runOptionalStep(this, "Classroom timer") {
      TimerChiplet.ensureInitialized(this)
    }
    StartupSafetyNet.runOptionalStep(this, "Stopwatch") {
      StopwatchChiplet.ensureInitialized()
    }
    StartupSafetyNet.runOptionalStep(this, "Watch faces") {
      FacesChiplet.ensureInitialized(this)
    }
    StartupSafetyNet.runOptionalStep(this, "Earbud listener") {
      val earbud = EarbudChiplet.ensureInitialized(this)
      if (StartupSafetyNet.isAppForeground(this)) {
        earbud.ensureStarted()
      }
    }
    StartupSafetyNet.runOptionalStep(this, "Stand By calendar") {
      StandByChiplet.ensureInitialized(this)
    }
    StartupSafetyNet.runOptionalStep(this, "Phone sensors") {
      CoreSingletons.sensorSource(this)
    }
  }
}

package com.example

import android.app.Application
import com.example.core.CoreSingletons
import com.example.core.CrashCatcher
import com.example.earbud.EarbudChiplet
import com.example.faces.FacesChiplet
import com.example.standby.StandByChiplet
import com.example.stopwatch.StopwatchChiplet
import com.example.timer.TimerChiplet

/**
 * Application entry point that initializes crash logging and shared singletons once.
 */
class AuraClockApp : Application() {

  override fun onCreate() {
    super.onCreate()
    CrashCatcher.install(this)
    CoreSingletons.prefs(this)
    CoreSingletons.speaker(this)
    TimerChiplet.ensureInitialized(this)
    StopwatchChiplet.ensureInitialized()
    FacesChiplet.ensureInitialized(this)
    EarbudChiplet.ensureInitialized(this).ensureStarted()
    StandByChiplet.ensureInitialized(this)
  }
}

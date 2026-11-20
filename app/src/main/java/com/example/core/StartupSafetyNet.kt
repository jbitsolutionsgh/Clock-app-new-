package com.example.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets

data class StartupStepFailure(
  val stepName: String,
  val summary: String,
  val stackTrace: String,
)

/**
 * Startup Safety Net for AuraClock:
 * - Marks a startup attempt as "in progress" (`inMainStartup`) only when opening `MainActivity`.
 * - Tracks `Application.onCreate` (`inAppCreate`) briefly for uncaught exceptions and clears it
 *   after 5 seconds on the main handler (never counting background exits without an uncaught crash).
 * - Isolates optional Application/startup initialization steps so a single failure does not kill the app.
 * - Triggers the "Startup problem" recovery flow after 2 consecutive startup crashes, with Copy, Share,
 *   Safe Mode, and full WFF/preferences reset actions.
 */
object StartupSafetyNet {

  private const val STATE_FILE_NAME = "startup_safety.json"
  private const val MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY = 2
  internal const val APP_CREATE_CLEAR_DELAY_MS = 5_000L

  private val lock = Any()
  private val _stepFailures = mutableListOf<StartupStepFailure>()
  private var appCreateTokenCounter = 0L
  private var pendingAppCreateClearRunnable: Runnable? = null

  private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

  @Volatile
  var isSafeModeActive: Boolean = false
    private set

  @Volatile
  private var recoveryDismissedForSession: Boolean = false

  /** Optional test hooks to verify startup step isolation and crash-loop recovery in tests. */
  @Volatile
  internal var simulatedStepFailureHook: ((stepName: String) -> Unit)? = null

  @Volatile
  internal var simulatedStartupCrashHook: (() -> Unit)? = null

  @Volatile
  internal var pidProvider: () -> Int = { Process.myPid() }

  private data class PersistedState(
    val inMainStartup: Boolean = false,
    val inAppCreate: Boolean = false,
    val appCreateRunToken: Long = 0L,
    val uncaughtRecordedForRun: Boolean = false,
    val activePid: Int = 0,
    val consecutiveStartupCrashes: Int = 0,
  )

  /**
   * Starts brief uncaught-crash tracking in `Application.onCreate` and schedules clearing it
   * 5 seconds later on the main handler if the process is still alive.
   *
   * Background starts (widgets, DreamService, timer/earbud services, broadcasts) that end without
   * opening `MainActivity` and without an uncaught exception never count as startup crashes.
   */
  fun beginApplicationStartupAttempt(context: Context) {
    val appCtx = context.applicationContext
    val pid = pidProvider()
    synchronized(lock) {
      val current = readState(appCtx)
      if (current.consecutiveStartupCrashes >= MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY) {
        cancelAppCreateClearLocked()
        return
      }
      var crashes = current.consecutiveStartupCrashes
      if (current.inMainStartup && !current.uncaughtRecordedForRun && current.activePid != pid) {
        crashes += 1
      }
      cancelAppCreateClearLocked()
      if (crashes >= MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY) {
        writeState(
          appCtx,
          PersistedState(
            inMainStartup = false,
            inAppCreate = false,
            appCreateRunToken = 0L,
            uncaughtRecordedForRun = false,
            activePid = pid,
            consecutiveStartupCrashes = crashes,
          ),
        )
      } else {
        appCreateTokenCounter += 1L
        val token = appCreateTokenCounter
        writeState(
          appCtx,
          PersistedState(
            inMainStartup = false,
            inAppCreate = true,
            appCreateRunToken = token,
            uncaughtRecordedForRun = false,
            activePid = pid,
            consecutiveStartupCrashes = crashes,
          ),
        )
        val runnable = Runnable {
          clearAppCreateTracking(appCtx, pid, token)
        }
        pendingAppCreateClearRunnable = runnable
        runCatching {
          mainHandler.postDelayed(runnable, APP_CREATE_CLEAR_DELAY_MS)
        }
      }
    }
  }

  /**
   * Begins tracking a real startup attempt when opening `MainActivity`.
   * If a previous process died mid-main-startup without recording an uncaught crash, counts it here.
   */
  fun beginStartupAttempt(context: Context) {
    val appCtx = context.applicationContext
    val pid = pidProvider()
    synchronized(lock) {
      val current = readState(appCtx)
      if (current.consecutiveStartupCrashes >= MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY) {
        cancelAppCreateClearLocked()
        return
      }
      var crashes = current.consecutiveStartupCrashes
      if (current.inMainStartup && !current.uncaughtRecordedForRun && current.activePid != pid) {
        crashes += 1
      }
      cancelAppCreateClearLocked()
      if (crashes >= MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY) {
        writeState(
          appCtx,
          PersistedState(
            inMainStartup = false,
            inAppCreate = false,
            appCreateRunToken = 0L,
            uncaughtRecordedForRun = false,
            activePid = pid,
            consecutiveStartupCrashes = crashes,
          ),
        )
      } else {
        writeState(
          appCtx,
          PersistedState(
            inMainStartup = true,
            inAppCreate = false,
            appCreateRunToken = 0L,
            uncaughtRecordedForRun = false,
            activePid = pid,
            consecutiveStartupCrashes = crashes,
          ),
        )
      }
    }
  }

  private fun clearAppCreateTracking(appCtx: Context, pid: Int, token: Long) {
    synchronized(lock) {
      val current = readState(appCtx)
      if (current.inAppCreate && current.activePid == pid && current.appCreateRunToken == token) {
        writeState(
          appCtx,
          current.copy(
            inAppCreate = false,
            appCreateRunToken = 0L,
          ),
        )
      }
      pendingAppCreateClearRunnable = null
    }
  }

  private fun cancelAppCreateClearLocked() {
    val existing = pendingAppCreateClearRunnable ?: return
    pendingAppCreateClearRunnable = null
    runCatching { mainHandler.removeCallbacks(existing) }
  }

  /** Clears recorded step errors at the beginning of a fresh Application.onCreate run. */
  fun resetSessionStepFailures() {
    synchronized(lock) {
      _stepFailures.clear()
      recoveryDismissedForSession = false
    }
  }

  /**
   * Runs one non-essential startup step in isolation. Never rethrows; records the failed step
   * and writes a non-fatal diagnostic entry to `startup_steps.txt` if it fails. Skips in Safe Mode.
   */
  fun runOptionalStep(context: Context, stepName: String, block: () -> Unit) {
    if (isSafeModeActive) return
    val appCtx = context.applicationContext
    try {
      simulatedStepFailureHook?.invoke(stepName)
      block()
    } catch (t: Throwable) {
      val sw = StringWriter()
      t.printStackTrace(PrintWriter(sw))
      val failure = StartupStepFailure(
        stepName = stepName,
        summary = "${t.javaClass.simpleName}: ${t.message ?: "Unknown error"}",
        stackTrace = sw.toString(),
      )
      synchronized(lock) {
        _stepFailures += failure
      }
      CrashCatcher.logNonFatal(appCtx, "Startup step '$stepName' failed", t)
    }
  }

  /** Invokes any test-configured fatal startup crash hook during startup verification. */
  fun maybeRunStartupCrashHookForTest() {
    simulatedStartupCrashHook?.invoke()
  }

  /** Synchronously records an uncaught exception during startup so crash loops persist across process death. */
  fun recordUncaughtCrash(context: Context, thread: Thread, throwable: Throwable) {
    val appCtx = context.applicationContext
    synchronized(lock) {
      val current = readState(appCtx)
      if ((current.inMainStartup || current.inAppCreate) && !current.uncaughtRecordedForRun) {
        cancelAppCreateClearLocked()
        writeState(
          appCtx,
          PersistedState(
            inMainStartup = false,
            inAppCreate = false,
            appCreateRunToken = 0L,
            uncaughtRecordedForRun = true,
            activePid = current.activePid,
            consecutiveStartupCrashes = current.consecutiveStartupCrashes + 1,
          ),
        )
      }
    }
    CrashCatcher.writeCrashLog(appCtx, thread, throwable)
  }

  /** Simulates a real `MainActivity` startup crash cleanly inside Robolectric tests without terminating the JVM runner. */
  fun simulateStartupCrashForTest(context: Context, throwable: Throwable) {
    val appCtx = context.applicationContext
    beginStartupAttempt(appCtx)
    recordUncaughtCrash(appCtx, Thread.currentThread(), throwable)
  }

  /** Simulates an uncaught crash during `Application.onCreate` inside Robolectric tests. */
  internal fun simulateApplicationStartupCrashForTest(context: Context, throwable: Throwable) {
    val appCtx = context.applicationContext
    beginApplicationStartupAttempt(appCtx)
    recordUncaughtCrash(appCtx, Thread.currentThread(), throwable)
  }

  /** Marks that the app reached its initial interactive UI cleanly without crashing. */
  fun markStartupSucceeded(context: Context) {
    val appCtx = context.applicationContext
    synchronized(lock) {
      val current = readState(appCtx)
      if (current.consecutiveStartupCrashes >= MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY && !recoveryDismissedForSession) {
        return
      }
      cancelAppCreateClearLocked()
      writeState(
        appCtx,
        PersistedState(
          inMainStartup = false,
          inAppCreate = false,
          appCreateRunToken = 0L,
          uncaughtRecordedForRun = false,
          activePid = pidProvider(),
          consecutiveStartupCrashes = 0,
        ),
      )
    }
  }

  /** Returns true if the app crashed during its last two startups and recovery has not yet been dismissed. */
  fun shouldShowRecoveryScreen(context: Context): Boolean {
    if (recoveryDismissedForSession) return false
    val state = synchronized(lock) { readState(context.applicationContext) }
    return state.consecutiveStartupCrashes >= MAX_CONSECUTIVE_CRASHES_BEFORE_RECOVERY
  }

  /** Returns the current number of recorded consecutive startup crashes. */
  fun consecutiveStartupCrashCount(context: Context): Int =
    synchronized(lock) { readState(context.applicationContext).consecutiveStartupCrashes }

  internal fun isMainStartupInProgressForTest(context: Context): Boolean =
    synchronized(lock) { readState(context.applicationContext).inMainStartup }

  internal fun isAppCreateTrackingActiveForTest(context: Context): Boolean =
    synchronized(lock) { readState(context.applicationContext).inAppCreate }

  /** Returns recorded non-fatal startup step failures from this run. */
  fun stepFailures(): List<StartupStepFailure> =
    synchronized(lock) { _stepFailures.toList() }

  /**
   * Builds a combined diagnostic error string for the Startup problem screen, Copy, and Share actions,
   * showing both `last_crash.txt` (fatal crash) and `startup_steps.txt` (non-fatal step errors).
   */
  fun buildErrorReport(context: Context): String {
    val sb = StringBuilder()
    val crash = CrashCatcher.readLastCrashLog(context)?.trim()
    val stepLog = CrashCatcher.readStartupStepsLog(context)?.trim()
    val steps = stepFailures()

    if (!crash.isNullOrBlank()) {
      sb.appendLine("Last crash (last_crash.txt):")
      sb.appendLine(crash)
    }

    if (!stepLog.isNullOrBlank()) {
      if (sb.isNotEmpty()) sb.appendLine()
      sb.appendLine("Non-fatal startup & step errors (startup_steps.txt):")
      sb.appendLine(stepLog)
    } else if (steps.isNotEmpty()) {
      if (sb.isNotEmpty()) sb.appendLine()
      sb.appendLine("Failed optional startup steps:")
      steps.forEach { step ->
        sb.appendLine("• ${step.stepName}: ${step.summary}")
      }
      if (crash.isNullOrBlank()) {
        sb.appendLine()
        sb.appendLine(steps.first().stackTrace)
      }
    }

    if (sb.isEmpty()) {
      sb.append("AuraClock crashed during its last two startups, but no stack trace was captured.")
    }
    return sb.toString().trim()
  }

  /** Builds a chooser Share Intent for the startup problem text. */
  fun createShareIntent(context: Context, errorText: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "AuraClock Startup Problem")
      putExtra(Intent.EXTRA_TEXT, errorText)
    }
    return Intent.createChooser(send, "Share startup error")
  }

  /**
   * Switches this session into Safe Mode (skips all optional startup steps and WFF loading),
   * resets the startup crash counter so the UI can open cleanly, and dismisses the recovery screen.
   */
  fun enterSafeMode(context: Context) {
    val appCtx = context.applicationContext
    synchronized(lock) {
      cancelAppCreateClearLocked()
      isSafeModeActive = true
      recoveryDismissedForSession = true
      writeState(
        appCtx,
        PersistedState(
          inMainStartup = false,
          inAppCreate = false,
          appCreateRunToken = 0L,
          uncaughtRecordedForRun = false,
          activePid = pidProvider(),
          consecutiveStartupCrashes = 0,
        ),
      )
    }
  }

  /**
   * Last-resort reset: deletes imported WFF packages (`files/wff/`), clears all user preferences,
   * clears Safe Mode, consecutive crash markers, and startup step logs, and allows the clean app to start.
   */
  fun clearImportedFacesAndSettings(context: Context) {
    val appCtx = context.applicationContext
    runCatching {
      File(appCtx.filesDir, "wff").deleteRecursively()
    }
    CoreSingletons.clearPreferences(appCtx)
    CrashCatcher.clearStartupStepsLog(appCtx)
    synchronized(lock) {
      cancelAppCreateClearLocked()
      isSafeModeActive = false
      recoveryDismissedForSession = true
      _stepFailures.clear()
      stateFile(appCtx).delete()
    }
  }

  /** Resets all in-memory and on-disk safety-net state (for unit tests). */
  internal fun resetAllForTest(context: Context) {
    val appCtx = context.applicationContext
    synchronized(lock) {
      cancelAppCreateClearLocked()
      isSafeModeActive = false
      recoveryDismissedForSession = false
      simulatedStepFailureHook = null
      simulatedStartupCrashHook = null
      pidProvider = { Process.myPid() }
      appCreateTokenCounter = 0L
      _stepFailures.clear()
      stateFile(appCtx).delete()
    }
    CrashCatcher.clearStartupStepsLog(appCtx)
  }

  /**
   * Returns true if this process is currently in the foreground (or running an active FGS),
   * so background cold starts via widgets, DreamService, or broadcasts do not try to launch
   * mediaPlayback foreground services illegally on Android 12+.
   */
  fun isAppForeground(context: Context): Boolean {
    return runCatching {
      val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return false
      val myPid = Process.myPid()
      val info = am.runningAppProcesses?.firstOrNull { it.pid == myPid } ?: return false
      info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }.getOrDefault(false)
  }

  private fun stateFile(appCtx: Context): File = File(appCtx.filesDir, STATE_FILE_NAME)

  private fun readState(appCtx: Context): PersistedState {
    val f = stateFile(appCtx)
    if (!f.exists() || f.length() <= 0L) return PersistedState()
    return runCatching {
      val obj = JSONObject(f.readText(StandardCharsets.UTF_8))
      PersistedState(
        inMainStartup = obj.optBoolean("inMainStartup", false),
        inAppCreate = obj.optBoolean("inAppCreate", false),
        appCreateRunToken = obj.optLong("appCreateRunToken", 0L),
        uncaughtRecordedForRun = obj.optBoolean("uncaughtRecordedForRun", false),
        activePid = obj.optInt("activePid", 0),
        consecutiveStartupCrashes = obj.optInt("consecutiveStartupCrashes", 0),
      )
    }.getOrDefault(PersistedState())
  }

  private fun writeState(appCtx: Context, state: PersistedState) {
    runCatching {
      val f = stateFile(appCtx)
      f.parentFile?.mkdirs()
      val payload = JSONObject().apply {
        put("inMainStartup", state.inMainStartup)
        put("inAppCreate", state.inAppCreate)
        put("appCreateRunToken", state.appCreateRunToken)
        put("uncaughtRecordedForRun", state.uncaughtRecordedForRun)
        put("activePid", state.activePid)
        put("consecutiveStartupCrashes", state.consecutiveStartupCrashes)
      }.toString()
      FileOutputStream(f).use { fos ->
        fos.write(payload.toByteArray(StandardCharsets.UTF_8))
        fos.flush()
        runCatching { fos.fd.sync() }
      }
    }
  }
}

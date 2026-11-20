package com.example.core

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught exception handler that writes crash stack traces to an offline log file
 * so teachers can share diagnostics without network permissions.
 */
object CrashCatcher {

  private const val CRASH_FILE_NAME = "last_crash.txt"
  private const val STARTUP_STEPS_FILE_NAME = "startup_steps.txt"
  private const val MAX_STARTUP_STEPS_BYTES = 32 * 1024
  private val fileLock = Any()
  private var installed = false

  /** Installs the uncaught exception handler once at app startup. */
  fun install(context: Context) {
    if (installed) return
    installed = true
    val appContext = context.applicationContext
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      StartupSafetyNet.recordUncaughtCrash(appContext, thread, throwable)
      previousHandler?.uncaughtException(thread, throwable)
    }
  }

  /**
   * Records a non-fatal caught error (such as a startup step failure or calendar error)
   * synchronously to `startup_steps.txt` without overwriting `last_crash.txt`.
   */
  fun logNonFatal(context: Context, title: String, throwable: Throwable) {
    runCatching {
      val sw = StringWriter()
      val pw = PrintWriter(sw)
      val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
      pw.println("AuraClock Non-Fatal Error: $title")
      pw.println("Time: $stamp")
      pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
      pw.println("---")
      throwable.printStackTrace(pw)
      pw.flush()
      val entry = sw.toString().trimEnd()
      synchronized(fileLock) {
        val file = getStartupStepsFile(context)
        val existing = if (file.exists() && file.length() > 0L) {
          file.readText(Charsets.UTF_8).trimEnd()
        } else {
          ""
        }
        val combined = if (existing.isEmpty()) entry else "$existing\n\n$entry"
        val bounded = if (combined.length > MAX_STARTUP_STEPS_BYTES) {
          combined.takeLast(MAX_STARTUP_STEPS_BYTES)
        } else {
          combined
        }
        writeTextSync(file, "$bounded\n")
      }
    }
  }

  /** Formats and synchronously writes a crash stack trace to internal files storage before restart. */
  internal fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable): String {
    val text = buildCrashReport(thread, throwable)
    runCatching {
      synchronized(fileLock) {
        writeTextSync(getCrashFile(context), text)
      }
    }
    return text
  }

  internal fun buildCrashReport(thread: Thread, throwable: Throwable): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    pw.println("AuraClock Crash Log")
    pw.println("Time: $stamp")
    pw.println("Thread: ${thread.name}")
    pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
    pw.println("---")
    throwable.printStackTrace(pw)
    pw.flush()
    return sw.toString()
  }

  private fun writeTextSync(file: File, content: String) {
    file.parentFile?.mkdirs()
    java.io.FileOutputStream(file).use { fos ->
      fos.write(content.toByteArray(Charsets.UTF_8))
      fos.flush()
      runCatching { fos.fd.sync() }
    }
  }

  /** Returns true if a fatal crash log file (`last_crash.txt`) exists on disk. */
  fun hasCrashLog(context: Context): Boolean =
    getCrashFile(context).let { it.exists() && it.length() > 0L }

  /** Reads the last saved fatal crash log (`last_crash.txt`), or null if none exists. */
  fun readLastCrashLog(context: Context): String? {
    val file = getCrashFile(context)
    return if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else null
  }

  /** Returns true if a non-fatal startup/step error log (`startup_steps.txt`) exists on disk. */
  fun hasStartupStepsLog(context: Context): Boolean =
    getStartupStepsFile(context).let { it.exists() && it.length() > 0L }

  /** Reads the saved non-fatal startup/step errors (`startup_steps.txt`), or null if none exist. */
  fun readStartupStepsLog(context: Context): String? {
    val file = getStartupStepsFile(context)
    return if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else null
  }

  /** Deletes `startup_steps.txt`. */
  fun clearStartupStepsLog(context: Context) {
    synchronized(fileLock) {
      runCatching { getStartupStepsFile(context).delete() }
    }
  }

  /** Builds a share chooser Intent containing the last crash log text. */
  fun createShareIntent(context: Context): Intent {
    val logText = readLastCrashLog(context) ?: "No crash log recorded."
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "AuraClock Crash Log")
      putExtra(Intent.EXTRA_TEXT, logText)
    }
    return Intent.createChooser(sendIntent, "Share last crash log")
  }

  private fun getCrashFile(context: Context): File =
    File(context.applicationContext.filesDir, CRASH_FILE_NAME)

  private fun getStartupStepsFile(context: Context): File =
    File(context.applicationContext.filesDir, STARTUP_STEPS_FILE_NAME)
}
